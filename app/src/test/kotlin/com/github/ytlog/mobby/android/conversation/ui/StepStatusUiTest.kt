package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.ExecutionPhase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepStatusUiTest {
    @Test fun `unfinished step stops announcing progress when its run terminates`() {
        assertEquals("步骤进行中", stepStatusLabel(null, ExecutionPhase.RUNNING))
        for (terminal in listOf(ExecutionPhase.CANCELLED, ExecutionPhase.TIMED_OUT, ExecutionPhase.FAILED, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN, ExecutionPhase.SUCCEEDED))
            assertEquals("步骤结果未确认", stepStatusLabel(null, terminal))
    }
    @Test fun `explicit cancelled result is distinct from success even when run succeeds`() {
        assertEquals("步骤已取消", stepStatusLabel("CANCELLED", ExecutionPhase.SUCCEEDED))
        assertEquals("步骤完成", stepStatusLabel("SUCCEEDED", ExecutionPhase.SUCCEEDED))
    }
}
