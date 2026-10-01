package com.github.ytlog.mobby.android.conversation.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PetResizeTest {
    @Test fun persistedBubbleKeepsRelativePositionAcrossFoldAndUnfold() {
        val session = PetSession()
        session.restorePosition(744, 500, 800, 1000)
        assertEquals(344, session.place(400, 800, 56, 252, 100).x)
        assertEquals(744, session.place(800, 1000, 56, 252, 100).x)
    }

    @Test fun oldPositionWithoutSavedDisplaySizeStillFits() {
        val session = PetSession()
        session.restorePosition(700, 900, null, null)
        val frame = session.place(400, 700, 56, 252, 100)
        assertEquals(344, frame.x)
        assertEquals(644, frame.y)
    }
}
