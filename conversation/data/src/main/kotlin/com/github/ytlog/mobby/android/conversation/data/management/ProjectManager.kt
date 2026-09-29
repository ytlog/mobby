package com.github.ytlog.mobby.android.conversation.data.management

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.conversation.data.ConversationDatabase
import com.github.ytlog.mobby.android.conversation.data.ProjectRow
import com.github.ytlog.mobby.android.conversation.data.domain
import com.github.ytlog.mobby.android.conversation.data.row
import com.github.ytlog.mobby.android.conversation.domain.Conversation
import com.github.ytlog.mobby.android.conversation.domain.ConversationId
import com.github.ytlog.mobby.android.conversation.domain.NextTurnConfig
import com.github.ytlog.mobby.android.conversation.domain.OperationResult
import com.github.ytlog.mobby.android.conversation.domain.Project
import com.github.ytlog.mobby.android.conversation.data.SelectionRow
import com.github.ytlog.mobby.android.conversation.data.storageJson
import com.github.ytlog.mobby.android.localization.AppStrings
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.map

/** A project owns one physical workspace. The default workspace is represented by no project. */
internal class ProjectManager(private val db: ConversationDatabase, private val now: () -> Long, private val newId: () -> String) {
    private val dao = db.dao()
    fun observe() = dao.projects().map { rows -> rows.map { Project(it.name, it.workspace, storageJson.decodeFromString<List<String>>(it.skills).toSet(), it.rules) } }

    suspend fun save(project: Project, createOnly: Boolean): OperationResult = db.withTransaction {
        val existing = dao.project(project.name)
        if (project.name.isBlank() || project.name != project.name.trim() || project.name.length > 80 || project.name.any { it.isISOControl() })
            return@withTransaction OperationResult.Failed(AppStrings.projectNameIsRequiredAndMustNotExceedCharacters)
        if (createOnly && existing != null) return@withTransaction OperationResult.Failed(AppStrings.aProjectWithThisNameExistsChooseAnotherName)
        if (project.workspace.isBlank() || project.workspace == "default") return@withTransaction OperationResult.Failed(AppStrings.workspaceUnavailableSelectAnotherWorkspace)
        if (existing != null && existing.workspace != project.workspace) return@withTransaction OperationResult.Failed(AppStrings.projectWorkspaceCannotChange)
        if (dao.projectByWorkspace(project.workspace)?.name?.let { it != project.name } == true)
            return@withTransaction OperationResult.Failed(AppStrings.projectWorkspaceAlreadyUsed)
        if (project.rules.length > 16_000 || '\u0000' in project.rules) return@withTransaction OperationResult.Failed(AppStrings.projectRulesTooLong)
        dao.save(ProjectRow(project.name, project.workspace, storageJson.encodeToString(project.skills.sorted()), project.rules))
        OperationResult.Done
    }

    suspend fun create(config: NextTurnConfig, project: String): ConversationId = db.withTransaction {
        val workspace = requireNotNull(dao.project(project)) { "Project no longer exists" }.workspace
        val c = Conversation(ConversationId(newId()), config.copy(workspace = workspace), project = project, updatedAt = now())
        dao.save(c.row())
        dao.select(SelectionRow(conversationId = c.id.value))
        c.id
    }

    suspend fun move(id: ConversationId, project: String?): OperationResult = db.withTransaction {
        val workspace = if (project == null) "default" else
            (dao.project(project)?.workspace ?: return@withTransaction OperationResult.Failed(AppStrings.projectUnavailable))
        val c = dao.conversation(id.value)?.domain()
            ?: return@withTransaction OperationResult.Failed(AppStrings.conversationNotFound)
        if (c.project == project && c.config.workspace == workspace) return@withTransaction OperationResult.Done
        if (dao.conversationTurns(id.value).any { it.pending || it.occupied || it.queued })
            return@withTransaction OperationResult.Failed(AppStrings.projectMoveRequiresIdleConversation)
        if (c.draft.attachments.isNotEmpty() || c.draft.pendingAttachment != null)
            return@withTransaction OperationResult.Failed(AppStrings.projectMoveRequiresNoAttachments)
        dao.save(c.copy(project = project, config = c.config.copy(workspace = workspace), session = null,
            sessions = emptyMap(), updatedAt = now()).row())
        OperationResult.Done
    }
}
