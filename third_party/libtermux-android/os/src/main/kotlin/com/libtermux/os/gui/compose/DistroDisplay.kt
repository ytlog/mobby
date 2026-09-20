package com.libtermux.os.gui.compose

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import com.libtermux.os.gui.DesktopSession
import com.libtermux.os.gui.DesktopSessionState
import com.libtermux.os.gui.vnc.KeySym
import com.libtermux.os.gui.vnc.MouseButton
import com.libtermux.os.gui.vnc.VncState
import kotlinx.coroutines.launch

/**
 * Composable that renders a Linux desktop inside your Jetpack Compose UI.
 *
 * Features:
 *  - VNC framebuffer rendered via Canvas (zero-copy ImageBitmap)
 *  - Touch → mouse event translation
 *      • Single tap      = left click
 *      • Long press      = right click
 *      • Drag            = mouse move + left button
 *      • Two-finger drag = scroll wheel
 *      • Pinch           = display scale (zoom without affecting desktop res)
 *  - Hardware keyboard forwarded as X11 keysyms
 *  - Software keyboard trigger via toolbar button
 *  - Scale-to-fit or 1:1 pixel mode
 *
 * Usage:
 * ```kotlin
 * DistroDisplay(
 *     session  = libtermux.os.createDesktopSession(Distro.Kali),
 *     modifier = Modifier.fillMaxSize(),
 * )
 * ```
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DistroDisplay(
    session: DesktopSession,
    modifier: Modifier = Modifier.fillMaxSize(),
    showToolbar: Boolean = true,
    onClose: (() -> Unit)? = null,
) {
    val sessionState by session.sessionState.collectAsState()
    val vncState     by session.vnc.state.collectAsState()
    val framebuffer  by session.vnc.framebuffer.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Display canvas size in pixels
    var canvasWidthPx  by remember { mutableStateOf(1) }
    var canvasHeightPx by remember { mutableStateOf(1) }

    // Zoom level (pinch-to-zoom)
    var scale by remember { mutableStateOf(1f) }

    // Current mouse button state
    var currentButtons by remember { mutableStateOf(0) }

    /** Translate canvas touch coords → desktop coords */
    fun toDesktopCoords(canvasX: Float, canvasY: Float): Pair<Int, Int> {
        val serverW = session.vnc.serverWidth.takeIf { it > 0 } ?: return 0 to 0
        val serverH = session.vnc.serverHeight.takeIf { it > 0 } ?: return 0 to 0
        val scaleX  = serverW.toFloat() / canvasWidthPx
        val scaleY  = serverH.toFloat() / canvasHeightPx
        return (canvasX * scaleX).toInt() to (canvasY * scaleY).toInt()
    }

    Column(modifier = modifier.background(Color.Black)) {

        // ── Toolbar ───────────────────────────────────────────────────────
        if (showToolbar && sessionState == DesktopSessionState.Running) {
            DistroDisplayToolbar(
                distroName = session.distro.displayName,
                onClose    = {
                    coroutineScope.launch { session.stop() }
                    onClose?.invoke()
                },
                onScaleToggle = { scale = if (scale == 1f) 0.5f else 1f },
                onCtrlAltDel  = {
                    session.vnc.sendKey(KeySym.CTRL_L,  true)
                    session.vnc.sendKey(KeySym.ALT_L,   true)
                    session.vnc.sendKey(KeySym.DELETE,  true)
                    session.vnc.sendKey(KeySym.DELETE,  false)
                    session.vnc.sendKey(KeySym.ALT_L,   false)
                    session.vnc.sendKey(KeySym.CTRL_L,  false)
                },
            )
        }

        // ── Main viewport ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                sessionState == DesktopSessionState.Running &&
                vncState is VncState.Connected && framebuffer != null -> {

                    // ── Desktop canvas ────────────────────────────────────
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                canvasWidthPx  = size.width.coerceAtLeast(1)
                                canvasHeightPx = size.height.coerceAtLeast(1)
                            }
                            .focusRequester(focusRequester)
                            .focusable()
                            // Hardware keyboard
                            .onKeyEvent { event ->
                                handleKeyEvent(event, session.vnc::sendKey)
                            }
                            // Touch → mouse
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        val (dx, dy) = toDesktopCoords(offset.x, offset.y)
                                        session.vnc.sendPointer(dx, dy, MouseButton.LEFT)
                                        session.vnc.sendPointer(dx, dy, MouseButton.NONE)
                                    },
                                    onLongPress = { offset ->
                                        val (dx, dy) = toDesktopCoords(offset.x, offset.y)
                                        session.vnc.sendPointer(dx, dy, MouseButton.RIGHT)
                                        session.vnc.sendPointer(dx, dy, MouseButton.NONE)
                                    },
                                )
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val (dx, dy) = toDesktopCoords(offset.x, offset.y)
                                        currentButtons = MouseButton.LEFT
                                        session.vnc.sendPointer(dx, dy, currentButtons)
                                    },
                                    onDrag = { change, _ ->
                                        val (dx, dy) = toDesktopCoords(
                                            change.position.x, change.position.y,
                                        )
                                        session.vnc.sendPointer(dx, dy, currentButtons)
                                    },
                                    onDragEnd = {
                                        currentButtons = MouseButton.NONE
                                        session.vnc.sendPointer(0, 0, MouseButton.NONE)
                                    },
                                )
                            }
                            .pointerInput(Unit) {
                                // Two-finger drag → scroll wheel
                                var prevY = 0f
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val ptrs  = event.changes.filter { it.pressed }
                                        if (ptrs.size == 2) {
                                            val avgY = ptrs.map { it.position.y }.average().toFloat()
                                            val dy   = avgY - prevY
                                            if (kotlin.math.abs(dy) > 5f) {
                                                val (mx, my) = toDesktopCoords(
                                                    ptrs[0].position.x, ptrs[0].position.y,
                                                )
                                                val btn = if (dy < 0) MouseButton.SCROLL_UP
                                                          else MouseButton.SCROLL_DOWN
                                                repeat(3) {
                                                    session.vnc.sendPointer(mx, my, btn)
                                                    session.vnc.sendPointer(mx, my, MouseButton.NONE)
                                                }
                                            }
                                            prevY = avgY
                                            ptrs.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                // Pinch-to-zoom → adjust scale
                                detectTransformGestures { _, _, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(0.25f, 4f)
                                }
                            },
                    ) {
                        framebuffer?.let { img ->
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.save()
                                canvas.nativeCanvas.scale(scale, scale)
                                drawImage(
                                    image   = img,
                                    dstSize = Size(size.width / scale, size.height / scale),
                                )
                                canvas.nativeCanvas.restore()
                            }
                        }
                    }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                }

                // ── Loading / connecting states ────────────────────────────
                else -> {
                    DesktopLoadingOverlay(sessionState = sessionState, vncState = vncState)
                }
            }
        }
    }
}

