package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import com.github.ytlog.mobby.android.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import com.github.ytlog.mobby.android.conversation.domain.ConversationId
import com.github.ytlog.mobby.android.conversation.domain.ExecutionId
import com.github.ytlog.mobby.android.conversation.domain.ExecutionPhase
import com.github.ytlog.mobby.android.conversation.domain.ConversationState
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

internal interface PetWindow {
    val attached: Boolean
    fun attach(view: View, frame: PetFrame)
    fun update(frame: PetFrame)
    fun detach()
}

internal class SystemPetWindow(context: Context) : PetWindow {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    override val attached get() = view != null

    override fun attach(view: View, frame: PetFrame) {
        if (this.view === view) {
            update(frame)
            return
        }
        detach()
        val next = layout(frame)
        try {
            wm.addView(view, next)
            this.view = view
            params = next
        } catch (_: RuntimeException) {
            this.view = null
            params = null
        }
    }

    override fun update(frame: PetFrame) {
        val view = view ?: return
        val params = params ?: return
        params.x = frame.x
        params.y = frame.y
        params.width = frame.width
        params.height = if (frame.focusable) android.view.ViewGroup.LayoutParams.WRAP_CONTENT else frame.height
        params.flags = flags(frame)
        fitKeyboard(params, frame)
        try {
            wm.updateViewLayout(view, params)
        } catch (_: RuntimeException) {
            detach()
        }
    }

    override fun detach() {
        val view = view ?: return
        wm.removeViewImmediate(view)
        check(!view.isAttachedToWindow) { "Floating window is still attached" }
        this.view = null
        params = null
    }

    private fun layout(frame: PetFrame) = WindowManager.LayoutParams(
        frame.width,
        if (frame.focusable) android.view.ViewGroup.LayoutParams.WRAP_CONTENT else frame.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags(frame),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = frame.x
        y = frame.y
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        fitKeyboard(this, frame)
    }

