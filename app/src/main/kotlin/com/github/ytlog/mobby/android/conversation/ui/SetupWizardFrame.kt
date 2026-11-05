package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

internal val LocalWizardFooterInset = staticCompositionLocalOf<(Dp) -> Unit> { {} }

/** A single task with navigation pinned below it; other setup tasks are never mounted here. */
@Composable internal fun SetupWizardFrame(title: String, number: Int, heading: String, description: String,
    back: () -> Unit, primaryText: String, primary: () -> Unit, enabled: Boolean = true,
    secondaryText: String = AppStrings.wizardPrevious, secondary: () -> Unit = back,
    busy: Boolean = false, error: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    val density = LocalDensity.current
    val footerInset = LocalWizardFooterInset.current
    DisposableEffect(footerInset) { onDispose { footerInset(0.dp) } }
    Column(Modifier.fillMaxSize().imePadding().testTag("setup-wizard")) {
        PageHeader(title, back)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppStrings.wizardStep(number), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(heading, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            error?.let { SettingsCaption(it, error = true) }
        }
        Row(Modifier.fillMaxWidth().onSizeChanged { footerInset(with(density) { it.height.toDp() }) }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = secondary, modifier = Modifier.heightIn(min = 48.dp), colors = textButtonColors()) { Text(secondaryText) }
            Button(onClick = primary, enabled = enabled && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(22.dp), colors = filledButtonColors()) {
                Text(primaryText)
            }
        }
    }
}
