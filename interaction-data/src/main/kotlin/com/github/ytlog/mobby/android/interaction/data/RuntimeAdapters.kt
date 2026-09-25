package com.github.ytlog.mobby.android.interaction.data

import com.github.ytlog.mobby.android.localization.CatalogIds

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import com.github.ytlog.mobby.android.runtime.api.gateway.*

import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.interaction.domain.AgentId as DomainAgent
import com.github.ytlog.mobby.android.runtime.api.AgentId as RuntimeAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import java.util.UUID

internal fun RuntimeError.message(): String = when (code) {
    ErrorCode.INPUT_TOO_LARGE -> AppStrings.textAndAttachmentsExceedTheInputLimitShortenText
    ErrorCode.NOT_READY -> AppStrings.runtimeIsNotReady
    ErrorCode.BUSY -> AppStrings.aTaskIsRunningWaitOrOpenItsConversation
    ErrorCode.INVALID_CONFIG -> AppStrings.invalidGatewayOrModelConfigurationCheckSettings
    ErrorCode.GATEWAY_PROBE_FAILED -> AppStrings.noAvailableAgentConfirmedCheckAddressModelKeyAnd
    ErrorCode.PERMISSION_DENIED -> AppStrings.permissionDeniedTaskDidNotComplete
    ErrorCode.UNSUPPORTED_CAPABILITY -> AppStrings.thisAgentDoesNotSupportThisCapabilityYet
    ErrorCode.DISCONNECTED -> AppStrings.disconnectedResultUnconfirmed
    ErrorCode.RESOURCE_MISSING -> AppStrings.requiredFileIsMissingOrUnreadable
    ErrorCode.RESOURCE_BUDGET_EXCEEDED -> AppStrings.attachmentStorageIsFullIncreaseCapacityInStorageRetention
    ErrorCode.STORAGE_FULL -> AppStrings.storageWriteFailedCheckAvailableSpace
    ErrorCode.TIMEOUT -> AppStrings.taskTimedOut
    ErrorCode.INTERRUPTED -> AppStrings.executionInterruptedResultUnconfirmed
    ErrorCode.STALE_APPROVAL, ErrorCode.STALE_INTERACTION -> AppStrings.thisApprovalRequestHasExpired
    ErrorCode.REQUEST_CONFLICT -> AppStrings.requestIdConflictExecutionWasNotRepeated
    ErrorCode.INCOMPATIBLE_VERSION -> AppStrings.incompatibleRuntimeApiUpdateTheApp
    ErrorCode.NOT_FOUND -> AppStrings.requestWasNotAcceptedYouCanSubmitItAgain
    ErrorCode.PROTOCOL_ERROR -> AppStrings.agentFailedOrReturnedAnIncompleteProtocolResponseExpand
}
internal fun RunPhase.domain(): ExecutionPhase = ExecutionPhase.valueOf(if (this == RunPhase.STARTING) "ACCEPTED" else name)
internal fun RuntimeError.failure() = when (code) {
    ErrorCode.INPUT_TOO_LARGE -> Failure.INPUT_TOO_LARGE
    ErrorCode.BUSY -> Failure.BUSY
    ErrorCode.UNSUPPORTED_CAPABILITY -> Failure.UNSUPPORTED_CAPABILITY
    ErrorCode.INVALID_CONFIG, ErrorCode.PERMISSION_DENIED -> Failure.INVALID_CONFIG
    else -> Failure.UNAVAILABLE
}
internal fun TurnExecution.request() = RunRequest(RequestId(turnId.value), RuntimeAgent.valueOf(config.agent.name), WorkspaceRef(config.workspace),
    (if (projectRules.isBlank()) emptyList() else listOf(InputPart.Text("Project rules for this turn:\n" + projectRules))) +
        listOf(InputPart.Text(draft.text)) + draft.attachments.map { InputPart.Resource(ResourceRef(it)) }, config.model,
    GatewayProfileRef(config.gatewayProfile, config.gatewayVersion), config.reasoning, session?.let(::SessionRef), draft.capabilities.map(::CapabilityRef).toSet(), requestedOutput = if (creatingSkill) RequestedOutput.SKILL_PROPOSAL else RequestedOutput.TEXT)
