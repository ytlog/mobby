package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Color.TRANSPARENT
import androidx.activity.SystemBarStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp

/** Named colors for every page. Screens read these instead of embedding hex values. */
internal object MobbyColors {
    val onAccent = Color.White
    val onAccentDisabled = Color.White.copy(alpha = 0.7f)
    val menuMuted = Color(0xFF8E8E93)

    object Dark {
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
        val addSheet = Color(0xFF1F1F1F)
        val addTile = Color(0xFF2A2A2A)
        val addInk = Color(0xFFDBDBDB)
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
        val glassBase = Color(0xFFE9E3D8)
        val glassInnerShadow = Color(0xFF6B5D48)
        val glassShadow = Color(0xFF3A2E1F)
        val statusCard = Color(0xFFF6EFE6)
        val statusAccent = Color(0xFF8A5A12)
        val page = Color(0xFFF6F4EF)
        val card = Color(0xFFFFFDF9)
        val surfaceVariant = Color(0xFFEFECE5)
        val primary = Color(0xFFB4552F)
        val onPrimary = Color.White
        val ink = Color(0xFF2C2A26)
        val muted = Color(0xFF8C8779)
        val outline = Color(0xFFE8E3D9)
        val menuInk = Color(0xFF2C2A26)
        val menuAccent = Color(0xFFB4552F)
        val menuScrim = Color(0xCCF6F4EF)
        val menuStroke = Color.Black.copy(alpha = 0.06f)
        val drawer = Color(0xFFF4F1EA)
        val drawerControl = Color(0xFFFFFDF9)
        val addSheet = Color(0xFFF4F1EA)
        val addTile = Color(0xFFEAE6DD)
        val addInk = Color(0xFF2C2A26)
        val voiceTrack = Color(0xFFF4E4DA)
        val voiceWash = Color(0xFFF2DFD2)
        val voiceCancel = Color(0xFFFFE4E6)
        val catalog = listOf(
            Color(0xFF1E88E5) to Color(0xFFE3F2FD),
            Color(0xFF43A047) to Color(0xFFE8F5E9),
            Color(0xFFFB8C00) to Color(0xFFFFF3E0),
            Color(0xFFE53935) to Color(0xFFFFEBEE),
            Color(0xFF8E24AA) to Color(0xFFF3E5F5),
        )

        object Conversation {
            val canvas = Color(0xFFFBFAF7)
            val ink = Light.ink
            val toolInk = Color(0xFF736E63)
            val toolBorder = Color(0xFFE3DED3)
            val userBubble = Color(0xFFF1EEE7)
            val inputShadow = Color(0x03000000)
        }
    }
}

/** Bars stay transparent so every page's own background runs behind them without a seam. */
fun mobbySystemBarStyle(dark: Boolean): SystemBarStyle =
    if (dark) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)

private val BaseType = Typography()

/** Material's tracking is tuned for Latin and visibly loosens Chinese, so text here carries none. */
internal val MobbyTypography = BaseType.copy(
    titleLarge = BaseType.titleLarge.copy(letterSpacing = 0.sp),
    titleMedium = BaseType.titleMedium.copy(letterSpacing = 0.sp),
    titleSmall = BaseType.titleSmall.copy(letterSpacing = 0.sp),
    bodyLarge = BaseType.bodyLarge.copy(letterSpacing = 0.sp, lineHeight = 26.sp),
    bodyMedium = BaseType.bodyMedium.copy(letterSpacing = 0.sp, lineHeight = 21.sp),
    bodySmall = BaseType.bodySmall.copy(letterSpacing = 0.sp, lineHeight = 17.sp),
    labelLarge = BaseType.labelLarge.copy(letterSpacing = 0.sp),
    labelMedium = BaseType.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = BaseType.labelSmall.copy(letterSpacing = 0.sp),
)