    private fun fitKeyboard(params: WindowManager.LayoutParams, frame: PetFrame) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Let window measurement fit the visible IME frame. The Compose content
            // sets its preferred maximum height instead of a fixed root-view height.
            params.setFitInsetsTypes(android.view.WindowInsets.Type.systemBars() or
                if (frame.focusable) android.view.WindowInsets.Type.ime() else 0)
        }
    }

    private fun flags(frame: PetFrame) =
        (if (frame.focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
}

class DesktopPet internal constructor(
    private val context: Context,
    private val window: PetWindow,
    private val prefs: android.content.SharedPreferences,
    private val stopRun: (ExecutionId) -> Unit,
    private val openConversation: (ConversationId?) -> Unit,
    private val quickConversation: (ConversationId?) -> Unit = {},
) {
    constructor(context: Context, onStop: (ExecutionId) -> Unit, onOpen: (ConversationId?) -> Unit,
        onChat: (ConversationId?) -> Unit = {}) : this(
        context.applicationContext,
        SystemPetWindow(context.applicationContext),
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        onStop,
        onOpen,
        onChat,
    )

    private val session = PetSession()
    private var target: PetTarget? = null
    private var lastActive: PetTarget? = null
    private var finished: PetTarget? = null
    private var rendered: RenderKey? = null
    private var applied: PetFrame? = null
    private var measuredTrayHeight = 0
    private var screenOperations = 0
    private var dragging = false
    private var dragFrameX = 0
    private var dragFrameY = 0
    private var lastState = ConversationState()
    private var lastForeground = false
    private var lastEnabled = false
    private var lastPermitted = false

    init {
        if (prefs.contains(KEY_X)) session.ballX = prefs.getInt(KEY_X, 0)
        if (prefs.contains(KEY_Y)) session.ballY = prefs.getInt(KEY_Y, 0)
    }

    fun update(state: ConversationState, foreground: Boolean, enabled: Boolean, permitted: Boolean) {
        lastState = state
        lastForeground = foreground
        lastEnabled = enabled
        lastPermitted = permitted
        val active = petTarget(state)
        if (active != null) {
            lastActive = active
            finished = null
        } else if (lastActive != null) {
            val previous = requireNotNull(lastActive)
            val row = state.conversations.firstOrNull { it.conversation.id == previous.conversation }
            if (row != null && petTerminal(row.phase)) {
                if (!foreground) {
                    finished = previous.copy(title = row.conversation.title.ifBlank { previous.title }, phase = row.phase, action = null)
                    session.reveal()
                }
                lastActive = null
            }
        }
        if (foreground) finished = null
        target = active ?: finished
        val visible = session.visible(target, foreground, enabled, permitted)
        if (!visible || screenOperations > 0) {
            dragging = false
            rendered = null
            applied = null
            window.detach()
            return
        }
        if (dragging) return
        showFrame()
    }

    /** Detach synchronously before the screen service reads or acts on the underlying window. */
    fun hideForScreenOperation(): AutoCloseable {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        screenOperations++
        try {
            refresh()
        } catch (error: Throwable) {
            screenOperations--
            throw error
        }
        var released = false
        return AutoCloseable {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            if (!released) {
                released = true
                screenOperations--
                refresh()
            }
        }
    }

    private fun refresh() = update(lastState, lastForeground, lastEnabled, lastPermitted)

    private fun showFrame() {
        val metrics = metrics()
        if (metrics.screenW < metrics.ball || metrics.screenH < metrics.ball) {
            window.detach()
            rendered = null
            applied = null
            return
        }
        val key = RenderKey(target, session.expanded, metrics)
        val rebuild = rendered != key || !window.attached
        val trayWidth = (min(metrics.trayW, metrics.screenW - metrics.ball) - petPx(8, metrics.density)).coerceAtLeast(0)
        val tray = if (rebuild && session.expanded) tray(target, trayWidth).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(trayWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.screenH, View.MeasureSpec.AT_MOST),
            )
            measuredTrayHeight = measuredHeight
        } else null
        val frame = session.place(metrics.screenW, metrics.screenH, metrics.ball, metrics.trayW, measuredTrayHeight)
        if (!rebuild && applied?.ballOnRight == frame.ballOnRight) {
            if (applied != frame) {
                applied = frame
                window.update(frame)
            }
        } else {
            rendered = key
            applied = frame
            window.attach(petContent(target, frame, metrics, tray), frame)
        }
    }

    private fun petContent(target: PetTarget?, frame: PetFrame, metrics: PetMetrics, measuredTray: View?): View {
        val ball = PetBallView(context, target?.phase).apply {
            contentDescription = if (target != null)
                "${AppStrings.floatingTaskBubble2}，${petStatus(target.phase)}" else AppStrings.floatingTaskBubble2
            onTap = {
                session.expanded = !session.expanded
                refresh()
            }
            onDragStart = {
                dragging = true
                dragFrameX = session.frame?.x ?: 0
                dragFrameY = session.frame?.y ?: 0
            }
            onDrag = { dx, dy ->
                val current = metrics()
                val moved = session.drag(dragFrameX, dragFrameY, dx, dy, current.screenW, current.screenH, current.ball)
                window.update(moved)
            }
            onDragEnd = {
                dragging = false
                session.ballX?.let { x -> session.ballY?.let { y -> prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply() } }
                refresh()
            }
            layoutParams = LinearLayout.LayoutParams(metrics.ball, metrics.ball)
        }
        if (!session.expanded) return ball
        val gap = petPx(8, metrics.density).coerceAtLeast(0)
        val tray = measuredTray ?: tray(target, (frame.width - metrics.ball - gap).coerceAtLeast(0))
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (frame.ballOnRight) {
                addView(tray)
                addView(gap(gap))
                addView(ball)
            } else {
                addView(ball)
                addView(gap(gap))
                addView(tray)
            }
        }
    }

    private fun gap(width: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun tray(target: PetTarget?, width: Int) = PetTrayView(
        context, target,
        onStop = {
            if (target != null) {
                session.expanded = false
                stopRun(target.execution)
                refresh()
            }
        },
        onOpen = {
            if (target != null && petTerminal(target.phase)) finished = null
            session.expanded = false
            openConversation(target?.conversation)
            refresh()
        },
        onTuck = {
            session.tuck(target?.execution?.value)
            refresh()
        },
        onChat = {
            session.expanded = false
            quickConversation(target?.conversation)
            refresh()
        },
    ).apply { layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT) }

    private fun metrics(): PetMetrics {
        val display = context.resources.displayMetrics
        val config = context.resources.configuration
        val ball = petPx(PET_BALL_DP, display.density).coerceAtLeast(1)
        return PetMetrics(display.widthPixels, display.heightPixels, display.density, ball,
            petPx(PET_TRAY_WIDTH_DP, display.density), config.fontScale, config.locales.toLanguageTags())
    }

    private data class PetMetrics(
        val screenW: Int, val screenH: Int, val density: Float, val ball: Int, val trayW: Int,
        val fontScale: Float, val locales: String,
    )
    private data class RenderKey(val target: PetTarget?, val expanded: Boolean, val metrics: PetMetrics)

    companion object {
        const val PREFS = "pet-overlay"
        const val ENABLED = "enabled"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}

