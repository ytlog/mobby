package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** Named colors for every page. Screens read these instead of embedding hex values. */
internal object MobbyColors {
    val onAccent = Color.White
    val onAccentDisabled = Color.White.copy(alpha = 0.7f)
    val menuMuted = Color(0xFF8E8E93)

    object Dark {
        val systemBar = Color(0xFF111213)
        val page = Color(0xFF121212)
        val card = Color(0xFF1E1E1E)
        val surfaceVariant = Color(0xFF2A2A2C)
        val primary = Color(0xFF80BAFF)
        val onPrimary = Color(0xFF102033)
        val ink = Color(0xFFEDEDED)
        val muted = Color(0xFF9A9A9A)
        val error = Color(0xFFE88B8B)
        val statusCard = Color(0xFF1C1C1E)
        val statusAccent = Color(0xFFD6AE70)
        val onError = Color(0xFF3B1010)
        val outline = Color(0xFF2E2E30)
        val menuInk = Color(0xFFF2F2F4)
        val menuAccent = Color(0xFF0A84FF)
        val menuScrim = Color(0x99141416)
        val menuStroke = Color.White.copy(alpha = 0.14f)
        val drawer = Color(0xFF1F1F1F)
        val button = Color(0xFF292929)
        val onButton = Color(0xFFDADADA)
        val voiceWash = Color(0xFF1A2A44)
        val voiceCancel = Color(0xFF3A2226)
        val catalog = listOf(
            Color(0xFF80BAFF) to Color(0xFF1A3050),
            Color(0xFF8BC34A) to Color(0xFF1C2E18),
            Color(0xFFFFB74D) to Color(0xFF3A2A14),
            Color(0xFFEF9A9A) to Color(0xFF3A1C1C),
            Color(0xFFCE93D8) to Color(0xFF2E1A36),
        )

        object Conversation {
            val canvas = Color(0xFF111111)
            val ink = Color(0xFFDADADA)
            val toolInk = Color(0xFF979797)
            val toolBorder = Color(0xFF3A3A3A)
            val userBubble = Color(0xFF292929)
            val userInk = Color(0xFFDBDBDB)
            val readerMuted = Color(0xFFA5A5A5)
            val replyAction = Color(0xFF757575)
        }
    }

    object Light {
        val systemBar = Color(0xFFFAFAFA)
        val statusCard = Color(0xFFF5F1E9)
        val statusAccent = Color(0xFF79551C)
        val page = Color(0xFFF5F5F7)
        val card = Color.White
        val surfaceVariant = Color(0xFFF0F1F3)
        val primary = Color(0xFF2F80FF)
        val onPrimary = Color.White
        val ink = Color(0xFF1A1A1A)
        val muted = Color(0xFF8A8A8A)
        val outline = Color(0xFFE6E6E8)
        val menuInk = Color(0xFF1C1C1E)
        val menuAccent = Color(0xFF007AFF)
        val menuScrim = Color(0xCCF5F5F7)
        val menuStroke = Color.Black.copy(alpha = 0.06f)
        val drawer = Color(0xFFF5F5F5)
        val drawerControl = Color.White
        val voiceTrack = Color(0xFFE4EEFF)
        val voiceWash = Color(0xFFD9E8FF)
        val voiceCancel = Color(0xFFFFE4E6)
        val catalog = listOf(
            Color(0xFF1E88E5) to Color(0xFFE3F2FD),
            Color(0xFF43A047) to Color(0xFFE8F5E9),
            Color(0xFFFB8C00) to Color(0xFFFFF3E0),
            Color(0xFFE53935) to Color(0xFFFFEBEE),
            Color(0xFF8E24AA) to Color(0xFFF3E5F5),
        )

        object Conversation {
            val canvas = Color.White
            val ink = Color(0xFF111111)
            val toolInk = Color(0xFF686868)
            val toolBorder = Color(0xFFDFDFDF)
            val userBubble = Color(0xFFF5F5F5)
            val inputShadow = Color(0x03000000)
        }
    }
}

/** Shared system-bar colors for the Activity, which lives outside the UI module. */
fun mobbySystemBarColor(dark: Boolean): Int =
    (if (dark) MobbyColors.Dark.systemBar else MobbyColors.Light.systemBar).toArgb()

internal val MobbyDarkScheme: ColorScheme = darkColorScheme(
    background = MobbyColors.Dark.page,
    surface = MobbyColors.Dark.page,
    surfaceVariant = MobbyColors.Dark.surfaceVariant,
    primary = MobbyColors.Dark.primary,
    onPrimary = MobbyColors.Dark.onPrimary,
    onBackground = MobbyColors.Dark.ink,
    onSurface = MobbyColors.Dark.ink,
    onSurfaceVariant = MobbyColors.Dark.muted,
    secondary = MobbyColors.Dark.primary,
    error = MobbyColors.Dark.error,
    onError = MobbyColors.Dark.onError,
    outline = MobbyColors.Dark.outline,
    outlineVariant = MobbyColors.Dark.outline,
)

