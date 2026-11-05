package com.github.ytlog.mobby.android.device

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** App-owned navigation window on the worker's display; no shell window session or global keys. */
internal class VirtualScreenNavigation(context: Context, private val displayId: Int) {
    data class Entry(val label: String, val action: String, val args: Map<String, String>)
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var panel: Presentation? = null
    private var pending: Entry? = null
    @Volatile var visible = false
        private set

    fun show(title: String, entries: List<Entry>) = onMain {
        pending = null
        check(displayId != Display.DEFAULT_DISPLAY)
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        check(display?.isValid == true) { "virtual_navigation_display_unavailable" }
        val window = panel?.takeIf { it.isShowing } ?: Presentation(app, display, android.R.style.Theme_Material_Light_NoActionBar)
        window.setCancelable(false)
        window.setOnDismissListener { if (panel === window) { panel = null; visible = false; pending = null } }
        val body = LinearLayout(window.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(TextView(context).apply { text = title; textSize = 24f })
            if (entries.isEmpty()) addView(TextView(context).apply { text = "No virtual tasks" })
            else addView(ListView(context).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, entries.map { it.label })
                setOnItemClickListener { _, _, index, _ -> pending = entries[index] }
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        window.setContentView(body)
        panel = window
        if (!window.isShowing) window.show()
        window.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        visible = true
    }

    fun takeSelection(): Entry? = onMain { pending.also { pending = null } }
    fun hide() = onMain(::dismiss)

    private fun dismiss() {
        val old = panel
        panel = null; visible = false; pending = null
        old?.dismiss()
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == main.looper) return action()
        val task = FutureTask(action)
        check(main.post(task)) { "virtual_navigation_ui_unavailable" }
        try { return task.get(3, TimeUnit.SECONDS) }
        catch (failure: java.util.concurrent.TimeoutException) {
            task.cancel(false)
            main.post(::dismiss)
            throw failure
        }
    }
}