internal class PetBallView(context: Context, private val phase: ExecutionPhase?) : View(context) {
    var onTap: () -> Unit = {}
    var onDragStart: () -> Unit = {}
    var onDrag: (Int, Int) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = requireNotNull(context.getDrawable(R.drawable.ic_launcher_foreground)).mutate()
    private val surface = context.getColor(R.color.mobby_brand_surface)
    private val blue = context.getColor(R.color.mobby_brand_fold)
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

    init {
        isClickable = true
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        onDragStart()
                    }
                    if (dragging) onDrag(dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onDragEnd() else if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    true
                }
                else -> false
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) / 2f - density).coerceAtLeast(0f)
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = blue
        paint.alpha = 40
        canvas.drawCircle(cx, cy, (radius - density / 2f).coerceAtLeast(0f), paint)
        paint.alpha = 255

        // The launcher foreground reserves one sixth per side for adaptive masks.
        // Remove that padding when drawing the same artwork inside the round bubble.
        val half = radius * 1.5f
        mark.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        mark.draw(canvas)

        if (phase != null) {
            val terminal = petTerminal(phase)
            val cancelling = phase == ExecutionPhase.CANCELLING
            val badge = terminal || cancelling || phase == ExecutionPhase.AWAITING_APPROVAL
            val pulse = (sin(SystemClock.uptimeMillis() / 1000f * 4f) + 1f) / 2f
            val dotX = cx + radius * 0.68f
            val dotY = cy + radius * 0.68f
            paint.style = Paint.Style.FILL
            paint.color = surface
            canvas.drawCircle(dotX, dotY, density * if (badge) 8f else 4.5f, paint)
            paint.color = when (phase) {
                ExecutionPhase.SUCCEEDED -> 0xFF16804A.toInt()
                ExecutionPhase.FAILED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED -> 0xFFBA1A1A.toInt()
                ExecutionPhase.CANCELLED, ExecutionPhase.OUTCOME_UNKNOWN, ExecutionPhase.AWAITING_APPROVAL -> 0xFF8A6416.toInt()
                else -> if (cancelling) 0xFFBA1A1A.toInt() else blue
            }
            paint.alpha = if (badge) 255 else (150 + 105 * pulse).toInt()
            canvas.drawCircle(dotX, dotY, density * if (badge) 6.5f else 3f, paint)
            paint.alpha = 255
            if (badge) {
                paint.color = 0xFFFFFFFF.toInt()
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = density * 9f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                val glyph = when (phase) {
                    ExecutionPhase.SUCCEEDED -> "✓"
                    ExecutionPhase.OUTCOME_UNKNOWN, ExecutionPhase.AWAITING_APPROVAL -> "?"
                    ExecutionPhase.CANCELLED, ExecutionPhase.CANCELLING -> "−"
                    else -> "!"
                }
                canvas.drawText(glyph, dotX, dotY - (paint.ascent() + paint.descent()) / 2f, paint)
            } else if (!cancelling && isAttachedToWindow) postInvalidateDelayed(80L)
        }
    }
}
