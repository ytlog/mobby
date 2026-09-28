package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.localization.FloatingStrings

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
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(20.dp), color = conversationCanvas()) {
            Box {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = new, enabled = system.ready && !screenReading) { Text(UiStrings.newConversation2) }
                        TextButton(onClick = { open(target) }) { Text(UiStrings.openConversation) }
                        TextButton(onClick = close) { Text(FloatingStrings.close) }
                    }
                    Text(state.selected?.conversation?.title ?: FloatingStrings.quickChat,
                        Modifier.padding(horizontal = 16.dp), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    if (!system.ready || !system.connected) Text(system.message, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    val detail = state.selected?.takeIf { it.conversation.id == target }
                    if (detail != null && !state.loading) {
                        key(detail.conversation.id) {
                            Timeline(detail, vm, Modifier.weight(1f),
                                read = { _, _ -> open(detail.conversation.id) },
                                hostActions = InteractionHostActions(share = { open(detail.conversation.id) },
                                    shortcut = { _, _ -> open(detail.conversation.id) }, appearance = {}),
                                proposal = { open(detail.conversation.id) },
                                onSelectPlugin = { ref -> vm.selectQuickPlugin(detail.conversation.id, ref) })
                        }
                        val enabled = system.ready && system.connected && !detail.conversation.archived && !detail.conversation.deleted && composer.conversation == detail.conversation.id
                        OutlinedTextField(composer.value, vm::edit,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            placeholder = { Text(FloatingStrings.inputHint) }, maxLines = 4, enabled = enabled)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { keyboard?.hide(); focus.clearFocus(); vm.readScreen() }, enabled = enabled && !screenReading) { Text(FloatingStrings.recognizeScreen) }
                            val active = detail.turns.lastOrNull { it.occupied }
                            active?.execution?.let { execution ->
                                TextButton(onClick = { vm.stop(execution) }, enabled = petCanStop(active.phase)) { Text(UiStrings.stop) }
                            }
                            TextButton(onClick = { vm.send() }, enabled = enabled && composer.value.text.isNotBlank()) { Text(UiStrings.send) }
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Text(state.error ?: if (state.loading) UiStrings.connecting else UiStrings.noConversationsYet, Modifier.padding(24.dp))
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.fillMaxWidth())
            }
        }
        }
    }
}
