package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.interaction.domain.ExecutionPhase
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepStatusUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `unfinished step stops announcing progress when its run terminates`() {
        var phase by mutableStateOf(ExecutionPhase.RUNNING)
        compose.setContent { MaterialTheme { StepStatus(null, phase) } }
        compose.onNodeWithContentDescription("步骤进行中").assertExists()
        for (terminal in listOf(ExecutionPhase.CANCELLED, ExecutionPhase.TIMED_OUT, ExecutionPhase.FAILED, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN, ExecutionPhase.SUCCEEDED)) {
            compose.runOnIdle { phase = terminal }
            compose.onNodeWithContentDescription("步骤进行中").assertDoesNotExist()
            compose.onNodeWithContentDescription("步骤完成").assertDoesNotExist()
            compose.onNodeWithContentDescription("步骤结果未确认").assertExists()
        }
    }
    @Test fun `explicit cancelled result is distinct from success even when run succeeds`() {
        var outcome by mutableStateOf("CANCELLED")
        compose.setContent { MaterialTheme { StepStatus(outcome, ExecutionPhase.SUCCEEDED) } }
        compose.onNodeWithContentDescription("步骤已取消").assertExists()
        compose.onNodeWithContentDescription("步骤完成").assertDoesNotExist()
        compose.runOnIdle { outcome = "SUCCEEDED" }
        compose.onNodeWithContentDescription("步骤完成").assertExists()
        compose.onNodeWithContentDescription("步骤已取消").assertDoesNotExist()
    }
}
