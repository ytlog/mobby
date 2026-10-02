package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/** Device wording follows the full display, even when this app is in a narrow split window. */
@Composable internal fun isTabletDisplay(): Boolean {
    val density = LocalDensity.current.density
    val mode = LocalView.current.display?.mode
    return if (mode != null && density > 0f) {
        minOf(mode.physicalWidth, mode.physicalHeight) / density >= 600f
    } else {
        LocalConfiguration.current.smallestScreenWidthDp >= 600
    }
}
