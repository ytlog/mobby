package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.localization.FloatingStrings

internal const val FloatingConversationHeight = 680

/** Shares the app's view model and Timeline; this is only compact window chrome and text input. */
@Composable internal fun FloatingConversationContent(
    vm: ConversationViewModel, target: ConversationId?, new: () -> Unit,
    close: () -> Unit, open: (ConversationId?) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val system by vm.status.collectAsStateWithLifecycle()
    val composer by vm.composer.collectAsStateWithLifecycle()
    val screenReading by vm.screenReading.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val appearance by vm.actions.appearance.collectAsStateWithLifecycle()
    val dark = appearance == Appearance.DARK || appearance == Appearance.SYSTEM && isSystemInDarkTheme()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { for (message in vm.feedback) snackbar.showSnackbar(message) }
    MaterialTheme(colorScheme = if (dark) MobbyDarkScheme else MobbyLightScheme) {
        CompositionLocalProvider(LocalAttachmentPreviewHost provides { open(target) }) {
            val colors = MaterialTheme.colorScheme
            val detail = state.selected?.takeIf { it.conversation.id == target }
            val active = detail?.turns?.lastOrNull { it.occupied }
            val accepted = detail?.turns?.lastOrNull { it.execution != null || it.queued }?.id
            val draft = detail?.conversation?.draft
            val hasDraft = composer.value.text.isNotEmpty() || draft?.text?.isNotEmpty() == true ||
                draft?.attachments?.isNotEmpty() == true || draft?.pendingAttachment != null
            var inputRequested by rememberSaveable(detail?.conversation?.id?.value) { mutableStateOf(false) }
            val inputExpanded = inputRequested || accepted == null || hasDraft
            val enabled = system.ready && system.connected && detail != null && !detail.conversation.archived && !detail.conversation.deleted && composer.conversation == detail.conversation.id
            val inputFocus = remember { FocusRequester() }
            LaunchedEffect(detail?.conversation?.id, accepted) { inputRequested = false }
            LaunchedEffect(inputExpanded, inputRequested) {
                if (!inputExpanded) { keyboard?.hide(); focus.clearFocus() }
                else if (inputRequested) { inputFocus.requestFocus(); keyboard?.show() }
            }
            Surface(Modifier.heightIn(max = FloatingConversationHeight.dp).fillMaxSize().padding(6.dp).shadow(6.dp, RoundedCornerShape(26.dp)), shape = RoundedCornerShape(26.dp), color = conversationCanvas(),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f))) {
                Box {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(12.dp), color = colors.primary.copy(alpha = 0.10f)) {
                                AppIcon(AppIcons.Phone, null, Modifier.padding(10.dp).size(20.dp), tint = colors.primary)
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(FloatingStrings.quickChat, style = MaterialTheme.typography.titleSmall)
                                Text(detail?.conversation?.config?.agent?.label() ?: UiStrings.appName,
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            FloatingAction(AppIcons.New, UiStrings.newConversation2, system.ready && !screenReading, new)
                            FloatingAction(AppIcons.Expand, UiStrings.openConversation, true, { open(target) })
                            FloatingAction(AppIcons.ChevronDown, FloatingStrings.close, true, close)
                        }
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                        if (detail != null && (detail.turns.isNotEmpty() || !detail.conversation.title.isNullOrBlank())) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(5.dp).background(if (active != null) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f), CircleShape))
                                Text(detail.conversation.title ?: FloatingStrings.quickChat, Modifier.weight(1f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                active?.let { Text(petStatus(it.phase), style = MaterialTheme.typography.labelSmall, color = colors.primary) }
                            }
                        }
                        if (!system.ready || !system.connected) {
                            Text(system.message, Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                color = colors.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (detail != null && !state.loading) {
                            key(detail.conversation.id) {
                                if (detail.turns.isEmpty()) {
                                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                                        verticalArrangement = Arrangement.Center) {
                                        Text(FloatingStrings.welcome, style = MaterialTheme.typography.headlineSmall)
                                        Text(FloatingStrings.welcomeDetail, Modifier.padding(top = 8.dp, bottom = 22.dp),
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        EmptyConversationPlugins(detail.conversation.draft.capabilities) { vm.selectQuickPlugin(detail.conversation.id, it) }
                                    }
                                } else {
                                    Timeline(detail, vm, Modifier.weight(1f),
                                        followPadding = PaddingValues(end = 12.dp, bottom = if (inputExpanded) 12.dp else 80.dp),
                                        initialFollow = vm.floatingTimelineFollow[detail.conversation.id] ?: true,
                                        onFollowChanged = { vm.floatingTimelineFollow[detail.conversation.id] = it },
                                        read = { _, _ -> open(detail.conversation.id) },
                                        hostActions = ConversationHostActions(share = { open(detail.conversation.id) },
                                            appearance = {}),
                                        proposal = { open(detail.conversation.id) },
                                        onSelectPlugin = { ref -> vm.selectQuickPlugin(detail.conversation.id, ref) })
                                }
                            }
                            if (inputExpanded) Surface(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 6.dp),
                                shape = RoundedCornerShape(20.dp), color = lerp(conversationCanvas(), colors.primary, 0.035f),
                                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f))) {
                                Column {
                                    if (inputExpanded) {
                                        BasicTextField(composer.value, vm::edit, enabled = enabled, maxLines = 4,
                                            modifier = Modifier.testTag("floating-input").focusRequester(inputFocus).fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp).heightIn(min = 40.dp),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                            cursorBrush = SolidColor(colors.primary),
                                            decorationBox = { input -> Box {
                                                if (composer.value.text.isEmpty()) Text(FloatingStrings.inputHint,
                                                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                                input()
                                            } })
                                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { keyboard?.hide(); focus.clearFocus(); vm.readScreen() }, enabled = enabled && !screenReading,
                                                contentPadding = PaddingValues(horizontal = 10.dp)) {
                                                AppIcon(AppIcons.Phone, null, Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(FloatingStrings.recognizeScreen, style = MaterialTheme.typography.labelMedium)
                                            }
                                            Spacer(Modifier.weight(1f))
                                            if (accepted != null && !hasDraft) {
                                                FloatingAction(AppIcons.ChevronDown, FloatingStrings.collapseInput, true, { inputRequested = false })
                                            }
                                            active?.execution?.let { execution ->
                                                FloatingAction(AppIcons.Stop, UiStrings.stop, petCanStop(active.phase), { vm.stop(execution) })
                                            }
                                            FilledIconButton(onClick = { vm.send() }, enabled = enabled && composer.value.text.isNotBlank(),
                                                modifier = Modifier.size(48.dp), shape = RoundedCornerShape(16.dp)) {
                                                AppIcon(AppIcons.Send, UiStrings.send, Modifier.size(21.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(state.error ?: if (state.loading) UiStrings.connecting else UiStrings.noConversationsYet,
                                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                    if (detail != null && !state.loading && !inputExpanded) {
                        Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            shape = RoundedCornerShape(18.dp), color = lerp(conversationCanvas(), colors.primary, 0.06f),
                            shadowElevation = 3.dp, border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f))) {
                            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { inputRequested = true }, enabled = enabled,
                                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    AppIcon(AppIcons.Edit, null, Modifier.size(17.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(FloatingStrings.continueChat, style = MaterialTheme.typography.labelLarge)
                                }
                                FloatingAction(AppIcons.Phone, FloatingStrings.recognizeScreen, enabled && !screenReading,
                                    { keyboard?.hide(); focus.clearFocus(); vm.readScreen() })
                                active?.execution?.let { execution ->
                                    FloatingAction(AppIcons.Stop, UiStrings.stop, petCanStop(active.phase), { vm.stop(execution) })
                                }
                            }
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
        }
    }
}

@Composable private fun FloatingAction(icon: AppGlyph, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        AppIcon(icon, description, Modifier.size(19.dp))
    }
}