internal val MobbyDarkScheme: ColorScheme = darkColorScheme(
    background = MobbyColors.Dark.page,
    surface = MobbyColors.Dark.page,
    surfaceVariant = MobbyColors.Dark.surfaceVariant,
    surfaceTint = MobbyColors.Dark.primary,
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = MobbyColors.Dark.card,
    surfaceContainer = Color(0xFF232323),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF313131),
    primary = MobbyColors.Dark.primary,
    onPrimary = MobbyColors.Dark.onPrimary,
    primaryContainer = Color(0xFF1A3050),
    onPrimaryContainer = Color(0xFFCFE3FF),
    secondaryContainer = Color(0xFF26303D),
    onSecondaryContainer = Color(0xFFD6E2F2),
    tertiary = MobbyColors.Dark.primary,
    onTertiary = MobbyColors.Dark.onPrimary,
    tertiaryContainer = Color(0xFF1A3050),
    onTertiaryContainer = Color(0xFFCFE3FF),
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
    surfaceTint = MobbyColors.Light.primary,
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFF4F1EA),
    surfaceContainer = Color(0xFFF1EEE7),
    surfaceContainerHigh = Color(0xFFEBE7DF),
    surfaceContainerHighest = Color(0xFFE5E0D6),
    primary = MobbyColors.Light.primary,
    onPrimary = MobbyColors.Light.onPrimary,
    primaryContainer = Color(0xFFF4E4DA),
    onPrimaryContainer = Color(0xFF6E3219),
    secondaryContainer = Color(0xFFF4E9E1),
    onSecondaryContainer = Color(0xFF6E3219),
    tertiary = MobbyColors.Light.primary,
    onTertiary = MobbyColors.Light.onPrimary,
    tertiaryContainer = Color(0xFFF4E4DA),
    onTertiaryContainer = Color(0xFF6E3219),
    onBackground = MobbyColors.Light.ink,
    onSurface = MobbyColors.Light.ink,
    onSurfaceVariant = MobbyColors.Light.muted,
    secondary = MobbyColors.Light.primary,
    error = Color(0xFFB3362B),
    outline = MobbyColors.Light.outline,
    outlineVariant = MobbyColors.Light.outline,
)

@Composable internal fun darkChrome(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

@Composable internal fun cardColor(): Color = if (darkChrome()) MobbyColors.Dark.card else MobbyColors.Light.card

@Composable internal fun raisedColor(): Color = cardColor()

@Composable internal fun addSheetColor(): Color = if (darkChrome()) MobbyColors.Dark.addSheet else MobbyColors.Light.addSheet

@Composable internal fun addTileColor(): Color = if (darkChrome()) MobbyColors.Dark.addTile else MobbyColors.Light.addTile

@Composable internal fun addInkColor(): Color = if (darkChrome()) MobbyColors.Dark.addInk else MobbyColors.Light.addInk

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

/** Fill for code, permission and other cards that sit directly on the conversation canvas. */
@Composable internal fun conversationCardColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

/** Warning ink for results that need a second look but did not fail outright. */
@Composable internal fun statusAccentColor(): Color =
    if (darkChrome()) MobbyColors.Dark.statusAccent else MobbyColors.Light.statusAccent

@Composable internal fun conversationCanvas(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.canvas else MobbyColors.Light.Conversation.canvas

@Composable internal fun conversationInk(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.ink else MobbyColors.Light.Conversation.ink

@Composable internal fun toolCallSurface(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.canvas else conversationCardColor()

@Composable internal fun toolCallInk(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.toolInk else MobbyColors.Light.Conversation.toolInk

@Composable internal fun toolCallBorder(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.toolBorder else MobbyColors.Light.Conversation.toolBorder

@Composable internal fun userBubbleColor(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.userBubble else MobbyColors.Light.Conversation.userBubble

@Composable internal fun userBubbleInk(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.userInk else MobbyColors.Light.Conversation.ink

@Composable internal fun replyActionColor(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.replyAction else MobbyColors.Light.Conversation.toolInk

@Composable internal fun readerInk(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.userInk else MaterialTheme.colorScheme.onSurface

@Composable internal fun readerMuted(): Color =
    if (darkChrome()) MobbyColors.Dark.Conversation.readerMuted else MaterialTheme.colorScheme.onSurfaceVariant
