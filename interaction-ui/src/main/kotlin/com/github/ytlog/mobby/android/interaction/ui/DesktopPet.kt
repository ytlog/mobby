package com.github.ytlog.mobby.android.interaction.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.github.ytlog.mobby.android.interaction.domain.ConversationId
import com.github.ytlog.mobby.android.interaction.domain.ExecutionId
import com.github.ytlog.mobby.android.interaction.domain.ExecutionPhase
import com.github.ytlog.mobby.android.interaction.domain.InteractionState
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
        params.height = frame.height
        try {
            wm.updateViewLayout(view, params)
        } catch (_: RuntimeException) {
            detach()
        }
    }

    override fun detach() {
        val view = view ?: return
        this.view = null
        params = null
        try {
            wm.removeView(view)
        } catch (_: RuntimeException) {
        }
    }

    private fun layout(frame: PetFrame) = WindowManager.LayoutParams(
        frame.width,
        frame.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = frame.x
        y = frame.y
    }
}

class DesktopPet internal constructor(
    private val context: Context,
    private val window: PetWindow,
    private val prefs: android.content.SharedPreferences,
    private val stopRun: (ExecutionId) -> Unit,
    private val openConversation: (ConversationId) -> Unit,
) {
    constructor(context: Context, onStop: (ExecutionId) -> Unit, onOpen: (ConversationId) -> Unit) : this(
        context.applicationContext,
        SystemPetWindow(context.applicationContext),
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        onStop,
        onOpen,
    )

    private val session = PetSession()
    private var target: PetTarget? = null
    private var rendered: RenderKey? = null
    private var applied: PetFrame? = null
    private var dragging = false
    private var dragFrameX = 0
    private var dragFrameY = 0
    private var lastState = InteractionState()
    private var lastForeground = false
    private var lastEnabled = false
    private var lastPermitted = false

    init {
        if (prefs.contains(KEY_X)) session.ballX = prefs.getInt(KEY_X, 0)
        if (prefs.contains(KEY_Y)) session.ballY = prefs.getInt(KEY_Y, 0)
    }

    fun update(state: InteractionState, foreground: Boolean, enabled: Boolean, permitted: Boolean) {
        lastState = state
        lastForeground = foreground
        lastEnabled = enabled
        lastPermitted = permitted
        target = petTarget(state)
        if (!session.visible(target, foreground, enabled, permitted)) {
            dragging = false
            rendered = null
            applied = null
            window.detach()
            return
        }
        if (dragging) return
        showFrame()
    }

    private fun refresh() = update(lastState, lastForeground, lastEnabled, lastPermitted)

    private fun showFrame() {
        val target = target ?: return
        val metrics = metrics()
        if (metrics.screenW < metrics.ball || metrics.screenH < metrics.ball) {
            window.detach()
            rendered = null
            applied = null
            return
        }
        val frame = session.place(metrics.screenW, metrics.screenH, metrics.ball, metrics.trayW, metrics.trayH)
        val key = RenderKey(target.execution.value, target.phase, session.expanded, frame.ballOnRight, target.title)
        if (rendered == key && window.attached) {
            if (applied != frame) {
                applied = frame
                window.update(frame)
            }
        } else {
            rendered = key
            applied = frame
            window.attach(petContent(target, frame, metrics), frame)
        }
    }

    private fun petContent(target: PetTarget, frame: PetFrame, metrics: PetMetrics): View {
        val ball = PetBallView(context, target.phase).apply {
            contentDescription = "任务悬浮球"
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
        val tray = tray(target, (frame.width - metrics.ball - gap).coerceAtLeast(0), metrics.density)
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

    private fun tray(target: PetTarget, width: Int, density: Float): View {
        val pad = petPx(12, density)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(MobbyColors.Dark.card.toArgb())
                cornerRadius = 16 * density
            }
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(label(target.title, MobbyColors.Dark.ink.toArgb(), 15f, bold = true))
            addView(label(petStatus(target.phase), MobbyColors.Dark.muted.toArgb(), 13f, bold = false))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val stopEnabled = petCanStop(target.phase)
                addView(action("停止", "停止当前任务", stopEnabled) {
                    session.expanded = false
                    stopRun(target.execution)
                    refresh()
                })
                addView(action("打开", "回到对话", true) {
                    session.expanded = false
                    openConversation(target.conversation)
                    refresh()
                })
                addView(action("收起", "收起悬浮球", true) {
                    session.tuck(target.execution.value)
                    refresh()
                })
            })
        }
    }

    private fun label(text: String, color: Int, size: Float, bold: Boolean) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun action(text: String, description: String, enabled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        contentDescription = description
        isEnabled = enabled
        textSize = 14f
        setTextColor(if (enabled) MobbyColors.Dark.onButton.toArgb() else MobbyColors.Dark.muted.toArgb())
        val pad = petPx(10, resources.displayMetrics.density)
        setPadding(pad, pad, pad, pad)
        if (enabled) setOnClickListener { onClick() }
    }

    private fun metrics(): PetMetrics {
        val display = context.resources.displayMetrics
        val ball = petPx(PET_BALL_DP, display.density).coerceAtLeast(1)
        return PetMetrics(display.widthPixels, display.heightPixels, display.density, ball, petPx(PET_TRAY_WIDTH_DP, display.density), petPx(PET_TRAY_HEIGHT_DP, display.density))
    }

    private data class PetMetrics(val screenW: Int, val screenH: Int, val density: Float, val ball: Int, val trayW: Int, val trayH: Int)
    private data class RenderKey(val execution: String, val phase: ExecutionPhase?, val expanded: Boolean, val ballOnRight: Boolean, val title: String)

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
        val t = SystemClock.uptimeMillis() / 1000f
        val cancelling = phase == ExecutionPhase.CANCELLING
        val bob = if (cancelling) 0f else sin(t * 3.9f) * density * 2.5f
        val blink = if (SystemClock.uptimeMillis() % 3200 in 2800..2920) 0.15f else 1f
        val cx = width / 2f
        val cy = height / 2f + bob
        val radius = min(width, height) / 2f - density
        paint.style = Paint.Style.FILL
        paint.color = MobbyColors.Dark.button.toArgb()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = MobbyColors.Dark.Conversation.toolBorder.toArgb()
        canvas.drawCircle(cx, cy, radius - density / 2f, paint)
        paint.style = Paint.Style.FILL
        paint.color = MobbyColors.Dark.onButton.toArgb()
        val eye = radius * 0.12f
        val eyeY = cy - radius * 0.12f
        canvas.save()
        canvas.scale(1f, blink, cx - radius * 0.28f, eyeY)
        canvas.drawCircle(cx - radius * 0.28f, eyeY, eye, paint)
        canvas.restore()
        canvas.save()
        canvas.scale(1f, blink, cx + radius * 0.28f, eyeY)
        canvas.drawCircle(cx + radius * 0.28f, eyeY, eye, paint)
        canvas.restore()
        val pulse = (sin(t * 5.7f) + 1f) / 2f
        paint.color = if (cancelling) MobbyColors.Dark.error.toArgb() else MobbyColors.Dark.primary.toArgb()
        paint.alpha = (140 + 115 * pulse).toInt()
        canvas.drawCircle(cx, cy + radius * 0.38f, eye * (0.7f + 0.3f * pulse), paint)
        paint.alpha = 255
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
