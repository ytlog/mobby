package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

internal enum class AppPage {
    CONVERSATION, PROJECTS, PROJECT_DETAIL, SETTINGS, GATEWAY,
    HISTORY_LIMITS, DIAGNOSTIC, LICENSES, ARCHIVED, UPDATES, SKILLS, PLUGINS, APP_FUNCTIONS;

    companion object {
        fun fromSettingsRoute(route: String): AppPage = when (route) {
            "gateway" -> GATEWAY
            "history-limits" -> HISTORY_LIMITS
            "diagnostic" -> DIAGNOSTIC
            "archived" -> ARCHIVED
            "licenses" -> LICENSES
            "updates" -> UPDATES
            else -> error("Unknown settings destination: $route")
        }
    }
}

/** Page and overlay destinations share one saved owner; window size never mutates them. */
internal data class AppNavigationState(
    val page: AppPage = AppPage.CONVERSATION,
    val projectsBackPage: AppPage = AppPage.CONVERSATION,
    val projectName: String? = null,
    val addSheetOpen: Boolean = false,
) {
    fun open(next: AppPage): AppNavigationState = copy(page = next, addSheetOpen = false)
    fun openAddSheet(): AppNavigationState = copy(page = AppPage.CONVERSATION, addSheetOpen = true)
    fun closeAddSheet(): AppNavigationState = copy(addSheetOpen = false)
    fun openProjects(from: AppPage): AppNavigationState = copy(page = AppPage.PROJECTS, projectsBackPage = from, addSheetOpen = false)
    fun openProject(name: String): AppNavigationState = copy(page = AppPage.PROJECT_DETAIL, projectName = name, addSheetOpen = false)

    fun back(gatewayFromIntro: Boolean): AppNavigationState = when {
        addSheetOpen -> closeAddSheet()
        page == AppPage.GATEWAY -> open(if (gatewayFromIntro) AppPage.CONVERSATION else AppPage.SETTINGS)
        page in setOf(AppPage.HISTORY_LIMITS, AppPage.DIAGNOSTIC, AppPage.ARCHIVED, AppPage.LICENSES, AppPage.UPDATES) -> open(AppPage.SETTINGS)
        page in setOf(AppPage.SKILLS, AppPage.PLUGINS, AppPage.APP_FUNCTIONS) -> openAddSheet()
        page == AppPage.PROJECTS -> open(projectsBackPage)
        else -> open(AppPage.CONVERSATION)
    }

    companion object {
        val Saver: Saver<AppNavigationState, Any> = listSaver(
            save = { listOf(it.page.name, it.projectsBackPage.name, it.projectName.orEmpty(), it.addSheetOpen.toString()) },
            restore = { AppNavigationState(AppPage.valueOf(it[0]), AppPage.valueOf(it[1]), it[2].ifEmpty { null }, it[3].toBoolean()) },
        )
    }
}
