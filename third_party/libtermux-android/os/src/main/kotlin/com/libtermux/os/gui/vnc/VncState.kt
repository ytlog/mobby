package com.libtermux.os.gui.vnc

/** States of the VNC client connection lifecycle */
sealed class VncState {
    object Disconnected  : VncState()
    object Connecting    : VncState()
    object Handshaking   : VncState()
    data class Connected(val width: Int, val height: Int, val name: String) : VncState()
    data class Failed(val reason: String, val cause: Throwable? = null)     : VncState()
}

/** Mouse button masks for RFB PointerEvent */
object MouseButton {
    const val NONE   = 0
    const val LEFT   = 1
    const val MIDDLE = 2
    const val RIGHT  = 4
    const val SCROLL_UP   = 8
    const val SCROLL_DOWN = 16
}

/**
 * X11 keysym constants for special keys.
 * Regular printable characters use their Unicode code point directly.
 */
object KeySym {
    const val BACKSPACE = 0xFF08
    const val TAB       = 0xFF09
    const val RETURN    = 0xFF0D
    const val ESCAPE    = 0xFF1B
    const val DELETE    = 0xFFFF
    const val HOME      = 0xFF50
    const val LEFT      = 0xFF51
    const val UP        = 0xFF52
    const val RIGHT     = 0xFF53
    const val DOWN      = 0xFF54
    const val PAGE_UP   = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END       = 0xFF57
    const val F1        = 0xFFBE
    const val F2        = 0xFFBF
    const val F3        = 0xFFC0
    const val F4        = 0xFFC1
    const val F5        = 0xFFC2
    const val F6        = 0xFFC3
    const val F7        = 0xFFC4
    const val F8        = 0xFFC5
    const val F9        = 0xFFC6
    const val F10       = 0xFFC7
    const val F11       = 0xFFC8
    const val F12       = 0xFFC9
    const val SHIFT_L   = 0xFFE1
    const val SHIFT_R   = 0xFFE2
    const val CTRL_L    = 0xFFE3
    const val CTRL_R    = 0xFFE4
    const val ALT_L     = 0xFFE9
    const val ALT_R     = 0xFFEA
    const val SUPER_L   = 0xFFEB

    /** Map Android KeyEvent keyCode to X11 keysym */
    fun fromAndroidKeyCode(keyCode: Int): Int? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_ENTER     -> RETURN
        android.view.KeyEvent.KEYCODE_DEL       -> BACKSPACE
        android.view.KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        android.view.KeyEvent.KEYCODE_TAB       -> TAB
        android.view.KeyEvent.KEYCODE_ESCAPE    -> ESCAPE
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT-> RIGHT
        android.view.KeyEvent.KEYCODE_DPAD_UP   -> UP
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        android.view.KeyEvent.KEYCODE_MOVE_HOME -> HOME
        android.view.KeyEvent.KEYCODE_MOVE_END  -> END
        android.view.KeyEvent.KEYCODE_PAGE_UP   -> PAGE_UP
        android.view.KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        android.view.KeyEvent.KEYCODE_F1        -> F1
        android.view.KeyEvent.KEYCODE_F2        -> F2
        android.view.KeyEvent.KEYCODE_F3        -> F3
        android.view.KeyEvent.KEYCODE_F4        -> F4
        android.view.KeyEvent.KEYCODE_F5        -> F5
        android.view.KeyEvent.KEYCODE_F6        -> F6
        android.view.KeyEvent.KEYCODE_F7        -> F7
        android.view.KeyEvent.KEYCODE_F8        -> F8
        android.view.KeyEvent.KEYCODE_F9        -> F9
        android.view.KeyEvent.KEYCODE_F10       -> F10
        android.view.KeyEvent.KEYCODE_F11       -> F11
        android.view.KeyEvent.KEYCODE_F12       -> F12
        android.view.KeyEvent.KEYCODE_SHIFT_LEFT  -> SHIFT_L
        android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_R
        android.view.KeyEvent.KEYCODE_CTRL_LEFT   -> CTRL_L
        android.view.KeyEvent.KEYCODE_CTRL_RIGHT  -> CTRL_R
        android.view.KeyEvent.KEYCODE_ALT_LEFT    -> ALT_L
        android.view.KeyEvent.KEYCODE_ALT_RIGHT   -> ALT_R
        else -> null
    }
}
