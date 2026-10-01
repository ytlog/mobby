package com.github.ytlog.mobby.android.conversation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationStateTest {
    @Test fun sheetIsAnOverlayAndItsChildPageReturnsToIt() {
        val sheet = AppNavigationState().openAddSheet()
        assertEquals(AppPage.CONVERSATION, sheet.page)
        assertTrue(sheet.addSheetOpen)
        val plugin = sheet.open(AppPage.PLUGINS)
        assertFalse(plugin.addSheetOpen)
        assertEquals(sheet, plugin.back(gatewayFromIntro = false))
        assertEquals(AppNavigationState(), sheet.back(gatewayFromIntro = false))
    }

    @Test fun projectsAndGatewayKeepTheirActualReturnTargets() {
        val detail = AppNavigationState().openProject("workspace")
        val returned = detail.openProjects(AppPage.PROJECT_DETAIL).back(gatewayFromIntro = false)
        assertEquals(AppPage.PROJECT_DETAIL, returned.page)
        assertEquals("workspace", returned.projectName)
        assertEquals(AppPage.SETTINGS, AppNavigationState().open(AppPage.GATEWAY).back(gatewayFromIntro = false).page)
        assertEquals(AppPage.CONVERSATION, AppNavigationState().open(AppPage.GATEWAY).back(gatewayFromIntro = true).page)
    }

    @Test fun settingsRouteBoundaryRejectsUnknownDestination() {
        assertEquals(AppPage.DIAGNOSTIC, AppPage.fromSettingsRoute("diagnostic"))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { AppPage.fromSettingsRoute("typo") }
    }
}
