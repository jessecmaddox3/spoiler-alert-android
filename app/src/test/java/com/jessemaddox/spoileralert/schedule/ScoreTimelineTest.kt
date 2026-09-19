package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreTimelineTest {
    @Test fun `skip is safe only when no scoring event occurs inside the selected stretch`() {
        val timeline = basketballTimeline()
        val checkpoints = SpoilerFreeTimeline.checkpoints(timeline)
        val halftime = checkpoints.first { it.label == "Halftime" }
        val fiveLeftThird = checkpoints.first { it.label == "5:00 left in the 3rd quarter" }
        val startFourth = checkpoints.first { it.label == "Start of the 4th quarter" }

        assertEquals(
            SkipAnswer.DO_NOT_SKIP,
            SpoilerFreeTimeline.canSkip(timeline, halftime, fiveLeftThird),
        )
        assertEquals(
            SkipAnswer.SAFE,
            SpoilerFreeTimeline.canSkip(timeline, fiveLeftThird, startFourth),
        )
    }

    @Test fun `a score exactly at the starting point is not counted but one at the target is`() {
        val timeline = basketballTimeline()
        val points = listOf(
            ScoreCheckpoint("start", "Start", 1_500),
            ScoreCheckpoint("target", "Target", 1_700),
        )

        assertEquals(SkipAnswer.DO_NOT_SKIP, SpoilerFreeTimeline.canSkip(timeline, points[0], points[1]))
        assertEquals(SkipAnswer.UNAVAILABLE, SpoilerFreeTimeline.canSkip(timeline, points[1], points[0]))
    }

    @Test fun `historical questions use only the state at or before the checkpoint`() {
        val timeline = basketballTimeline()
        val checkpoints = SpoilerFreeTimeline.checkpoints(timeline)
        val halftime = checkpoints.first { it.label == "Halftime" }
        val fiveLeftThird = checkpoints.first { it.label == "5:00 left in the 3rd quarter" }

        assertEquals(
            ScoreAnswer.NO,
            SpoilerFreeTimeline.answerAt(timeline, halftime, ScoreQuestion.ANY_SCORE),
        )
        assertEquals(
            ScoreAnswer.YES,
            SpoilerFreeTimeline.answerAt(timeline, fiveLeftThird, ScoreQuestion.ANY_SCORE),
        )
    }

    @Test fun `checkpoint catalog is neutral and capped at the latest known game point`() {
        val live = basketballTimeline().copy(
            completed = false,
            points = basketballTimeline().points.take(4) +
                ScoreTimelinePoint(1_900, 3, 260, null, 4, 0, false),
        )
        val checkpoints = SpoilerFreeTimeline.checkpoints(live)

        assertTrue(checkpoints.any { it.label == "Halftime" })
        assertTrue(checkpoints.any { it.label == "5:00 left in the 3rd quarter" })
        assertFalse(checkpoints.any { it.label == "Start of the 4th quarter" })
        assertTrue(checkpoints.last().label.startsWith("Latest checked point"))
        assertTrue(checkpoints.all { labelIsSpoilerFree(it.label) })
    }

    @Test fun `baseball checkpoints use inning halves and support scoring checks`() {
        val timeline = ScoreTimeline(
            leagueId = "mlb",
            completed = false,
            complete = true,
            points = listOf(
                ScoreTimelinePoint(0, 1, null, InningHalf.TOP, 0, 0, false),
                ScoreTimelinePoint(10, 1, null, InningHalf.TOP, 1, 0, true),
                ScoreTimelinePoint(1_000_000, 1, null, InningHalf.BOTTOM, 1, 0, false),
                ScoreTimelinePoint(2_000_000, 2, null, InningHalf.TOP, 1, 0, false),
            ),
        )
        val checkpoints = SpoilerFreeTimeline.checkpoints(timeline)
        val topFirst = checkpoints.first { it.label == "Start of the top of the 1st" }
        val bottomFirst = checkpoints.first { it.label == "Start of the bottom of the 1st" }

        assertEquals(SkipAnswer.DO_NOT_SKIP, SpoilerFreeTimeline.canSkip(timeline, topFirst, bottomFirst))
    }

    private fun basketballTimeline() = ScoreTimeline(
        leagueId = "nba",
        completed = true,
        complete = true,
        points = listOf(
            ScoreTimelinePoint(0, 1, 720, null, 0, 0, false),
            ScoreTimelinePoint(1_440, 3, 720, null, 0, 0, false),
            ScoreTimelinePoint(1_500, 3, 660, null, 2, 0, true),
            ScoreTimelinePoint(1_700, 3, 460, null, 4, 0, true),
            ScoreTimelinePoint(2_160, 4, 720, null, 4, 0, false),
            ScoreTimelinePoint(2_880, 4, 0, null, 10, 8, true),
        ),
    )

    private fun labelIsSpoilerFree(label: String): Boolean =
        listOf("score", "lead", "win", "home", "away").none { it in label.lowercase() }
}
