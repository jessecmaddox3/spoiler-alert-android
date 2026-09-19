package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class SpoilerFreeScoreTest {
    private fun game(
        league: String = "mls",
        home: Int? = 0,
        away: Int? = 0,
        state: String = "in",
        completed: Boolean = false,
    ) = LeagueGame(
        eventId = "1", leagueId = league, name = "A at B", shortName = "A @ B",
        startMillis = 1L, completed = completed, homeEspnId = "1", awayEspnId = "2",
        label = null, statusState = state, homeScore = home, awayScore = away,
    )

    @Test fun `reports whether anyone has scored without exposing the score`() {
        assertEquals(ScoreAnswer.NO, SpoilerFreeScore.answer(game(), ScoreQuestion.ANY_SCORE))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game(home = 1), ScoreQuestion.ANY_SCORE))
    }

    @Test fun `reports whether the game has started`() {
        assertEquals(ScoreAnswer.NO, SpoilerFreeScore.answer(game(state = "pre"), ScoreQuestion.STARTED))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game(state = "in"), ScoreQuestion.STARTED))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game(state = "post"), ScoreQuestion.STARTED))
    }

    @Test fun `formats clock and period without exposing score or leader`() {
        assertEquals(
            "15:34 left in the 3rd quarter.",
            SpoilerFreeScore.progress(
                ScoreSnapshot(
                    leagueId = "nfl", statusState = "in", completed = false,
                    homeScore = 14, awayScore = 7, displayClock = "15:34", period = 3,
                )
            ),
        )
        assertEquals(
            "Second half, 63rd minute.",
            SpoilerFreeScore.progress(
                ScoreSnapshot(
                    leagueId = "soccer", statusState = "in", completed = false,
                    homeScore = 2, awayScore = 0, displayClock = "63:12", period = 2,
                )
            ),
        )
    }

    @Test fun `formats safe pregame halftime baseball and final states`() {
        assertEquals("Not started yet.", SpoilerFreeScore.progress(
            ScoreSnapshot("mls", "pre", false, null, null)
        ))
        assertEquals("Halftime.", SpoilerFreeScore.progress(
            ScoreSnapshot("soccer", "in", false, 1, 0, statusName = "STATUS_HALFTIME")
        ))
        assertEquals("Bottom of the 5th inning.", SpoilerFreeScore.progress(
            ScoreSnapshot("mlb", "in", false, 8, 2, period = 5, statusDetail = "Bottom 5th")
        ))
        assertEquals("The game is over.", SpoilerFreeScore.progress(
            ScoreSnapshot("nba", "post", true, 100, 80)
        ))
    }

    @Test fun `progress rejects unsafe or missing detail instead of leaking raw status text`() {
        assertEquals(null, SpoilerFreeScore.progress(
            ScoreSnapshot("mlb", "in", false, 8, 2, period = 5, statusDetail = "ATL leads 8-2")
        ))
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeScore.answer(
            ScoreSnapshot("mlb", "in", false, 8, 2, period = 5, statusDetail = "ATL leads 8-2"),
            ScoreQuestion.PROGRESS,
        ))
    }

    @Test fun `zero zero and equal nonzero games are tied`() {
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game(), ScoreQuestion.TIED))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game(home = 2, away = 2), ScoreQuestion.TIED))
        assertEquals(ScoreAnswer.NO, SpoilerFreeScore.answer(game(home = 2, away = 1), ScoreQuestion.TIED))
    }

    @Test fun `blowout thresholds are sport specific`() {
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game("mls", 3, 0), ScoreQuestion.BLOWOUT))
        assertEquals(ScoreAnswer.NO, SpoilerFreeScore.answer(game("mls", 2, 0), ScoreQuestion.BLOWOUT))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game("nfl", 24, 7), ScoreQuestion.BLOWOUT))
        assertEquals(ScoreAnswer.NO, SpoilerFreeScore.answer(game("nfl", 23, 7), ScoreQuestion.BLOWOUT))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game("nba", 100, 80), ScoreQuestion.BLOWOUT))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game("mlb", 8, 2), ScoreQuestion.BLOWOUT))
    }

    @Test fun `close thresholds are sport specific and do not expose the score`() {
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game("nfl", 24, 17), ScoreQuestion.CLOSE))
        assertEquals(ScoreAnswer.NO, SpoilerFreeScore.answer(game("nfl", 24, 14), ScoreQuestion.CLOSE))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game("mls", 2, 1), ScoreQuestion.CLOSE))
    }

    @Test fun `golf reducer answers only coarse leaderboard questions`() {
        val golf = ScoreSnapshot(
            leagueId = "golf", statusState = "in", completed = false,
            homeScore = null, awayScore = null, currentRound = 4,
            leadersStarted = true, leaderHolesCompleted = 12,
            tiedForLead = false, contenderWithinTwo = true, playoff = false,
            weatherDelay = false,
        )
        assertEquals("Round 4.", SpoilerFreeScore.detail(golf, ScoreQuestion.CURRENT_ROUND))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(golf, ScoreQuestion.BACK_NINE))
        assertEquals(ScoreAnswer.NO, SpoilerFreeScore.answer(golf, ScoreQuestion.TIE_FOR_LEAD))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(golf, ScoreQuestion.WITHIN_TWO))
    }

    @Test fun `over uses completed or post state`() {
        assertEquals(ScoreAnswer.NO, SpoilerFreeScore.answer(game(), ScoreQuestion.OVER))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game(completed = true), ScoreQuestion.OVER))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(game(state = "post"), ScoreQuestion.OVER))
    }

    @Test fun `score questions are unavailable before kickoff or when scores are absent`() {
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeScore.answer(game(state = "pre"), ScoreQuestion.TIED))
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeScore.answer(game(home = null), ScoreQuestion.ANY_SCORE))
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeScore.answer(game("unknown", 9, 0), ScoreQuestion.BLOWOUT))
    }
}
