package com.github.ytlog.mobby.android.conversation.data

import com.github.ytlog.mobby.android.conversation.domain.Failure
import com.github.ytlog.mobby.android.runtime.api.ErrorCode
import com.github.ytlog.mobby.android.runtime.api.RuntimeError
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeErrorMappingTest {
    @Test fun `permission denial is not reported as invalid gateway configuration`() {
        assertEquals(Failure.PERMISSION_DENIED, RuntimeError(ErrorCode.PERMISSION_DENIED).failure())
    }
}
