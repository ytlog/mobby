package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun mediumWindowStaysSingle() {
        assertEquals(ConversationLayout.Single(0.dp, 0.dp, 839.dp, 900.dp), conversationLayout(839.dp, 900.dp, null))
    }

    @Test fun expandedWindowShowsListAndDetail() {
        assertEquals(ConversationLayout.Dual(0.dp, 320.dp, 336.dp, 504.dp, 0.dp, 900.dp), conversationLayout(840.dp, 900.dp, null))
    }

    @Test fun shortExpandedWindowStaysSingle() {
        assertTrue(conversationLayout(900.dp, 400.dp, null) is ConversationLayout.Single)
    }

    @Test fun featureOutsideTheAppWindowDoesNotSuppressWideLayout() {
        assertEquals(
            ConversationLayout.Dual(0.dp, 320.dp, 336.dp, 664.dp, 0.dp, 800.dp),
            conversationLayout(1000.dp, 800.dp, FoldRegion(1100.dp, 0.dp, 1120.dp, 800.dp, vertical = true)),
        )
    }

    @Test fun separatingHingeKeepsBothPanesOffTheHinge() {
        assertEquals(
            ConversationLayout.Dual(0.dp, 432.dp, 480.dp, 520.dp, 0.dp, 800.dp),
            conversationLayout(1000.dp, 800.dp, FoldRegion(440.dp, 0.dp, 472.dp, 800.dp, vertical = true)),
        )
    }

    @Test fun narrowHingeSegmentUsesOneSide() {
        assertEquals(
            ConversationLayout.Single(418.dp, 0.dp, 382.dp, 700.dp),
            conversationLayout(800.dp, 700.dp, FoldRegion(380.dp, 0.dp, 410.dp, 700.dp, vertical = true)),
        )
    }

    @Test fun horizontalHingeUsesOneUnobstructedRegion() {
        assertEquals(
            ConversationLayout.Single(0.dp, 428.dp, 700.dp, 372.dp),
            conversationLayout(700.dp, 800.dp, FoldRegion(0.dp, 350.dp, 700.dp, 420.dp, vertical = false)),
        )
    }
}
