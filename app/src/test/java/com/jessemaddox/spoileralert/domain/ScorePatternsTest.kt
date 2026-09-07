package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorePatternsTest {
    private fun agg(t: String) = ScorePatterns.matches(t, MatchMode.AGGRESSIVE)
    private fun strict(t: String) = ScorePatterns.matches(t, MatchMode.STRICT)

    @Test fun `off mode never matches`() {
        assertFalse(ScorePatterns.matches("final 24-17", MatchMode.OFF))
        assertFalse(ScorePatterns.matches("up by 7", MatchMode.OFF))
    }

    @Test fun `tier A margin-by matches both modes`() {
        for (t in listOf(
            "up by 7", "down by 3", "won by three", "lost by fourteen",
            "leads by 10", "trailing by 2", "beat by 21", "ahead by twenty",
        )) {
            assertTrue("agg '$t'", agg(t))
            assertTrue("strict '$t'", strict(t))
        }
    }

    @Test fun `tier A context pair matches both modes`() {
        for (t in listOf(
            "final 24-17", "Final: 24-17", "halftime 10-7", "ht 3-0",
            "won 24-17!", "score 21-14", "leads 2-1",
        )) {
            assertTrue("agg '$t'", agg(t))
            assertTrue("strict '$t'", strict(t))
        }
    }

    @Test fun `tier A trailing final matches both modes`() {
        for (t in listOf("21-14 final", "3-0 ft")) {
            assertTrue("agg '$t'", agg(t))
            assertTrue("strict '$t'", strict(t))
        }
    }

    @Test fun `tier B bare patterns match aggressive only`() {
        for (t in listOf("up 7", "down 10", "21-14", "12-25")) {
            assertTrue("agg '$t'", agg(t))
            assertFalse("strict '$t'", strict(t))
        }
    }

    @Test fun `down N lbs is excluded from bare margin`() {
        assertFalse(agg("down 10 lbs"))
        assertFalse(agg("down 5 kg"))
        assertTrue(agg("down 10"))
    }

    @Test fun `times dates and references never match either mode`() {
        for (t in listOf("see you at 7:30", "7/22 works", "John 3:16", "meet at 8")) {
            assertFalse("agg '$t'", agg(t))
            assertFalse("strict '$t'", strict(t))
        }
    }

    @Test fun `three digit scores are a documented gap`() {
        assertFalse(agg("118-110"))
        assertFalse(strict("118-110"))
    }
}
