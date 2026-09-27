package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal data class ReadingTarget(val conversation: ConversationId, val key: String, val sequence: Long)
internal data class SkillProposalEditor(val proposal: SkillProposal, val value: TextFieldValue = TextFieldValue(proposal.markdown),
    val preview: SkillContent? = null, val busy: Boolean = false, val error: String? = null, val operation: Long = 0)
internal data class SkillProposalSaved(val operation: Long, val ref: String)
internal class ConversationViewModel(val actions: InteractionUseCases) : ViewModel() {
    fun importAttachment(id: ConversationId, workspace: String, location: String) = enqueue {
        actions.importAttachment(id, workspace, location)
    }
    val readingTarget = MutableStateFlow<ReadingTarget?>(null)
    private var readingSequence = 0L
    fun jumpTo(id: ConversationId, hit: SearchHit) {
        enqueue {
            actions.revealTurn(id, hit.turnId)
            readingTarget.value = ReadingTarget(id, hit.targetKey, ++readingSequence)
        }
    }
    fun consumed(target: ReadingTarget) { readingTarget.compareAndSet(target, null) }
    val state = actions.state.stateIn(viewModelScope, SharingStarted.Eagerly, InteractionState())
    val status = actions.status.stateIn(viewModelScope, SharingStarted.Eagerly, SystemStatus())
    val diagnostic = actions.diagnostic.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticOutput(null, emptyList()))
    val composer = MutableStateFlow(ComposerState())
    val agents = MutableStateFlow<List<AgentOption>>(emptyList())
    val gateways = MutableStateFlow<List<GatewayProfile>>(emptyList())
    val gatewaysLoaded = MutableStateFlow(false)
    val defaultGateway = MutableStateFlow<GatewayDefault?>(null)
    val skills = MutableStateFlow<List<Skill>>(emptyList())
    val skillsError = MutableStateFlow<String?>(null)
    val skillsLoading = MutableStateFlow(false)
    val plugins = MutableStateFlow<List<Plugin>>(emptyList())
    val pluginsError = MutableStateFlow<String?>(null)
    val pluginsLoading = MutableStateFlow(false)
    val permissionBusy = MutableStateFlow<Set<PermissionKey>>(emptySet())
    val permissionSubmitted = MutableStateFlow<Set<PermissionKey>>(emptySet())
    private val permissionAttempts = mutableMapOf<PermissionKey, PermissionDecision>()
    fun decidePermission(execution: ExecutionId, permission: PermissionRequest, allow: Boolean) {
        val key = PermissionKey(execution, permission.id, permission.revision)
        if (key in permissionBusy.value || key in permissionSubmitted.value) return
        permissionAttempts.keys.removeAll { it.execution != execution }
        val decision = permissionAttempts.getOrPut(key) { PermissionDecision(java.util.UUID.randomUUID().toString(), key, allow) }
        if (decision.allow != allow) { feedback.trySend(AppStrings.previousDecisionIsPendingConfirmationRetryTheSameDecision); return }
        permissionBusy.value = permissionBusy.value + key
        enqueue {
            try {
                when (val result = actions.resolvePermission(decision)) {
                    OperationResult.Done -> permissionSubmitted.value = permissionSubmitted.value.filter { it.execution == execution }.toSet() + key
                    is OperationResult.Failed -> feedback.send(result.message)
                }
            } finally { permissionBusy.value = permissionBusy.value - key }
        }
    }
    val skillProposal = MutableStateFlow<SkillProposalEditor?>(null)
    val skillProposalSaved = MutableStateFlow<SkillProposalSaved?>(null)
    private val retainedSkillProposals = mutableMapOf<String, SkillProposalEditor>()
    private var proposalOperation = 0L
    fun openSkillProposal(proposal: SkillProposal) {
        val current = skillProposal.value
        if (current?.proposal?.ref == proposal.ref || current?.busy == true) return
        current?.let { retainedSkillProposals[it.proposal.ref] = it }
        val retained = retainedSkillProposals.remove(proposal.ref)
        skillProposal.value = retained?.copy(proposal = proposal) ?: SkillProposalEditor(proposal, operation = ++proposalOperation)
        if (retained == null) validateSkillProposal()
    }
    fun dismissSkillProposal() {
        val editor = skillProposal.value?.takeUnless { it.busy } ?: return
        retainedSkillProposals[editor.proposal.ref] = editor
        skillProposal.value = null
    }
    fun consumeSkillProposalSaved(operation: Long) {
        skillProposalSaved.value?.takeIf { it.operation == operation }?.let { skillProposalSaved.compareAndSet(it, null) }
    }
    fun editSkillProposal(value: TextFieldValue) {
        skillProposal.update { current ->
            current?.takeUnless { it.busy }?.let {
                val changed = value.text != it.value.text
                it.copy(value = value, preview = if (changed) null else it.preview, error = if (changed) null else it.error)
            } ?: current
        }
    }
    fun validateSkillProposal() {
        val editor = skillProposal.value?.takeUnless { it.busy } ?: return
        val operation = ++proposalOperation
        skillProposal.value = editor.copy(busy = true, error = null, operation = operation)
        viewModelScope.launch {
            try {
                val result = actions.previewSkill(editor.value.text)
                skillProposal.update { current -> if (current?.operation != operation) current else when (result) {
                    is DataResult.Loaded -> current.copy(preview = result.value)
                    is DataResult.Failed -> current.copy(preview = null, error = result.message)
                } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { skillProposal.update { if (it?.operation == operation) it.copy(error = AppStrings.validationIncompleteYourEditsArePreservedPleaseRetry) else it } }
            finally { skillProposal.update { if (it?.operation == operation) it.copy(busy = false) else it } }
        }
    }
    fun saveSkillProposal() {
        val editor = skillProposal.value?.takeUnless { it.busy } ?: return
        if (editor.preview?.issues?.isEmpty() != true) return
        val operation = ++proposalOperation
        skillProposal.value = editor.copy(busy = true, error = null, operation = operation)
        viewModelScope.launch {
            try {
                when (val result = actions.saveSkillProposal(editor.proposal, editor.value.text)) {
                    is DataResult.Loaded -> {
                        retainedSkillProposals.remove(editor.proposal.ref)
                        skillProposal.update { if (it?.operation == operation) null else it }
                        skillProposalSaved.value = SkillProposalSaved(operation, editor.proposal.ref)
                        feedback.send(AppStrings.skillSaved)
                    }
                    is DataResult.Failed -> skillProposal.update { if (it?.operation == operation) it.copy(error = result.message) else it }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { skillProposal.update { if (it?.operation == operation) it.copy(error = AppStrings.saveUnconfirmedYourEditsArePreservedCheckTheSkill) else it } }
            finally { skillProposal.update { if (it?.operation == operation) it.copy(busy = false) else it } }
        }
    }
    val projectEditor = MutableStateFlow<ProjectEditor?>(null)
    val projectCreated = MutableStateFlow<String?>(null)
    private var projectOperation = 0L
    fun openProject() {
        if (projectEditor.value?.busy == true) return
        projectEditor.value = ProjectEditor(operation = ++projectOperation)
    }
    fun editProject(value: ProjectEditor) {
        projectEditor.update { if (it?.operation == value.operation && !it.busy) value.copy(error = null) else it }
    }
    fun dismissProject() { if (projectEditor.value?.busy != true) projectEditor.value = null }
    fun saveProject() {
        val editor = projectEditor.value?.takeUnless { it.busy } ?: return
        projectEditor.value = editor.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = actions.createProject(editor.name)
                projectEditor.update { current -> if (current?.operation != editor.operation) current else when (result) {
                    OperationResult.Done -> { projectCreated.value = editor.name.trim(); null }
                    is OperationResult.Failed -> current.copy(error = result.message)
                } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { projectEditor.update { if (it?.operation == editor.operation) it.copy(error = AppStrings.projectSaveIncompleteCheckAndRetry) else it } }
            finally { projectEditor.update { if (it?.operation == editor.operation) it.copy(busy = false) else it } }
        }
    }
    val workspaces = MutableStateFlow<List<WorkspaceOption>>(emptyList())
    val workspaceError = MutableStateFlow<String?>(null)
    private var workspaceQuery = 0L
    fun loadWorkspaces() {
        val query = ++workspaceQuery
        workspaceError.value = null
        viewModelScope.launch {
            try {
                val result = actions.workspaces()
                if (query == workspaceQuery) when (result) {
                    is DataResult.Loaded -> workspaces.value = result.value
                    is DataResult.Failed -> { workspaces.value = emptyList(); workspaceError.value = result.message }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (query == workspaceQuery) workspaceError.value = AppStrings.couldNotReadWorkspacesPleaseRetry }
        }
    }
    val skillEditor = MutableStateFlow<SkillEditor?>(null)
    val skillEditorSaved = MutableStateFlow<Long?>(null)
    private var editorOperation = 0L
    fun openManualSkill(agent: AgentId) {
        skillEditorSaved.value = null
        skillEditor.value = SkillEditor(agent, manual = true, operation = ++editorOperation)
    }
    fun editSkill(value: SkillEditor) {
        skillEditor.update { current ->
            if (current?.operation == value.operation && !current.busy) value.copy(error = null) else current
        }
    }
    fun consumeSkillEditorSaved(operation: Long) { skillEditorSaved.compareAndSet(operation, null) }
    fun importSkillFile(agent: AgentId, location: String) {
        skillEditorSaved.value = null
        skillEditor.value = SkillEditor(agent, manual = false, operation = ++editorOperation)
        runSkillEditor(AppStrings.fileReadIncompleteSelectItAgain) { editor ->
            when (val result = actions.readSkillImport(location)) {
                is DataResult.Loaded -> editor.copy(name = result.value.name, description = result.value.description,
                    body = result.value.body, markdown = result.value.markdown,
                    preview = result.value.takeIf { it.issues.isEmpty() },
                    error = result.value.issues.joinToString("\n").ifBlank { null })
                is DataResult.Failed -> editor.copy(error = result.message)
            }
        }
    }
    fun validateSkillEditor() = runSkillEditor(AppStrings.validationIncompleteYourEditsArePreservedPleaseRetry) { editor ->
        when (val result = if (editor.manual) actions.previewManualSkill(editor.agent, editor.name, editor.description, editor.body)
            else actions.previewSkill(editor.markdown)) {
            is DataResult.Loaded -> editor.copy(preview = result.value)
            is DataResult.Failed -> editor.copy(error = result.message)
        }
    }
    fun saveSkillEditor() {
        if (skillEditor.value?.preview?.issues?.isEmpty() != true) return
        runSkillEditor(AppStrings.saveUnconfirmedYourEditsArePreservedCheckTheSkill) { editor ->
            when (val result = if (editor.manual) actions.saveManualSkill(editor.agent, editor.name, editor.description, editor.body)
                else actions.importSkill(editor.agent, requireNotNull(editor.preview).markdown)) {
                is DataResult.Loaded -> null
                is DataResult.Failed -> editor.copy(error = result.message)
            }
        }
    }
    // Each operation owns only the editor version it started from. A late import,
    // validation or save cannot replace a newer draft or navigate its page away.
    private fun runSkillEditor(failure: String, action: suspend (SkillEditor) -> SkillEditor?) {
        val original = skillEditor.value?.takeUnless { it.busy } ?: return
        val editor = original.copy(busy = true, error = null, operation = ++editorOperation)
        skillEditor.value = editor
        viewModelScope.launch {
            try {
                val result = action(editor)
                if (skillEditor.value?.operation == editor.operation) {
                    skillEditor.value = result?.copy(busy = false)
                    if (result == null) skillEditorSaved.value = editor.operation
                }
                if (result == null) {
                    loadSkills(catalogueAgent ?: editor.agent)
                    feedback.trySend(AppStrings.skillSavedYouCanAddItToThisDraft)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                skillEditor.update { if (it?.operation == editor.operation) it.copy(error = failure) else it }
            } finally {
                skillEditor.update { if (it?.operation == editor.operation) it.copy(busy = false) else it }
            }
        }
    }
    private var catalogueOperation = 0L
    private var catalogueAgent: AgentId? = null
    fun loadSkills(agent: AgentId) {
        catalogueAgent = agent
        val operation = ++catalogueOperation
        skillsLoading.value = true; skillsError.value = null
        viewModelScope.launch {
            try {
                val result = actions.skills(agent)
                if (operation == catalogueOperation) when (result) {
                    is DataResult.Loaded -> skills.value = result.value
                    is DataResult.Failed -> { skills.value = emptyList(); skillsError.value = result.message }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (operation == catalogueOperation) skillsError.value = AppStrings.skillDirectoryReadIncompletePleaseRetry }
            finally { if (operation == catalogueOperation) skillsLoading.value = false }
        }
    }
    private var pluginCatalogueOperation = 0L
    fun loadPlugins() {
        val operation = ++pluginCatalogueOperation
        pluginsLoading.value = true; pluginsError.value = null
        viewModelScope.launch {
            try {
                val result = actions.plugins()
                if (operation == pluginCatalogueOperation) when (result) {
                    is DataResult.Loaded -> plugins.value = result.value
                    is DataResult.Failed -> { plugins.value = emptyList(); pluginsError.value = result.message }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (operation == pluginCatalogueOperation) pluginsError.value = AppStrings.pluginCatalogueReadIncompletePleaseRetry }
            finally { if (operation == pluginCatalogueOperation) pluginsLoading.value = false }
        }
    }
    val feedback = Channel<String>(Channel.BUFFERED)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val edits = mutableMapOf<ConversationId, Int>()
    private val minimumRevision = mutableMapOf<ConversationId, Long>()
    private var repairing: Pair<ConversationId, NextTurnConfig>? = null
    init {
        viewModelScope.launch { for (action in queue) safe(action) }
        viewModelScope.launch { state.collect { syncComposer(); repairSelectedConversation() } }
        viewModelScope.launch { status.map { it.ready }.distinctUntilChanged().collect { safe { refresh() } } }
    }
    private fun repairSelectedConversation() {
        if (!gatewaysLoaded.value) return
        val conversation = state.value.selected?.conversation ?: return
        val replacement = ConversationGatewayResolver.repair(conversation.config, gateways.value, defaultGateway.value) ?: return
        val marker = conversation.id to conversation.config
        if (repairing == marker) return
        repairing = marker
        enqueue {
            try {
                val current = state.value.selected?.conversation
                if (current?.id == conversation.id && current.config == conversation.config)
                    actions.configure(conversation.id, replacement)
            } finally { if (repairing == marker) repairing = null }
        }
    }
    private fun syncComposer() {
        val c = state.value.selected?.conversation ?: return
        if (composer.value.conversation != c.id || (edits[c.id] ?: 0) == 0 && c.draft.revision >= (minimumRevision[c.id] ?: 0)) {
            composer.value = composer.value.synchronize(c)
        }
    }
    fun edit(value: TextFieldValue) {
        val id = composer.value.conversation ?: return
        composer.value = ComposerState(id, value)
        edits[id] = (edits[id] ?: 0) + 1
        enqueue {
            try {
                val draft = actions.draft(id, value.text, value.selection.start, value.selection.end)
                minimumRevision[id] = draft.revision
            } finally { edits[id] = (edits[id] ?: 1) - 1; syncComposer() }
        }
    }
    fun insertVoice(original: ComposerState, text: String): Boolean {
        val updated = composer.value.insertVoice(original, text) ?: return false
        edit(updated.value)
        return true
    }
    /** Writes the transcript over the original selection, then sends. A changed draft is left untouched. */
    fun sendVoice(original: ComposerState, text: String): String? {
        if (text.isBlank()) return AppStrings.noSpeechRecognizedRecordAgain
        if (!insertVoice(original, text)) return AppStrings.originalDraftChangedVoiceInputWasNotSent
        send()
        return null
    }
    fun enqueue(action: suspend () -> Unit) { queue.trySend(action) }
    fun send(mode: MessageDeliveryMode = MessageDeliveryMode.QUEUE) {
        val id = composer.value.conversation ?: return
        enqueue {
            when (val prepared = actions.prepareMessage(id, mode)) {
                is PreparedMessage.Rejected -> feedback.send(failure(prepared.reason))
                is PreparedMessage.Queued -> Unit
                else -> viewModelScope.launch { safe {
                    when (val result = actions.deliverMessage(prepared)) {
                        is Submission.Rejected -> feedback.send(failure(result.reason))
                        Submission.Unconfirmed -> feedback.send(AppStrings.requestResultUnconfirmedDraftPreservedCheckTheOriginalRequest)
                        is Submission.Queued -> Unit
                        is Submission.Accepted -> Unit
                    }
                } }
            }
        }
    }
    fun sendInProject(project: String, text: String, opened: () -> Unit) {
        if (text.isBlank()) return
        enqueue {
            val agent = defaultGateway.value?.agent ?: state.value.selected?.conversation?.config?.agent ?: AgentId.CODEX
            val config = ConversationGatewayResolver.newConversation(agent, state.value.selected?.conversation,
                state.value.conversations, gateways.value, defaultGateway.value)
            if (config == null) { feedback.send(AppStrings.noGatewayAvailableOpenGatewaySettings); return@enqueue }
            val id = actions.create(config, project)
            actions.draft(id, text, text.length, text.length)
            opened()
            when (val prepared = actions.prepareMessage(id, MessageDeliveryMode.QUEUE)) {
                is PreparedMessage.Rejected -> feedback.send(failure(prepared.reason))
                is PreparedMessage.Queued -> Unit
                else -> viewModelScope.launch { safe {
                    when (val result = actions.deliverMessage(prepared)) {
                        is Submission.Rejected -> feedback.send(failure(result.reason))
                        Submission.Unconfirmed -> feedback.send(AppStrings.requestResultUnconfirmedDraftPreservedCheckTheOriginalRequest)
                        is Submission.Queued -> Unit
                        is Submission.Accepted -> Unit
                    }
                } }
            }
        }
    }
    fun stop(id: ExecutionId) {
        // Stop must not wait behind draft writes or other UI commands in the serial queue.
        viewModelScope.launch { safe {
            val result = actions.stop(id)
            if (result is StopResult.Rejected) feedback.send(failure(result.reason))
        } }
    }
    suspend fun refresh() {
        val profiles = actions.gateways()
        var selected = actions.defaultGateway()
        if (profiles.isNotEmpty() && selected?.let { choice -> profiles.any { it.id == choice.id && it.agent == choice.agent && it.model.isNotBlank() } } != true) {
            ConversationGatewayResolver.preferred(profiles, null)?.let { profile ->
                when (val result = actions.selectDefaultGateway(profile)) {
                    OperationResult.Done -> selected = GatewayDefault(profile.agent, profile.id, profile.version)
                    is OperationResult.Failed -> report(result)
                }
            }
        }
        gateways.value = profiles
        defaultGateway.value = selected
        gatewaysLoaded.value = status.value.ready
        repairSelectedConversation()
        agents.value = actions.agents()
    }
    fun chooseGateway(profile: GatewayProfile, model: String = profile.model, reasoning: String? = null) = enqueue {
        val related = gateways.value.filter { it.id == profile.id }
        val currentAgent = state.value.selected?.conversation?.config?.agent
        val target = related.firstOrNull { it.agent == currentAgent }
            ?: related.firstOrNull { it.agent == defaultGateway.value?.agent }
            ?: profile
        val selectedModel = model.takeIf { candidate -> related.any { it.models.any { item -> item.id == candidate } || it.model == candidate } }
            ?: target.model
        state.value.selected?.conversation?.let { conversation ->
            actions.configure(conversation.id, NextTurnConfig(target.agent, selectedModel, reasoning, conversation.config.workspace, target.id, target.version))
        }
        when (val result = actions.selectDefaultGateway(target)) {
            OperationResult.Done -> defaultGateway.value = GatewayDefault(target.agent, target.id, target.version)
            is OperationResult.Failed -> report(result)
        }
    }
    fun report(result: OperationResult) { if (result is OperationResult.Failed) feedback.trySend(result.message) }
    private suspend fun safe(action: suspend () -> Unit) {
        try { action() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { feedback.send(AppStrings.operationIncompleteCheckConnectionOrStorageAndRetryExisting) }
    }
    private fun failure(reason: Failure): String = when (reason) {
        Failure.PENDING_ATTACHMENT -> AppStrings.finishOrRemovePendingAttachmentsFirst
        Failure.INPUT_TOO_LARGE -> AppStrings.textAndAttachmentsExceedTheKibInputLimitIncluding
        Failure.BUSY -> AppStrings.aTaskIsAlreadyRunningDraftPreserved
        Failure.INVALID_CONFIG -> AppStrings.checkGatewayAndModelSettingsFirst
        Failure.UNSUPPORTED_CAPABILITY -> AppStrings.thisAgentDoesNotSupportTheSelectedCapability
        Failure.EMPTY_DRAFT -> AppStrings.enterATaskFirst
        Failure.PENDING_SUBMISSION -> AppStrings.previousRequestIsUnconfirmedCheckTheOriginalRequestFirst
        Failure.UNAVAILABLE -> AppStrings.runtimeServiceUnavailableCheckConnectionAndRetry
    }
}
