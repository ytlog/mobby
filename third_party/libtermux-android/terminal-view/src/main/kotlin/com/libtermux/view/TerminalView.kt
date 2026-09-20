/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.libtermux.executor.OutputLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** A single terminal line with text and color */
data class TermLine(val text: String, val color: Int)

/**
 * A terminal emulator View that renders output from a LibTermux session.
 *
 * Usage in XML:
 * ```xml
 * <com.libtermux.view.TerminalView
 *     android:id="@+id/terminalView"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 * ```
 *
 * Usage in Kotlin:
 * ```kotlin
 * binding.terminalView.attachSession(session)
 * session.run("python3 -c 'for i in range(10): print(i)'")
 * ```
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Appearance ────────────────────────────────────────────────────────
    var terminalBackgroundColor: Int = Color.parseColor("#1E1E2E")
        set(value) {
            field = value
            super.setBackgroundColor(value)
        }
    var textColor: Int       = Color.parseColor("#CDD6F4")
    var errorColor: Int      = Color.parseColor("#F38BA8")
    var promptColor: Int     = Color.parseColor("#A6E3A1")
    var cursorColor: Int     = Color.parseColor("#F5C2E7")
    var textSizeSp: Float    = 13f
    var fontFamily: Typeface = Typeface.MONOSPACE
    var lineSpacing: Float   = 1.3f

    // ── Internal state ────────────────────────────────────────────────────
    private val lines = mutableListOf<TermLine>()
    private val maxLines = 2000
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = fontFamily
    }
    private val cursorPaint = Paint().apply { color = cursorColor }
    private var charWidth  = 0f
    private var charHeight = 0f
    private var viewScope  = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentSession: com.libtermux.executor.SessionHandle? = null
    private var inputBuffer = StringBuilder()
    private var cursorRow = 0
    private var cursorCol = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        terminalBackgroundColor = terminalBackgroundColor // triggers setter
        updateTextSize()
    }

    // ── Public API ────────────────────────────────────────────────────────
    /** Attach a session handle to this view */
    fun attachSession(session: com.libtermux.executor.SessionHandle) {
        currentSession = session
        session.output
            .onEach { line -> appendLine(line) }
            .launchIn(viewScope)
    }

    /** Detach current session */
    fun detach() {
        currentSession = null
    }

    /** Append text programmatically */
    fun appendText(text: String, isError: Boolean = false) {
        lines.add(TermLine(text, if (isError) errorColor else textColor))
        if (lines.size > maxLines) lines.removeAt(0)
        invalidate()
    }

    /** Clear the terminal display */
    fun clear() {
        lines.clear()
        inputBuffer.clear()
        cursorRow = 0
        cursorCol = 0
        invalidate()
    }

    /** Set font size in SP */
    fun setFontSizeSp(sp: Float) {
        textSizeSp = sp
        updateTextSize()
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(terminalBackgroundColor)
        val startLine = calculateScrollStart()
        val visibleLines = (height / charHeight).toInt() + 1
        val endLine = minOf(lines.size, startLine + visibleLines)

        for (i in startLine until endLine) {
            val line = lines[i]
            val y = (i - startLine + 1) * charHeight * lineSpacing
            textPaint.color = line.color
            canvas.drawText(line.text, paddingLeft.toFloat(), y, textPaint)
        }

        // Draw input line
        val inputY = (endLine - startLine + 1) * charHeight * lineSpacing
        textPaint.color = promptColor
        canvas.drawText("$ $inputBuffer", paddingLeft.toFloat(), inputY, textPaint)

        // Draw cursor
        val cursorX = paddingLeft + (inputBuffer.length + 2) * charWidth
        canvas.drawRect(cursorX, inputY - charHeight, cursorX + charWidth, inputY, cursorPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        updateTextSize()
    }

    // ── Input ───────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
            showSoftKeyboard()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                val command = inputBuffer.toString().trim()
                inputBuffer.clear()
                if (command.isNotEmpty()) {
                    appendText("$ $command", false)
                    currentSession?.run(command)
                }
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (inputBuffer.isNotEmpty()) {
                    inputBuffer.deleteCharAt(inputBuffer.length - 1)
                    invalidate()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.let {
                    if (it == "\n") {
                        performEditorAction(EditorInfo.IME_ACTION_SEND)
                    } else {
                        inputBuffer.append(it)
                        invalidate()
                    }
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) {
                    if (inputBuffer.isNotEmpty()) inputBuffer.deleteCharAt(inputBuffer.length - 1)
                }
                invalidate()
                return true
            }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────
    private fun appendLine(output: OutputLine) {
        val (text, color) = when (output) {
            is OutputLine.Stdout -> output.text to textColor
            is OutputLine.Stderr -> output.text to errorColor
            is OutputLine.Exit   -> "[Process exited with code ${output.code}]" to promptColor
        }
        text.split("\n").forEach { lines.add(TermLine(it, color)) }
        if (lines.size > maxLines) lines.subList(0, lines.size - maxLines).clear()
        post { invalidate() }
    }

    private fun updateTextSize() {
        textPaint.textSize = textSizeSp * resources.displayMetrics.scaledDensity
        charWidth  = textPaint.measureText("M")
        charHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
    }

    private fun calculateScrollStart(): Int {
        val visibleLines = (height / (charHeight * lineSpacing)).toInt()
        return maxOf(0, lines.size - visibleLines + 2)
    }

    private fun showSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.cancel()
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
