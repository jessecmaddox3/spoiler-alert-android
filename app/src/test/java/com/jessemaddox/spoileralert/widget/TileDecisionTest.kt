package com.jessemaddox.spoileralert.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TileDecisionTest {

    private val now = 1_700_000_000_000L

    private fun candidate(
        id: Long,
        name: String = "shield-$id",
        league: String? = "nfl",
        start: Long? = null,
    ) = TileDecision.Candidate(shieldId = id, name = name, leagueId = league, gameStartMillis = start)

    private fun decide(candidates: List<TileDecision.Candidate>) = TileDecision.decide(candidates, now)

    @Test fun `no candidates opens the app`() {
        assertEquals(TileDecision.Action.OpenApp, decide(emptyList()))
    }

    @Test fun `exactly one candidate arms it for the sport-suggested duration`() {
        val action = decide(listOf(candidate(1L, league = "nfl")))
        assertEquals(TileDecision.Action.Arm(1L, 5), action) // SessionDurations.suggestedHours("nfl") == 5
    }

    @Test fun `an evening game armed from the tile in the morning is covered through its finish`() {
        // nfl kicking off in 8h: 8h + 3.5h + 45m = 12h15m → ceil 13, not the flat 5
        val action = decide(listOf(candidate(1L, league = "nfl", start = now + 8 * 3_600_000L)))
        assertEquals(TileDecision.Action.Arm(1L, 13), action)
    }

    @Test fun `unmapped league falls back to the default suggested duration`() {
        val action = decide(listOf(candidate(1L, league = null)))
        assertEquals(TileDecision.Action.Arm(1L, 4), action) // SessionDurations.DEFAULT_HOURS
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