internal class RuntimeExecutionAdapter(private val client: RuntimeClient) : ExecutionPort {
    override suspend fun respondToDevice(request: com.github.ytlog.mobby.android.deviceinteraction.model.DeviceInteractionResponse): OperationResult =
        when (val result = client.respondToDevice(request)) {
            CommandResult.Accepted, CommandResult.AlreadyTerminal -> OperationResult.Done
            is CommandResult.Rejected -> OperationResult.Failed(result.error.message())
        }
    override suspend fun resolvePermission(decision: PermissionDecision): OperationResult = when (val result = client.resolveApproval(
        ApprovalDecision(CommandId(decision.commandId), RunId(decision.key.execution.value), decision.key.approvalId,
            if (decision.allow) ApprovalChoice.ALLOW_ONCE else ApprovalChoice.DENY, decision.key.revision))) {
        CommandResult.Accepted -> OperationResult.Done
        CommandResult.AlreadyTerminal -> OperationResult.Failed(AppStrings.thisApprovalRequestHasExpired)
        is CommandResult.Rejected -> OperationResult.Failed(result.error.message())
    }
    override suspend fun submit(turn: TurnExecution): Submission = try {
        when (val result = client.submit(turn.request())) {
            is SubmitResult.Accepted -> Submission.Accepted(ExecutionId(result.runId.value))
            is SubmitResult.Rejected -> Submission.Rejected(result.error.failure(), result.activeRunId?.let { ExecutionId(it.value) })
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Submission.Unconfirmed }
    override suspend fun lookup(turnId: TurnId): Submission = when (val found = client.findByRequest(RequestId(turnId.value))) {
        is RequestLookup.Found -> Submission.Accepted(ExecutionId(found.runId.value))
        RequestLookup.NotFound -> Submission.Rejected(Failure.UNAVAILABLE)
        RequestLookup.Unavailable -> Submission.Unconfirmed
    }
    override suspend fun cancel(executionId: ExecutionId): StopResult = when (val result = client.cancel(CancelRequest(CommandId(UUID.randomUUID().toString()), RunId(executionId.value)))) {
        CommandResult.Accepted -> StopResult.Accepted
        CommandResult.AlreadyTerminal -> StopResult.AlreadyTerminal
        is CommandResult.Rejected -> StopResult.Rejected(result.error.failure())
    }
    override fun observe(executionId: ExecutionId): Flow<ExecutionFact> = client.observe(RunId(executionId.value)).mapNotNull {
        val snapshot = if (it is RuntimeUpdate.Baseline) it.snapshot else (client.snapshot(RunId(executionId.value)) as? SnapshotResult.Found)?.snapshot
        snapshot?.let { ExecutionFact(executionId, RunProjection.verifiedPhase(it).domain()) }
    }
}
internal class RuntimeSystemAdapter(private val context: android.content.Context, private val client: RuntimeClient, private val admin: RuntimeAdminClient, private val diagnostics: RuntimeDiagnosticsClient) : SystemPort {
    private val camera = CameraCaptureStore(context)
    private suspend fun <T> cameraResult(block: suspend () -> T): DataResult<T> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try { DataResult.Loaded(block()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { DataResult.Failed(AppStrings.cannotUpdatePhotoCaptureStateHandleTheExistingPhoto) }
    }
    override suspend fun beginCapture(conversation: String, workspace: String) = cameraResult { camera.begin(conversation, workspace) }
    override suspend fun capture() = cameraResult { camera.current() }
    override suspend fun finishCapture(id: String, success: Boolean) = cameraResult { camera.finish(id, success) }
    override suspend fun previewCapture(id: String) = cameraResult { AttachmentPreview(camera.preview(id)) }
    override suspend fun discardCapture(id: String): OperationResult = when (val result = cameraResult { camera.discard(id) }) {
        is DataResult.Loaded -> OperationResult.Done
        is DataResult.Failed -> OperationResult.Failed(result.message)
    }
    private val grants = context.getSharedPreferences("attachment-grants", android.content.Context.MODE_PRIVATE)
    private val grantLock = Any()
    override suspend fun retainAttachmentGrants(locations: Set<String>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        camera.retain(locations)
        val resolver = context.contentResolver
        val owned = synchronized(grantLock) { grants.getStringSet("owned", emptySet())!!.toSet() }
        resolver.persistedUriPermissions.filter { it.uri.toString() in owned && it.uri.toString() !in locations }.forEach { grant ->
            try { resolver.releasePersistableUriPermission(grant.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Provider already revoked the grant. */ }
        }
        synchronized(grantLock) { check(grants.edit().putStringSet("owned", owned.intersect(locations)).commit()) }
    }
    override suspend fun previewAttachment(workspace: String, ref: String, expanded: Boolean): DataResult<AttachmentPreview> = when (val result = admin.previewResource(ResourceRef(ref), WorkspaceRef(workspace), expanded)) {
        is AdminResult.Success -> DataResult.Loaded(AttachmentPreview(result.value.bytes))
        is AdminResult.Failed -> DataResult.Failed(result.error.message())
    }
    override suspend fun attachment(workspace: String, ref: String): DataResult<Attachment> = when (val result = admin.resource(ResourceRef(ref), WorkspaceRef(workspace))) {
        is AdminResult.Success -> DataResult.Loaded(Attachment(result.value.ref.value, result.value.name, result.value.sizeBytes, result.value.mediaType))
        is AdminResult.Failed -> DataResult.Failed(result.error.message())
    }
    override suspend fun importAttachment(workspace: String, location: String): DataResult<Attachment> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(location)
            require(uri.scheme == "content")
            if (uri.authority == "${context.packageName}.captures") camera.importing(location)
            val resolver = context.contentResolver
            // Some providers offer only a transient grant. Import still works now; a later
            // retry reports permission loss explicitly and asks the user to select again.
            try {
                val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!alreadyGranted) synchronized(grantLock) { check(grants.edit().putStringSet("owned", grants.getStringSet("owned", emptySet())!!.toSet() + location).commit()) }
            }
            catch (_: SecurityException) { /* transient grant remains valid for this import */ }
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: AppStrings.attachment
            val bytes = java.io.ByteArrayOutputStream()
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; bytes.write(buffer, 0, n); require(bytes.size() <= 2 * 1024 * 1024) }
            } ?: return@withContext DataResult.Failed(AppStrings.cannotReadTheSelectedFile)
            when (val result = admin.importResource(ImportResourceRequest(WorkspaceRef(workspace), name, bytes.toByteArray()))) {
                is AdminResult.Success -> DataResult.Loaded(Attachment(result.value.ref.value, result.value.name, result.value.sizeBytes, result.value.mediaType))
                is AdminResult.Failed -> DataResult.Failed(if (result.error.code in setOf(ErrorCode.INVALID_CONFIG, ErrorCode.UNSUPPORTED_CAPABILITY)) AppStrings.supportsUtfTextUpToKibOrPngJpeg else result.error.message())
            }
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { DataResult.Failed(AppStrings.filePermissionExpiredSelectTheFileAgain) }
        catch (_: Exception) { DataResult.Failed(AppStrings.fileCouldNotBeReadOrExceedsMibRetry) }
    }
    private fun SkillSummary.domain() = Skill(ref.value, DomainAgent.valueOf(agent.name), name, description, if (source == SkillSource.USER) CatalogIds.USER_SKILLS else CatalogIds.BUILTIN_SKILLS, available, error?.let { AppStrings.invalidSkillMetadataDirectoryNameOrFilePath })
    private fun SkillPreview.domain() = SkillContent(name, description, body, markdown, issues.map { when (it) {
        SkillIssue.INVALID_FRONTMATTER -> AppStrings.invalidYamlMetadataCorrectItBeforeImporting
        SkillIssue.UNCLOSED_FRONTMATTER -> AppStrings.metadataIsMissingTheClosingSeparator
        SkillIssue.INVALID_NAME -> AppStrings.nameMustBeLowercaseLettersDigitsOrHyphensAnd
        SkillIssue.INVALID_DESCRIPTION -> AppStrings.describeThePurposeAndTriggersInAtMostCharacters
        SkillIssue.EMPTY_BODY -> AppStrings.skillInstructionsCannotBeEmpty
    } })
    private fun <T, R> AdminResult<T>.result(map: (T) -> R): DataResult<R> = when (this) {
        is AdminResult.Success -> DataResult.Loaded(map(value))
        is AdminResult.Failed -> DataResult.Failed(when (error.code) {
            ErrorCode.REQUEST_CONFLICT -> AppStrings.aSkillWithThisNameExistsRenameItThe
            ErrorCode.INVALID_CONFIG -> AppStrings.invalidSkillContentOrMetadataCheckNamePurposeInstructions
            ErrorCode.RESOURCE_MISSING -> AppStrings.skillFileChangedOrCannotBeReadRefreshThe
            else -> error.message()
        })
    }
    override suspend fun workspaces() = admin.listWorkspaces().result { list -> list.map { WorkspaceOption(it.ref.value, it.name) } }
    override suspend fun createWorkspace(name: String) = when (val result = admin.createWorkspace(name)) {
        is AdminResult.Success -> DataResult.Loaded(WorkspaceOption(result.value.ref.value, result.value.name))
        is AdminResult.Failed -> DataResult.Failed(if (result.error.code == ErrorCode.INVALID_CONFIG) AppStrings.workspaceNameIsInvalidOrAlreadyExistsUseA else result.error.message())
    }
    override suspend fun skills(agent: DomainAgent) = admin.listSkills(RuntimeAgent.valueOf(agent.name)).result { list -> list.map { it.domain() } }
    override suspend fun plugins() = admin.listPlugins().result { list -> list.map { item ->
        Plugin(item.ref.value, item.name, item.description, item.available, item.reason, item.category,
            PluginAccess.valueOf(item.access.name), item.permissions,
            item.grant?.let { PluginGrant(it.ref.value, it.label, it.available, it.reason, it.permissions) })
    } }
    override suspend fun readSkill(ref: String) = admin.readSkill(CapabilityRef(ref)).result { it.domain() }
    override suspend fun previewSkill(markdown: String) = admin.previewSkill(markdown).result { it.domain() }
    override suspend fun previewManualSkill(agent: DomainAgent, name: String, description: String, body: String) = admin.previewManualSkill(ManualSkillRequest(RuntimeAgent.valueOf(agent.name), name, description, body)).result { it.domain() }
    override suspend fun importSkill(agent: DomainAgent, markdown: String) = admin.importSkill(RuntimeAgent.valueOf(agent.name), markdown).result { it.domain() }
    override suspend fun saveManualSkill(agent: DomainAgent, name: String, description: String, body: String) = admin.saveManualSkill(ManualSkillRequest(RuntimeAgent.valueOf(agent.name), name, description, body)).result { it.domain() }
    override suspend fun readSkillImport(location: String): DataResult<SkillContent> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(location)
            require(uri.scheme == "content")
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (name?.endsWith(".md", true) != true) return@withContext DataResult.Failed(AppStrings.selectAMdSkillFile)
            val bytes = java.io.ByteArrayOutputStream()
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; bytes.write(buffer, 0, n); require(bytes.size() <= 128 * 1024) }
            } ?: return@withContext DataResult.Failed(AppStrings.cannotReadTheSelectedFile)
            previewSkill(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString())
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { DataResult.Failed(AppStrings.fileReadPermissionExpiredSelectTheFileAgain) }
        catch (_: Exception) { DataResult.Failed(AppStrings.cannotImportSelectAUtfMarkdownFileUpTo) }
    }
    override suspend fun eventHistoryLimits(): DataResult<EventHistoryLimits> = when (val result = admin.eventHistorySettings()) {
        is AdminResult.Success -> DataResult.Loaded(EventHistoryLimits(result.value.retentionDays, result.value.budgetMiB, result.value.outputRetentionDays, result.value.outputBudgetMiB, result.value.attachmentBudgetMiB))
        is AdminResult.Failed -> DataResult.Failed(if (result.error.code == ErrorCode.DISCONNECTED) AppStrings.disconnectedPleaseRetry else AppStrings.cannotReadStorageSettingsPleaseRetry)
    }
    override suspend fun saveEventHistoryLimits(value: EventHistoryLimits): OperationResult {
        if (value.days !in 1..3650 || value.mib !in 1..1024 || value.outputDays !in 1..3650 || value.outputMiB !in 1..4096 || value.attachmentMiB !in 1..8192) return OperationResult.Failed(AppStrings.storageSettingsAreOutOfRange)
        return when (val result = admin.saveEventHistorySettings(EventHistorySettings(value.days, value.mib, value.outputDays, value.outputMiB, value.attachmentMiB))) {
            is AdminResult.Success -> OperationResult.Done
            is AdminResult.Failed -> OperationResult.Failed(if (result.error.code == ErrorCode.DISCONNECTED) AppStrings.disconnectedSaveUnconfirmed else AppStrings.cannotSaveStorageSettingsPleaseRetry)
        }
    }
    override val status = combine(client.connection, admin.environment, diagnostics.state) { connection, environment, diagnostic ->
        SystemStatus(environment.phase == EnvironmentPhase.READY, connection == ConnectionState.CONNECTED,
            if (connection == ConnectionState.DISCONNECTED) AppStrings.disconnectedResultUnconfirmed else environment.summary,
            diagnostic.phase?.let { !it.terminal || it == RunPhase.OUTCOME_UNKNOWN } == true)
    }
    override val diagnostic = diagnostics.state.map { DiagnosticOutput(it.phase?.domain(), it.output) }
    override suspend fun agents(): List<AgentOption> = when (val result = client.capabilities()) {
        is CapabilityResult.Unavailable -> DomainAgent.values().map { AgentOption(it, emptyMap(), result.error.message(), false, emptySet()) }
        is CapabilityResult.Available -> result.capabilities.agents.map { AgentOption(DomainAgent.valueOf(it.agentId.name), it.models.associate { m -> m.id to m.reasoningLevels }, it.unavailableReason?.message(), it.supportsResume, it.skillCapabilities.map { ref -> ref.value }.toSet(), it.supportsResources, it.supportsImages, it.supportsApproval, it.models.associate { m -> m.id to m.name }) }
    }
    override suspend fun checkGateway(profile: GatewayProfile): DataResult<GatewayCheckReport> =
        when (val result = admin.validateGateway(GatewayProfileRef(profile.id, profile.version), RuntimeAgent.valueOf(profile.agent.name))) {
            is AdminResult.Failed -> DataResult.Failed(result.error.message())
            is AdminResult.Success -> DataResult.Loaded(GatewayCheckReport(result.value.outcome == GatewayCheckOutcome.SUCCEEDED, result.value.message()))
        }
    override suspend fun gateways(): List<GatewayProfile> = when (val result = admin.listGatewayProfiles()) {
        is AdminResult.Success -> result.value.map { GatewayProfile(DomainAgent.valueOf(it.agent.name), it.ref.id, it.ref.version, it.endpoint, it.model, it.protocol.name, it.hasCredential, it.models.map { model -> GatewayModel(model.id, model.name) }, it.catalogError, it.temporary) }
        is AdminResult.Failed -> throw IllegalStateException(result.error.message())
    }
    override suspend fun defaultGateway(): GatewayDefault? = when (val result = admin.defaultGateway()) {
        is AdminResult.Success -> result.value?.let { GatewayDefault(DomainAgent.valueOf(it.agent.name), it.profile.id, it.profile.version) }
        is AdminResult.Failed -> throw IllegalStateException(result.error.message())
    }
    override suspend fun selectDefaultGateway(profile: GatewayProfile): OperationResult =
        admin.selectDefaultGateway(GatewaySelection(RuntimeAgent.valueOf(profile.agent.name), GatewayProfileRef(profile.id, profile.version))).operation()
    override suspend fun deleteGateway(id: String): OperationResult = admin.deleteGatewayProfile(id).operation()
    override suspend fun fetchGatewayModels(edit: GatewayEdit): DataResult<GatewayCatalogResult> {
        val secret = edit.credential?.let(::SecretInput)
        edit.credential?.fill('\u0000')
        return when (val result = admin.fetchGatewayModels(FetchGatewayModelsRequest(edit.id,
            GatewayCandidateAddresses(edit.addresses.responses, edit.addresses.messages), secret))) {
            is AdminResult.Success -> DataResult.Loaded(GatewayCatalogResult(
                result.value.models.map { GatewayModel(it.id, it.name) }, result.value.catalogError))
            is AdminResult.Failed -> DataResult.Failed(result.error.message())
        }
    }
    override suspend fun saveGateway(edit: GatewayEdit): GatewaySaveResult {
        val secret = edit.credential?.let(::SecretInput)
        edit.credential?.fill('\u0000')
        return when (val result = admin.saveGatewayProfile(SaveGatewayRequest(edit.id,
            GatewayCandidateAddresses(edit.addresses.responses, edit.addresses.messages), edit.model, secret, edit.selectedModels))) {
            is AdminResult.Success -> {
                val saved = admin.listGatewayProfiles()
                val agents = (saved as? AdminResult.Success)?.value?.filter { it.ref.id == result.value.ref.id }
                    ?.map { DomainAgent.valueOf(it.agent.name) }?.toSet().orEmpty()
                GatewaySaveResult.Saved(result.value.models.map { GatewayModel(it.id, it.name) }, result.value.catalogError, agents)
            }
            is AdminResult.Failed -> GatewaySaveResult.Failed(result.error.message())
        }
    }
    override suspend fun initialize() = admin.initialize().operation()
    override suspend fun shell(command: String) = diagnostics.executeShell(command).operation()
    override suspend fun stopShell() = diagnostics.stopShell().operation()
    private fun AdminResult<*>.operation(): OperationResult = when (this) { is AdminResult.Success -> OperationResult.Done; is AdminResult.Failed -> OperationResult.Failed(error.message()) }
    private fun CommandResult.operation(): OperationResult = when (this) { CommandResult.Accepted, CommandResult.AlreadyTerminal -> OperationResult.Done; is CommandResult.Rejected -> OperationResult.Failed(error.message()) }
}

