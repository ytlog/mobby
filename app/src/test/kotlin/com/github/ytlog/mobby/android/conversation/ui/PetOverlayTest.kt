package com.github.ytlog.mobby.android.conversation.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.conversation.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
class PetOverlayTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `quick chat opens the current task in overlay without launching app`() {
        val window = MemoryWindow()
        var chat: ConversationId? = null
        var appOpened = false
        val pet = DesktopPet(context, window, context.getSharedPreferences("pet-quick", Context.MODE_PRIVATE),
            {}, { appOpened = true }, { chat = it })
        pet.update(running("run"), false, true, true)
        window.view!!.described("任务悬浮球")!!.performClick()
        window.view!!.described("悬浮对话")!!.performClick()
        assertEquals(ConversationId("c"), chat)
        assertFalse(appOpened)
        assertEquals(PET_BALL_DP, window.frame?.width)
    }

    @Test fun `idle quick chat requests a new conversation without launching app`() {
        val window = MemoryWindow()
        var requested = false
        val pet = DesktopPet(context, window, context.getSharedPreferences("pet-quick-idle", Context.MODE_PRIVATE),
            {}, { error("Quick chat must not launch the app") }, { requested = true; assertNull(it) })
        pet.update(ConversationState(loading = false), false, true, true)
        window.view!!.described("任务悬浮球")!!.performClick()
        window.view!!.described("悬浮对话")!!.performClick()
        assertTrue(requested)
    }

    @Test fun `chat window asks system layout to keep input above the keyboard`() {
        val window = SystemPetWindow(context)
        val view = View(context)
        window.attach(view, PetFrame(0, 80, 300, 560, true, focusable = true))
        val params = view.layoutParams as android.view.WindowManager.LayoutParams
        assertTrue(params.fitInsetsTypes and android.view.WindowInsets.Type.ime() != 0)
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, params.height)
        window.update(PetFrame(0, 80, 56, 56, true))
        assertEquals(0, params.fitInsetsTypes and android.view.WindowInsets.Type.ime())
        window.detach()
    }

    @Test fun `chat window takes keyboard focus while ball does not`() {
        val window = SystemPetWindow(context)
        val view = View(context)
        val frame = PetFrame(0, 0, 200, 300, true, focusable = true)
        window.attach(view, frame)
        assertEquals(0, (view.layoutParams as android.view.WindowManager.LayoutParams).flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.update(frame.copy(focusable = false))
        assertTrue((view.layoutParams as android.view.WindowManager.LayoutParams).flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        window.detach()
    }

    @Test fun `system window is detached before hide returns`() {
        val view = View(context)
        val window = SystemPetWindow(context)
        window.attach(view, PetFrame(0, 0, 56, 56, true))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(view.isAttachedToWindow)
        window.detach()
        assertFalse(window.attached)
        assertFalse(view.isAttachedToWindow)
    }

    @Test fun `screen operations remove ball and tray until all operations finish`() {
        val window = MemoryWindow()
        val pet = DesktopPet(context, window, context.getSharedPreferences("pet-screen", Context.MODE_PRIVATE), {}, {})
        pet.update(running("run"), false, true, true)
        window.view!!.described("任务悬浮球")!!.performClick()
        assertNotNull(window.view!!.described("停止当前任务"))
        val first = pet.hideForScreenOperation()
        assertFalse(window.attached)
        val second = pet.hideForScreenOperation()
        pet.update(running("run"), false, true, true)
        assertFalse("State updates must not reattach during screen input", window.attached)
        first.close()
        first.close()
        assertFalse(window.attached)
        second.close()
        assertTrue(window.attached)
        assertNotNull(window.view!!.described("停止当前任务"))
        val hidden = pet.hideForScreenOperation()
        pet.update(running("run"), false, false, true)
        hidden.close()
        assertFalse("Restoring must respect settings changed while hidden", window.attached)
    }

    @Test fun `idle background pet appears and returns to app without selecting a conversation`() {
        val window = MemoryWindow()
        val prefs = context.getSharedPreferences("pet-idle", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var opened = false
        val pet = DesktopPet(context, window, prefs, { error("No idle task to stop") }, { opened = true; assertNull(it) })
        val idle = ConversationState(loading = false)
        pet.update(idle, foreground = false, enabled = true, permitted = true)
        assertTrue("Enabled idle pet must be visible outside app", window.attached)
        window.view!!.described("任务悬浮球")!!.performClick()
        assertNull(window.view!!.described("停止当前任务"))
        window.view!!.described("返回应用")!!.performClick()
        assertTrue(opened)
        window.view!!.described("任务悬浮球")!!.performClick()
        window.view!!.described("收起悬浮球")!!.performClick()
        pet.update(idle, foreground = false, enabled = true, permitted = true)
        assertFalse(window.attached)
        pet.update(idle, foreground = true, enabled = true, permitted = true)
        assertFalse(window.attached)
        pet.update(idle, foreground = false, enabled = true, permitted = true)
        assertTrue(window.attached)
        pet.update(idle, foreground = false, enabled = false, permitted = true)
        assertFalse(window.attached)
        pet.update(idle, foreground = false, enabled = true, permitted = false)
        assertFalse(window.attached)
    }

    @Test fun `completed background task becomes idle without removing the ball`() {
        val window = MemoryWindow()
        val prefs = context.getSharedPreferences("pet-completion", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val pet = DesktopPet(context, window, prefs, {}, {})
        pet.update(running("run"), foreground = false, enabled = true, permitted = true)
        assertTrue(window.attached)
        pet.update(ConversationState(loading = false), foreground = false, enabled = true, permitted = true)
        assertTrue("Completion must retain idle pet", window.attached)
        window.view!!.described("任务悬浮球")!!.performClick()
        assertNotNull(window.view!!.described("返回应用"))
        assertNull(window.view!!.described("停止当前任务"))
    }

    @Test fun `finished task remains visible on ball and tray until opened or a new run starts`() {
        val window = MemoryWindow()
        val prefs = context.getSharedPreferences("pet-outcome", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var opened: ConversationId? = null
        val pet = DesktopPet(context, window, prefs, {}, { opened = it })
        pet.update(running("first", title = "整理相册"), foreground = false, enabled = true, permitted = true)
        pet.update(finished(ExecutionPhase.SUCCEEDED), foreground = false, enabled = true, permitted = true)
        assertNotNull(window.view!!.described("任务悬浮球，已完成"))
        window.view!!.described("任务悬浮球，已完成")!!.performClick()
        assertTrue(window.view!!.hasText("整理相册"))
        assertTrue(window.view!!.hasText("已完成"))
        assertNull(window.view!!.described("停止当前任务"))
        window.view!!.described("回到对话")!!.performClick()
        assertEquals(ConversationId("c"), opened)
        pet.update(finished(ExecutionPhase.SUCCEEDED), foreground = true, enabled = true, permitted = true)
        pet.update(finished(ExecutionPhase.SUCCEEDED), foreground = false, enabled = true, permitted = true)
        assertNotNull(window.view!!.described("任务悬浮球"))
        assertNull(window.view!!.described("任务悬浮球，已完成"))

        pet.update(running("second"), foreground = false, enabled = true, permitted = true)
        pet.update(finished(ExecutionPhase.FAILED), foreground = false, enabled = true, permitted = true)
        assertNotNull(window.view!!.described("任务悬浮球，失败"))
        window.view!!.described("任务悬浮球，失败")!!.performClick()
        assertTrue(window.view!!.hasText("失败"))
        assertNull(window.view!!.described("停止当前任务"))

        pet.update(running("third"), foreground = false, enabled = true, permitted = true)
        assertNotNull(window.view!!.described("任务悬浮球"))
        assertNull(window.view!!.described("任务悬浮球，失败"))
    }

    @Test fun `task finished while app is open does not show a stale result after leaving`() {
        val window = MemoryWindow()
        val prefs = context.getSharedPreferences("pet-foreground-outcome", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val pet = DesktopPet(context, window, prefs, {}, {})
        pet.update(running("run"), foreground = true, enabled = true, permitted = true)
        pet.update(finished(ExecutionPhase.FAILED), foreground = true, enabled = true, permitted = true)
        pet.update(finished(ExecutionPhase.FAILED), foreground = false, enabled = true, permitted = true)
        assertNotNull(window.view!!.described("任务悬浮球"))
        assertNull(window.view!!.described("任务悬浮球，失败"))
    }

    @Test fun `completion restores a bubble tucked during the run`() {
        val window = MemoryWindow()
        val prefs = context.getSharedPreferences("pet-tucked-outcome", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val pet = DesktopPet(context, window, prefs, {}, {})
        pet.update(running("run"), foreground = false, enabled = true, permitted = true)
        window.view!!.described("任务悬浮球")!!.performClick()
        window.view!!.described("收起悬浮球")!!.performClick()
        assertFalse(window.attached)
        pet.update(finished(ExecutionPhase.SUCCEEDED), foreground = false, enabled = true, permitted = true)
        assertNotNull(window.view!!.described("任务悬浮球，已完成"))
        window.view!!.described("任务悬浮球，已完成")!!.performClick()
        window.view!!.described("收起悬浮球")!!.performClick()
        assertFalse(window.attached)
        pet.update(finished(ExecutionPhase.SUCCEEDED), foreground = false, enabled = true, permitted = true)
        assertFalse(window.attached)
    }

    @Test fun `pet stays inside the screen and keeps the ball beside the tray`() {
        assertEquals(PetFrame(0, 0, 56, 56, true), petFrame(-20, -5, false, 400, 800, 56, 196, 128))
        assertEquals(PetFrame(344, 744, 56, 56, true), petFrame(999, 999, false, 400, 800, 56, 196, 128))
        val right = petFrame(300, 100, true, 400, 800, 56, 196, 128)
        assertTrue(right.ballOnRight)
        assertEquals(300 to 100, ballOrigin(right, 56))
        assertTrue(right.x >= 0 && right.x + right.width <= 400)
        val left = petFrame(10, 700, true, 400, 800, 56, 196, 128)
        assertFalse(left.ballOnRight)
        assertEquals(10 to 700, ballOrigin(left, 56))
        assertTrue(left.y >= 0 && left.y + left.height <= 800)
    }

    @Test fun `pet is shown outside the app when enabled and not tucked`() {
        assertFalse(petShouldShow(foreground = true, enabled = true, permitted = true, tucked = false))
        assertFalse(petShouldShow(false, false, true, false))
        assertFalse(petShouldShow(false, true, false, false))
        assertFalse(petShouldShow(false, true, true, true))
        assertTrue(petShouldShow(false, true, true, false))
        assertEquals("停止中", petStatus(ExecutionPhase.CANCELLING))
        assertEquals("等待确认", petStatus(ExecutionPhase.AWAITING_APPROVAL))
        assertEquals("失败", petStatus(ExecutionPhase.FAILED))
        assertFalse(petCanStop(ExecutionPhase.CANCELLING))
        assertTrue(petCanStop(ExecutionPhase.RUNNING))
        val occupied = running("run")
        assertEquals(ExecutionId("run"), petTarget(occupied)?.execution)
        assertNull(petTarget(running(null)))
    }

    @Test fun `background task shows a draggable ball that opens a tray`() {
        val window = MemoryWindow()
        val prefs = context.getSharedPreferences("pet-drag", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("x", 100).putInt("y", 200).commit()
        val pet = DesktopPet(context, window, prefs, {}, {})
        pet.update(running("run"), foreground = true, enabled = true, permitted = true)
        assertFalse(window.attached)
        pet.update(running("run"), foreground = false, enabled = false, permitted = true)
        assertFalse(window.attached)
        pet.update(running("run"), foreground = false, enabled = true, permitted = false)
        assertFalse(window.attached)
        pet.update(running(null), foreground = false, enabled = true, permitted = true)
        assertTrue(window.attached)
        pet.update(running("run"), foreground = false, enabled = true, permitted = true)
        val ballSize = petPx(PET_BALL_DP, context.resources.displayMetrics.density).coerceAtLeast(1)
        assertEquals(100, window.frame?.x)
        assertEquals(200, window.frame?.y)
        assertEquals(ballSize, window.frame?.width)
        draw(window.view!!, ballSize)
        touch(window.view!!.described("任务悬浮球")!!, 20f, 20f, 90f, 50f)
        assertEquals(170, window.frame?.x)
        assertEquals(230, window.frame?.y)
        assertEquals(ballSize, window.frame?.width)
        assertEquals(170, prefs.getInt("x", -1))
        touch(window.view!!.described("任务悬浮球")!!, 20f, 20f, 20f, 20f)
        assertTrue((window.frame?.width ?: 0) > ballSize)
        assertNotNull(window.view!!.described("停止当前任务"))
    }

    @Test fun `stop open and tuck use the occupied run and a new run shows again`() {
        val window = MemoryWindow()
        val prefs = context.getSharedPreferences("pet-actions", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var stopped: ExecutionId? = null
        var opened: ConversationId? = null
        val pet = DesktopPet(context, window, prefs, { stopped = it }, { opened = it })
        pet.update(running("run", title = "整理相册"), foreground = false, enabled = true, permitted = true)
        touch(window.view!!.described("任务悬浮球")!!, 8f, 8f, 8f, 8f)
        assertTrue(window.view!!.hasText("整理相册"))
        window.view!!.described("停止当前任务")!!.performClick()
        assertEquals(ExecutionId("run"), stopped)
        assertEquals(petPx(PET_BALL_DP, context.resources.displayMetrics.density).coerceAtLeast(1), window.frame?.width)
        touch(window.view!!.described("任务悬浮球")!!, 8f, 8f, 8f, 8f)
        window.view!!.described("回到对话")!!.performClick()
        assertEquals(ConversationId("c"), opened)
        touch(window.view!!.described("任务悬浮球")!!, 8f, 8f, 8f, 8f)
        window.view!!.described("收起悬浮球")!!.performClick()
        assertFalse(window.attached)
        pet.update(running("run"), foreground = false, enabled = true, permitted = true)
        assertFalse(window.attached)
        pet.update(running("next"), foreground = false, enabled = true, permitted = true)
        assertTrue(window.attached)
        pet.update(running("next"), foreground = true, enabled = true, permitted = true)
        assertFalse(window.attached)
    }

    @Test fun `cancelling disables stop`() {
        val window = MemoryWindow()
        val prefs = context.getSharedPreferences("pet-cancel", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var stopped = false
        val pet = DesktopPet(context, window, prefs, { stopped = true }, {})
        pet.update(running("run", ExecutionPhase.CANCELLING), foreground = false, enabled = true, permitted = true)
        touch(window.view!!.described("任务悬浮球")!!, 8f, 8f, 8f, 8f)
        val stop = window.view!!.described("停止当前任务")!!
        assertFalse(stop.isEnabled)
        assertTrue(window.view!!.hasText("停止中"))
        stop.performClick()
        assertFalse(stopped)
    }

    private fun running(execution: String?, phase: ExecutionPhase = ExecutionPhase.RUNNING, title: String = "当前任务") = ConversationState(
        loading = false,
        conversations = listOf(ConversationSummary(
            Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"), title = title),
            phase,
            occupied = true,
            execution = execution?.let(::ExecutionId),
        )),
    )

    private fun finished(phase: ExecutionPhase) = ConversationState(
        loading = false,
        conversations = listOf(ConversationSummary(
            Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"), title = "整理相册"),
            phase,
            occupied = false,
        )),
    )

    private fun draw(view: View, size: Int) {
        val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, size, size)
        view.draw(Canvas(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)))
    }

    private fun touch(view: View, x: Float, y: Float, upX: Float, upY: Float) {
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, x, y))
        if (upX != x || upY != y) view.dispatchTouchEvent(event(down, down + 16, MotionEvent.ACTION_MOVE, upX, upY))
        view.dispatchTouchEvent(event(down, down + 32, MotionEvent.ACTION_UP, upX, upY))
    }

    private fun event(down: Long, time: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(down, time, action, x, y, 0)


}

private class MemoryWindow : PetWindow {
    var view: View? = null
    var frame: PetFrame? = null
    override val attached get() = view != null
    override fun attach(view: View, frame: PetFrame) {
        this.view = view
        this.frame = frame
    }
    override fun update(frame: PetFrame) { this.frame = frame }
    override fun detach() {
        view = null
        frame = null
    }
}

private fun View.described(text: String): View? {
    if (contentDescription == text || contentDescription?.startsWith("$text，") == true) return this
    if (this is ViewGroup) {
        for (index in 0 until childCount) getChildAt(index).described(text)?.let { return it }
    }
    return null
}

private fun View.hasText(value: String): Boolean {
    if (this is android.widget.TextView && text.toString() == value) return true
    return this is ViewGroup && (0 until childCount).any { getChildAt(it).hasText(value) }
}
