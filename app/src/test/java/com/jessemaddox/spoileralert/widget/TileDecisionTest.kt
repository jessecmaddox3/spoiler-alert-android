package com.jessemaddox.spoileralert.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TileDecisionTest {

    private fun candidate(id: Long, name: String = "shield-$id", league: String? = "nfl") =
        TileDecision.Candidate(shieldId = id, name = name, leagueId = league)

    @Test fun `no candidates opens the app`() {
        assertEquals(TileDecision.Action.OpenApp, TileDecision.decide(emptyList()))
    }

    @Test fun `exactly one candidate arms it for the sport-suggested duration`() {
        val action = TileDecision.decide(listOf(candidate(1L, league = "nfl")))
        assertEquals(TileDecision.Action.Arm(1L, 5), action) // SessionDurations.suggestedHours("nfl") == 5
    }

    @Test fun `unmapped league falls back to the default suggested duration`() {
        val action = TileDecision.decide(listOf(candidate(1L, league = null)))
        assertEquals(TileDecision.Action.Arm(1L, 4), action) // SessionDurations.DEFAULT_HOURS
    }

    @Test fun `two or more candidates is ambiguous and opens the app regardless of league`() {
        assertEquals(
            TileDecision.Action.OpenApp,
            TileDecision.decide(listOf(candidate(1L), candidate(2L))),
        )
    }

    @Test fun `three candidates is still just ambiguous, not a crash`() {
        assertEquals(
            TileDecision.Action.OpenApp,
            TileDecision.decide(listOf(candidate(1L), candidate(2L), candidate(3L))),
        )
    }

    @Test fun `subtitle names the single candidate`() {
        assertEquals("shield-1", TileDecision.subtitle(listOf(candidate(1L, name = "shield-1"))))
    }

    @Test fun `subtitle falls back to the app name when ambiguous`() {
        assertEquals("Spoiler Alert", TileDecision.subtitle(listOf(candidate(1L), candidate(2L))))
    }

    @Test fun `subtitle falls back to the app name when nothing is schedulable`() {
        assertEquals("Spoiler Alert", TileDecision.subtitle(emptyList()))
    }
}
