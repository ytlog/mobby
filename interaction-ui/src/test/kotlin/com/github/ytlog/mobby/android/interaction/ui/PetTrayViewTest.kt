package com.github.ytlog.mobby.android.interaction.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.interaction.domain.ConversationId
import com.github.ytlog.mobby.android.interaction.domain.ExecutionId
import com.github.ytlog.mobby.android.interaction.domain.ExecutionPhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PetTrayViewTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val target = PetTarget(ConversationId("c"), "整理今天的照片", ExecutionId("r"), ExecutionPhase.RUNNING)

    @Test fun `idle running and cancelling cards keep their actions reachable`() {
        for ((name, state) in listOf("idle" to null, "running" to target, "cancelling" to target.copy(phase = ExecutionPhase.CANCELLING))) {
            var opened = false
            var stopped = false
            var tucked = false
            val view = PetTrayView(context, state, { stopped = true }, { opened = true }, { tucked = true })
            layout(view)
            val open = view.described(if (state == null) "返回应用" else "回到对话")!!
            assertReachable(view, open)
            open.performClick()
            assertTrue(opened)
            val close = view.described("收起悬浮球")!!
            assertReachable(view, close)
            close.performClick()
            assertTrue(tucked)
            val stop = view.described("停止当前任务")
            if (state == null) assertNull(stop) else {
                assertReachable(view, stop!!)
                assertEquals(state.phase != ExecutionPhase.CANCELLING, stop.isEnabled)
                stop.performClick()
                assertEquals(state.phase != ExecutionPhase.CANCELLING, stopped)
            }
            preview(view, name)
        }
    }

    @Test fun `long text and larger fonts grow the card without hiding actions`() {
        val normal = PetTrayView(context, target, {}, {}, {})
        layout(normal)
        val config = Configuration(context.resources.configuration).apply { fontScale = 1.6f }
        val larger = context.createConfigurationContext(config)
        val view = PetTrayView(larger, target.copy(
            title = "整理旅行照片并且按日期和地点进行分类",
            action = "正在查看相册中的照片与拍摄时间",
        ), {}, {}, {})
        layout(view)
        assertTrue(view.height > normal.height)
        assertTrue(view.height <= 640)
        for (action in listOf("回到对话", "停止当前任务", "收起悬浮球")) {
            assertReachable(view, view.described(action)!!)
        }
        preview(view, "large-text")
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(244, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun assertReachable(root: ViewGroup, action: View) {
        val bounds = Rect(0, 0, action.width, action.height)
        root.offsetDescendantRectToMyCoords(action, bounds)
        assertTrue(action.width >= 48 && action.height >= 48)
        assertTrue(Rect(0, 0, root.width, root.height).contains(bounds))
    }

    private fun preview(view: View, name: String) {
        val path = System.getenv("MOBBY_PET_PREVIEW_DIR") ?: return
        val bitmap = Bitmap.createBitmap(view.width * 3, view.height * 3, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap).apply { scale(3f, 3f) })
        File(path).mkdirs()
        File(path, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

private fun View.described(value: String): View? {
    if (contentDescription == value) return this
    if (this is ViewGroup) for (index in 0 until childCount) {
        getChildAt(index).described(value)?.let { return it }
    }
    return null
}
