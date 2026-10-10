package com.jessemaddox.spoileralert.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TileDecisionTest {

    private fun candidate(id: Long, name: String = "shield-$id", league: String? = "nfl") =
        TileDecision.Candidate(shieldId = id, name = name, leagueId = league, eventId = "fiction-game-$id")

    private fun decide(candidates: List<TileDecision.Candidate>) = TileDecision.decide(candidates)

    @Test fun `no candidates opens the app`() {
        assertEquals(TileDecision.Action.OpenApp, decide(emptyList()))
    }

    @Test fun `exactly one candidate protects its next game on the game's own clock`() {
        // The repository decides when hiding starts (game time) and ends (expected finish).
        assertEquals(TileDecision.Action.Protect(1L, "fiction-game-1"), decide(listOf(candidate(1L))))
    }

    @Test fun `two or more candidates is ambiguous and opens the app regardless of league`() {
        assertEquals(
            TileDecision.Action.OpenApp,
            decide(listOf(candidate(1L), candidate(2L))),
        )
    }

    @Test fun `three candidates is still just ambiguous, not a crash`() {
        assertEquals(
            TileDecision.Action.OpenApp,
            decide(listOf(candidate(1L), candidate(2L), candidate(3L))),
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
