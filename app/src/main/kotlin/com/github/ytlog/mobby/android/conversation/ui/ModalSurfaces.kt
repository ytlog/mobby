package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/** Where sheets sit: over the page's own column on large screens rather than the whole window. */
@OptIn(ExperimentalMaterial3Api::class)
internal data class SheetPlacement(val offset: Dp = 0.dp, val maxWidth: Dp = BottomSheetDefaults.SheetMaxWidth)
internal val LocalSheetPlacement = staticCompositionLocalOf { SheetPlacement() }

/** Every bottom sheet in the app: same surface, shape and opening behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AppSheet(onDismissRequest: () -> Unit, placement: SheetPlacement = LocalSheetPlacement.current, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.offset(x = placement.offset),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = placement.maxWidth,
        shape = RoundedCornerShape(28.dp, 28.dp, 0.dp, 0.dp),
        containerColor = addSheetColor(),
        contentColor = addInkColor(),
        content = content,
    )
}

/** Sheet content that scrolls when it does not fit. Rows inset themselves, so their press highlight has room. */
@Composable internal fun SheetBody(maxHeight: Dp = Dp.Unspecified, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
        .verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
        if (title != null) Text(title, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = addInkColor())
        content()
    }
}

/** One choice in a sheet. Rows sit directly against each other and read as a single list. */
@Composable internal fun SheetRow(title: String, detail: String, icon: AppGlyph, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = addInkColor()
    val content = if (enabled) ink else ink.copy(alpha = 0.38f)
    val secondary = if (enabled) toolCallInk() else toolCallInk().copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth().testTag("sheet-row").heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(icon, null, Modifier.size(22.dp), tint = content)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = content)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = secondary)
        }
        AppIcon(AppIcons.ChevronRight, null, Modifier.size(16.dp), tint = secondary)
    }
}

/** Every centered dialog in the app, so none falls back to Material's default surface. */
@Composable internal fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = confirmButton,
    modifier = modifier,
    dismissButton = dismissButton,
    icon = icon,
    title = title?.let { content -> {
        ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold), content)
    } },
    text = text,
    shape = RoundedCornerShape(24.dp),
    containerColor = raisedColor(),
    tonalElevation = 0.dp,
    properties = properties,
)
