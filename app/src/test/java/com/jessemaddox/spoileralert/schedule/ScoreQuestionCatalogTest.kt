package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreQuestionCatalogTest {
    @Test fun `football gets one-possession question while soccer gets one-goal question`() {
        val football = ScoreQuestionCatalog.forLeague("nfl")
        val soccer = ScoreQuestionCatalog.forLeague("mls")
        assertTrue(football.any { it.question == ScoreQuestion.CLOSE && "possession" in it.label })
        assertTrue(soccer.any { it.question == ScoreQuestion.CLOSE && "goal" in it.label })
    }

    @Test fun `golf questions avoid team score concepts`() {
        val golf = ScoreQuestionCatalog.forLeague("golf")
        assertTrue(golf.any { it.question == ScoreQuestion.CURRENT_ROUND })
        assertTrue(golf.any { it.question == ScoreQuestion.BACK_NINE })
        assertTrue(golf.any { it.question == ScoreQuestion.WITHIN_TWO })
        assertFalse(golf.any { it.question == ScoreQuestion.ANY_SCORE })
    }

    @Test fun `formula one exposes only trustworthy start and finish`() {
        assertEquals(
            listOf(ScoreQuestion.STARTED, ScoreQuestion.OVER),
            ScoreQuestionCatalog.forLeague("f1").map { it.question },
        )
    }
}
