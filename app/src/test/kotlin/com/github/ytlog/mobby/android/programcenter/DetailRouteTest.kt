package com.github.ytlog.mobby.android.programcenter

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailRouteTest {
    @Test fun `the list still loading is not a missing program`() {
        assertEquals(DetailTarget.WAIT, detailTarget("header-watch", loaded = null))
    }

    @Test fun `a loaded program is shown`() {
        assertEquals(DetailTarget.SHOW, detailTarget("header-watch", loaded = setOf("header-watch")))
    }

    @Test fun `a program missing from a loaded list returns to the list`() {
        assertEquals(DetailTarget.GONE, detailTarget("header-watch", loaded = setOf("other")))
    }
}
