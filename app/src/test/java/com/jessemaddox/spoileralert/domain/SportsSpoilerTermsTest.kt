package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SportsSpoilerTermsTest {
    private val falcons = ArmedShield(
        id = 1, name = "Atlanta Falcons",
        aliases = listOf(Alias("falcons")) + SportsSpoilerTerms.aliases,
    )
    private val shields = listOf(falcons)

    @Test fun `new non-short phrase matches a sports shield in both modes`() {
        assertEquals(falcons, Matcher.match("What a finish!!", shields, MatchMode.AGGRESSIVE))
        assertEquals(falcons, Matcher.match("What a finish!!", shields, MatchMode.STRICT))
        assertEquals(falcons, Matcher.match("they sealed the win late", shields, MatchMode.STRICT))
    }

    @Test fun `short phrase needs sports context in strict mode only`() {
        assertEquals(falcons, Matcher.match("game over", shields, MatchMode.AGGRESSIVE))
        assertNull(Matcher.match("game over, I'm going to bed", shields, MatchMode.STRICT))
        assertEquals(falcons, Matcher.match("game over — overtime", shields, MatchMode.STRICT))
    }

    @Test fun `existing terms stay non-short and short terms are flagged`() {
        val byText = SportsSpoilerTerms.aliases.associateBy { it.text }
        val existingNonShort = setOf(
            "scoreless", "goalless", "nil-nil", "nil nil", "no goals yet", "clean sheet",
            "shutout", "blowout", "equalizer", "equaliser", "hat trick", "penalty shootout",
            "final whistle", "final score", "halftime score", "game winner", "game-winning",
            "match winner", "walk-off", "walk off", "took the lead", "takes the lead",
        )
        for (t in existingNonShort) assertFalse("'$t' must stay non-short", byText.getValue(t).short)
        for (t in setOf("game over", "swept", "tough loss", "big win", "huge win", "great game", "got robbed")) {
            assertEquals("'$t' must be short-flagged", true, byText.getValue(t).short)
        }
    }
}
