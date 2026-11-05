package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

internal val LocalWizardFooterInset = staticCompositionLocalOf<(Dp) -> Unit> { {} }

/** Completed tasks can be reviewed while pending tasks stay behind their prerequisite checks. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun WizardSteps(labels: List<String>, current: Int, isComplete: (Int) -> Boolean,
    canSelect: (Int) -> Boolean, select: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val active = remember { BringIntoViewRequester() }
    LaunchedEffect(current, labels) { active.bringIntoView() }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp)
        .testTag("wizard-steps").semantics { isTraversalGroup = true }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val selected = index == current
            val completed = isComplete(index)
            Surface(shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primary else cardColor(),
                modifier = (if (selected) Modifier.bringIntoViewRequester(active) else Modifier)
                    .selectable(selected, enabled = canSelect(index), role = Role.Tab, onClick = { select(index) }).semantics {
                    contentDescription = "${AppStrings.wizardStep(index + 1)}：$label"
                    stateDescription = when { selected -> AppStrings.wizardCurrentStep; completed -> AppStrings.stepCompleted; else -> AppStrings.wizardPendingStep }
                    this.selected = selected
                }) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ink = when {
                        selected -> MaterialTheme.colorScheme.onPrimary
                        completed -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Text("${index + 1}", Modifier.clearAndSetSemantics {}, color = ink, style = MaterialTheme.typography.labelLarge)
                    if (completed && !selected) AppIcon(AppIcons.Check, null, Modifier.size(16.dp), tint = ink)
                    if (selected) Text(label, Modifier.clearAndSetSemantics {}, color = ink, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A single task with navigation pinned below it; other setup tasks are never mounted here. */
@Composable internal fun SetupWizardFrame(title: String, number: Int, heading: String, description: String,
    back: () -> Unit, primaryText: String, primary: () -> Unit, enabled: Boolean = true,
    secondaryText: String = AppStrings.wizardPrevious, secondary: () -> Unit = back,
    busy: Boolean = false, error: String? = null, steps: List<String> = emptyList(),
    isStepComplete: (Int) -> Boolean = { false }, canSelectStep: (Int) -> Boolean = { false }, selectStep: (Int) -> Unit = {}, content: @Composable ColumnScope.() -> Unit = {}) {
    val density = LocalDensity.current
    val footerInset = LocalWizardFooterInset.current
    DisposableEffect(footerInset) { onDispose { footerInset(0.dp) } }
    Column(Modifier.fillMaxSize().imePadding().testTag("setup-wizard")) {
        PageHeader(title, back)
        if (steps.isNotEmpty()) WizardSteps(steps, (number - 1).coerceIn(steps.indices), isStepComplete, { !busy && canSelectStep(it) }, selectStep)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (steps.isEmpty()) Text(AppStrings.wizardStep(number), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