internal val MobbyLightScheme: ColorScheme = lightColorScheme(
    background = MobbyColors.Light.page,
    surface = MobbyColors.Light.page,
    surfaceVariant = MobbyColors.Light.surfaceVariant,
    primary = MobbyColors.Light.primary,
    onPrimary = MobbyColors.Light.onPrimary,
    onBackground = MobbyColors.Light.ink,
    onSurface = MobbyColors.Light.ink,
    onSurfaceVariant = MobbyColors.Light.muted,
    secondary = MobbyColors.Light.primary,
    outline = MobbyColors.Light.outline,
    outlineVariant = MobbyColors.Light.outline,
)

@Composable internal fun darkChrome(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

@Composable internal fun cardColor(): Color = if (darkChrome()) MobbyColors.Dark.card else MobbyColors.Light.card

@Composable internal fun raisedColor(): Color = cardColor()

@Composable internal fun menuInk(): Color = if (darkChrome()) MobbyColors.Dark.menuInk else MobbyColors.Light.menuInk

@Composable internal fun menuMuted(): Color = MobbyColors.menuMuted

@Composable internal fun menuAccent(): Color = if (darkChrome()) MobbyColors.Dark.menuAccent else MobbyColors.Light.menuAccent

@Composable internal fun menuTick(): Color = if (darkChrome()) MobbyColors.onAccent else MobbyColors.Light.menuAccent

@Composable internal fun menuScrim(): Color = if (darkChrome()) MobbyColors.Dark.menuScrim else MobbyColors.Light.menuScrim

@Composable internal fun menuStroke(): Color = if (darkChrome()) MobbyColors.Dark.menuStroke else MobbyColors.Light.menuStroke

@Composable internal fun voiceTrack(): Color =
    if (darkChrome()) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else MobbyColors.Light.voiceTrack

@Composable internal fun voiceWash(cancelArmed: Boolean): Color = when {
    cancelArmed && darkChrome() -> MobbyColors.Dark.voiceCancel
    cancelArmed -> MobbyColors.Light.voiceCancel
    darkChrome() -> MobbyColors.Dark.voiceWash
    else -> MobbyColors.Light.voiceWash
}

@Composable internal fun catalogSwatch(key: String): Pair<Color, Color> {
    val palettes = if (darkChrome()) MobbyColors.Dark.catalog else MobbyColors.Light.catalog
    return palettes[key.hashCode().and(Int.MAX_VALUE) % palettes.size]
}

@Composable internal fun drawerColor(): Color = if (darkChrome()) MobbyColors.Dark.drawer else MobbyColors.Light.drawer

@Composable internal fun drawerControlColor(): Color = if (darkChrome()) MobbyColors.Dark.button else MobbyColors.Light.drawerControl

@Composable internal fun buttonColor(): Color = if (darkChrome()) MobbyColors.Dark.button else cardColor()

@Composable internal fun onButtonColor(): Color = if (darkChrome()) MobbyColors.Dark.onButton else MaterialTheme.colorScheme.onSurface

@Composable internal fun filledButtonColors(
    container: Color = if (darkChrome()) MobbyColors.Dark.button else MaterialTheme.colorScheme.primary,
    content: Color = if (darkChrome()) MobbyColors.Dark.onButton else MaterialTheme.colorScheme.onPrimary,
) = androidx.compose.material3.ButtonDefaults.buttonColors(
    containerColor = container,
    contentColor = content,
    disabledContainerColor = container.copy(alpha = 0.38f),
    disabledContentColor = content.copy(alpha = 0.38f),
)

@Composable internal fun textButtonColors(
    content: Color = if (darkChrome()) MobbyColors.Dark.onButton else MaterialTheme.colorScheme.primary,
) = androidx.compose.material3.ButtonDefaults.textButtonColors(
    contentColor = content,
    disabledContentColor = content.copy(alpha = 0.38f),
)

@Composable internal fun tonalButtonColors() = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
    containerColor = buttonColor(),
    contentColor = onButtonColor(),
    disabledContainerColor = buttonColor().copy(alpha = 0.38f),
    disabledContentColor = onButtonColor().copy(alpha = 0.38f),
)

@Composable internal fun outlinedButtonColors() = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
    containerColor = if (darkChrome()) MobbyColors.Dark.button else Color.Transparent,
    contentColor = if (darkChrome()) MobbyColors.Dark.onButton else MaterialTheme.colorScheme.primary,
    disabledContentColor = (if (darkChrome()) MobbyColors.Dark.onButton else MaterialTheme.colorScheme.primary).copy(alpha = 0.38f),
)

@Composable internal fun conversationCanvas(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.canvas else MobbyColors.Light.Conversation.canvas

@Composable internal fun conversationInk(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.ink else MobbyColors.Light.Conversation.ink

@Composable internal fun toolCallSurface(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.canvas else raisedColor()

@Composable internal fun toolCallInk(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.toolInk else MobbyColors.Light.Conversation.toolInk

@Composable internal fun userBubbleColor(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.userBubble else MobbyColors.Light.Conversation.userBubble

@Composable internal fun userBubbleInk(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.userInk else MobbyColors.Light.Conversation.ink

@Composable internal fun replyActionColor(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.replyAction else onButtonColor()

@Composable internal fun readerInk(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.userInk else MaterialTheme.colorScheme.onSurface

@Composable internal fun readerMuted(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.readerMuted else MaterialTheme.colorScheme.onSurfaceVariant