internal fun GatewayCheck.message(): String = when (outcome) {
    GatewayCheckOutcome.SUCCEEDED -> AppStrings.minimalProtocolRequestPassedCliToolCallsAndSession
    GatewayCheckOutcome.HTTP_ERROR -> when (httpStatus) {
        401, 403 -> AppStrings.authenticationOrAccessDeniedHttpCheckYourKeyAnd(httpStatus)
        404 -> AppStrings.requestPathOrModelNotFoundHttpCheckAddress
        429 -> AppStrings.rateOrQuotaLimitReachedHttpRetryLaterAnd
        in 300..399 -> AppStrings.gatewayRequestedARedirectHttpCredentialsWereNotForwarded(httpStatus)
        else -> AppStrings.gatewayRejectedTheRequestHttpCheckConfigurationOrService(httpStatus)
    }
    GatewayCheckOutcome.INVALID_RESPONSE -> AppStrings.responseDoesNotMatchTheSelectedProtocolConnectionSuccess
    GatewayCheckOutcome.RESPONSE_TOO_LARGE -> AppStrings.checkResponseExceedsKibSuccessWasNotConfirmed
    GatewayCheckOutcome.DNS_ERROR -> AppStrings.cannotResolveGatewayDomainCheckAddressAndNetwork
    GatewayCheckOutcome.TLS_ERROR -> AppStrings.tlsCertificateOrSecureConnectionValidationFailedCheckGateway
    GatewayCheckOutcome.TIMEOUT -> AppStrings.gatewayConnectionOrReadTimedOutCheckTheNetwork
    GatewayCheckOutcome.CONNECTION_ERROR -> AppStrings.networkRequestFailedCheckAddressNetworkAndGatewayStatus
}
