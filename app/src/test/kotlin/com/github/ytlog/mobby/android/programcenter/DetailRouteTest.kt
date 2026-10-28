package com.github.ytlog.mobby.android.programcenter

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailRouteTest {
    /** On the tablet, installing returned to the list because the new program had not reached the list yet. */
    @Test fun `a just installed program is waited for instead of treated as gone`() {
        assertEquals(DetailTarget.WAIT, detailTarget("header-watch", loaded = setOf("other"), awaiting = "header-watch"))
        assertEquals(DetailTarget.SHOW, detailTarget("header-watch", loaded = setOf("header-watch"), awaiting = "header-watch"))
    }

    @Test fun `the list still loading is not a missing program`() {
        assertEquals(DetailTarget.WAIT, detailTarget("header-watch", loaded = null, awaiting = null))
    }

    @Test fun `a program missing from a loaded list returns to the list`() {
        assertEquals(DetailTarget.GONE, detailTarget("header-watch", loaded = setOf("other"), awaiting = null))
    }
}