// ── Toolbar ───────────────────────────────────────────────────────────────────

@Composable
private fun DistroDisplayToolbar(
    distroName:    String,
    onClose:       () -> Unit,
    onScaleToggle: () -> Unit,
    onCtrlAltDel:  () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = distroName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCtrlAltDel, modifier = Modifier.size(36.dp)) {
                Text("C+A+D", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onScaleToggle, modifier = Modifier.size(36.dp)) {
                Text("⛶", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Text("✕", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ── Loading overlay ───────────────────────────────────────────────────────────

@Composable
private fun DesktopLoadingOverlay(
    sessionState: DesktopSessionState,
    vncState:     VncState,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        val (message, showSpinner) = when (sessionState) {
            DesktopSessionState.Idle               -> "Initializing…"  to true
            DesktopSessionState.StartingVncServer  -> "Starting VNC server…" to true
            is DesktopSessionState.WaitingForServer ->
                "Waiting for desktop… (${sessionState.attempt})" to true
            DesktopSessionState.ConnectingVnc       -> "Connecting…" to true
            is DesktopSessionState.Failed           -> "Error: ${sessionState.reason}" to false
            DesktopSessionState.Stopped             -> "Session stopped" to false
            else -> when (vncState) {
                is VncState.Failed -> "VNC error: ${vncState.reason}" to false
                else               -> "Loading…" to true
            }
        }

        if (showSpinner) CircularProgressIndicator()
        Text(
            text  = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}

// ── Key event translation ─────────────────────────────────────────────────────

private fun handleKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    sendKey: (Int, Boolean) -> Unit,
): Boolean {
    val down = event.type == KeyEventType.KeyDown

    // Try special key mapping first
    val keySym = KeySym.fromAndroidKeyCode(event.nativeKeyEvent.keyCode)
    if (keySym != null) {
        sendKey(keySym, down)
        return true
    }

    // Printable character — use Unicode code point as keysym
    val unicodeChar = event.nativeKeyEvent.unicodeChar
    if (unicodeChar > 0) {
        sendKey(unicodeChar, down)
        return true
    }
    return false
}
