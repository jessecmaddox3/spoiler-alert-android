package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.*
import org.junit.Test

/** Constructed examples only. A known quiet interval is a positive control for every boundary. */
class TimelineSafetyTest {
    private fun at(position: Int) = ScoreCheckpoint("p$position", "Checked point", position)
    private fun point(position: Int, home: Int? = 0, away: Int? = 0, scoring: Boolean = false) =
        ScoreTimelinePoint(position, 1, 900 - position, null, home, away, scoring)
    private fun timeline(vararg points: ScoreTimelinePoint) = ScoreTimeline("nfl", false, points.toList(), true)

    private fun unavailable(value: ScoreTimeline?) {
        if (value == null) return
        assertTrue(SpoilerFreeTimeline.checkpoints(value).isEmpty())
        assertEquals(SkipAnswer.UNAVAILABLE, SpoilerFreeTimeline.canSkip(value, at(0), at(60)))
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeTimeline.answerAt(value, at(60), ScoreQuestion.ANY_SCORE))
    }

    @Test fun `known zero scores support quiet intervals and historical answers`() {
        val value = timeline(point(0), point(60))
        assertEquals(SkipAnswer.SAFE, SpoilerFreeTimeline.canSkip(value, at(0), at(60)))
        assertEquals(ScoreAnswer.NO, SpoilerFreeTimeline.answerAt(value, at(0), ScoreQuestion.ANY_SCORE))
        assertEquals(ScoreAnswer.YES, SpoilerFreeTimeline.answerAt(value, at(60), ScoreQuestion.TIED))
        assertEquals(SkipAnswer.SAFE, SpoilerFreeTimeline.canSkip(EspnTimeline.parse(football(), "nfl")!!, at(0), at(60)))
    }

    @Test fun `explicit completeness cannot make missing or contradictory scores trustworthy`() {
        for (bad in listOf(point(0, null), point(0, away = null), point(0, -1), point(0, away = -1))) {
            unavailable(timeline(bad, point(60)))
        }
        unavailable(timeline(point(0), point(60, null)))
        unavailable(timeline(point(0), point(60, away = null)))
        unavailable(timeline(point(0, 1, 0), point(60, 0, 1)))
        unavailable(timeline(point(0), point(60, 1)))
    }

    @Test fun `coverage begins at the first actual point and never invents a zero baseline`() {
        val value = timeline(point(60), point(120))
        assertEquals(SkipAnswer.UNAVAILABLE, SpoilerFreeTimeline.canSkip(value, at(0), at(120)))
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeTimeline.answerAt(value, at(0), ScoreQuestion.ANY_SCORE))
        assertEquals(SkipAnswer.SAFE, SpoilerFreeTimeline.canSkip(value, at(60), at(120)))
        assertEquals(SkipAnswer.UNAVAILABLE, SpoilerFreeTimeline.canSkip(value, at(60), at(121)))
        assertEquals(SkipAnswer.UNAVAILABLE, SpoilerFreeTimeline.canSkip(value, at(60), at(60)))
        assertEquals(SkipAnswer.UNAVAILABLE, SpoilerFreeTimeline.canSkip(value, at(120), at(60)))
        assertTrue(SpoilerFreeTimeline.checkpoints(value).all { it.position in 60..120 })
    }

    @Test fun `scoring at the end counts and scoring at the start does not`() {
        val value = timeline(point(0), point(60, 7, scoring = true), point(120, 7))
        assertEquals(SkipAnswer.DO_NOT_SKIP, SpoilerFreeTimeline.canSkip(value, at(0), at(60)))
        assertEquals(SkipAnswer.SAFE, SpoilerFreeTimeline.canSkip(value, at(60), at(120)))
        assertEquals(ScoreAnswer.YES, SpoilerFreeTimeline.answerAt(value, at(60), ScoreQuestion.ANY_SCORE))
    }

    @Test fun `malformed collection members cannot disappear from coverage proof`() {
        for (bad in listOf("null", "\"unreadable\"", "[]", "7")) {
            unavailable(EspnTimeline.parse(football(plays = "${play("a", "15:00")},$bad,${play("b", "14:00")}"), "nfl"))
            unavailable(EspnTimeline.parse(football().replace("\"previous\":[", "\"previous\":[$bad,"), "nfl"))
            unavailable(EspnTimeline.parse(football().replace("\"drives\":{", "\"drives\":{\"current\":$bad,"), "nfl"))
            unavailable(EspnTimeline.parse(soccer().replace("\"keyEvents\":[", "\"keyEvents\":[$bad,"), "mls"))
        }
    }

    @Test fun `unambiguous competitor scores are required`() {
        for (bad in listOf("null", "{}", "[]")) {
            unavailable(EspnTimeline.parse(football().replace("\"competitors\":[", "\"competitors\":[$bad,"), "nfl"))
        }
        unavailable(EspnTimeline.parse(football().replace("\"score\":\"0\"", "\"score\":null"), "nfl"))
    }

    @Test fun `identical duplicate plays collapse but conflicting revisions do not`() {
        val same = play("b", "14:00")
        val value = EspnTimeline.parse(football(plays = "${play("a", "15:00")},$same,$same"), "nfl")!!
        assertEquals(SkipAnswer.SAFE, SpoilerFreeTimeline.canSkip(value, at(0), at(60)))
        unavailable(EspnTimeline.parse(football(plays = "${play("a", "15:00")},$same,${play("b", "13:00")}"), "nfl"))
    }

    @Test fun `malformed clocks and unsupported overtime never claim quiet play`() {
        for (clock in listOf("1:99", "NaN", "Infinity", "9999999999999")) {
            unavailable(EspnTimeline.parse(football(plays = "${play("a", "15:00")},${play("b", clock)}"), "nfl"))
        }
        for (period in listOf(0, -1, 5, Int.MAX_VALUE)) {
            unavailable(EspnTimeline.parse(football().replace("\"number\":1", "\"number\":$period"), "nfl"))
        }
    }

    @Test fun `verified soccer zero history supports viewing cues from kickoff`() {
        val value = EspnTimeline.parse(soccer(), "mls")!!
        assertEquals(SkipAnswer.SAFE, SpoilerFreeTimeline.canSkip(value, at(0), at(1800)))
        assertEquals(ScoreAnswer.NO, SpoilerFreeTimeline.answerAt(value, at(0), ScoreQuestion.ANY_SCORE))
        assertTrue(SoccerGoalGuide.answer(value, CatchUpRequest(CatchUpQuestion.GOAL_TIMES)).startsWith("No goals recorded"))
    }

    @Test fun `goal guide validates history and cannot trust a malformed explicit timeline`() {
        val value = EspnTimeline.parse(soccer(), "mls")!!
        val invalid = value.copy(points = value.points.map { it.copy(homeScore = null) }, complete = true)
        assertTrue(SoccerGoalGuide.answer(invalid, CatchUpRequest(CatchUpQuestion.GOAL_TIMES)).startsWith("I can't verify"))
        assertTrue(SoccerGoalGuide.answer(value.copy(points = value.points.reversed()),
            CatchUpRequest(CatchUpQuestion.GOAL_TIMES)).contains("30′"))
    }

    @Test fun `conflicting soccer final marker is not an identical duplicate`() {
        val marker = """{"id":"end","period":{"number":2},"clock":{"displayValue":"90:00"},"type":{"type":"end-regular-time"},"scoringPlay":false}"""
        val body = soccer(events = "$marker,${marker.replace("end-regular-time", "ordinary-event")}", completed = true)
        unavailable(EspnTimeline.parse(body, "mls"))
    }

    @Test fun `blank event ids do not merge distinct goals in the same minute`() {
        fun goal(second: Int) = """{"id":" ","period":{"number":1},"clock":{"displayValue":"10:$second"},"scoringPlay":true,"team":{"id":"home"}}"""
        val body = soccer(events = "${goal(10)},${goal(20)}", home = 2)
        val value = EspnTimeline.parse(body, "mls")!!
        assertEquals(2, value.points.count { it.scoring })
        assertEquals(ScoreAnswer.NO, SpoilerFreeTimeline.answerAt(value, at(0), ScoreQuestion.ANY_SCORE))
        assertEquals(SkipAnswer.DO_NOT_SKIP, SpoilerFreeTimeline.canSkip(value, at(0), at(1800)))
    }

    @Test fun `fractional clocks keep goals after the integer starting boundary`() {
        for ((league, clocks) in mapOf("nhl" to listOf("0:00", "0:00.5", "1:00"),
            "nba" to listOf("12:00", "11:59.5", "11:00"))) {
            val plays = clocks.mapIndexed { index, clock ->
                """{"id":"p$index","homeScore":${if (index == 0) 0 else 1},"awayScore":0,"period":{"number":1},"clock":{"displayValue":"$clock"},"scoringPlay":${index == 1}}"""
            }.joinToString(",")
            val body = """{"header":{"competitions":[{"competitors":[{"homeAway":"home","score":"1"},{"homeAway":"away","score":"0"}],"status":{"type":{"completed":false}}}]},"plays":[$plays]}"""
            val value = EspnTimeline.parse(body, league)!!
            assertEquals(league, SkipAnswer.DO_NOT_SKIP, SpoilerFreeTimeline.canSkip(value, at(0), at(60)))
            assertEquals(league, ScoreAnswer.NO, SpoilerFreeTimeline.answerAt(value, at(0), ScoreQuestion.ANY_SCORE))
            assertEquals(league, ScoreAnswer.YES, SpoilerFreeTimeline.answerAt(value, at(60), ScoreQuestion.ANY_SCORE))
        }
    }

    @Test fun `precise soccer seconds refine a minute label without moving a goal earlier`() {
        val goal = """{"id":"g","period":{"number":1},"clock":{"displayValue":"15'","value":950},"scoringPlay":true,"team":{"id":"home"}}"""
        val value = EspnTimeline.parse(soccer(events = goal, home = 1), "mls")!!
        assertEquals(SkipAnswer.DO_NOT_SKIP, SpoilerFreeTimeline.canSkip(value, at(900), at(1200)))
        assertEquals(ScoreAnswer.NO, SpoilerFreeTimeline.answerAt(value, at(900), ScoreQuestion.ANY_SCORE))
        assertEquals(ScoreAnswer.YES, SpoilerFreeTimeline.answerAt(value, at(960), ScoreQuestion.ANY_SCORE))
        assertFalse(SoccerGoalGuide.answer(value, CatchUpRequest(CatchUpQuestion.GOAL_TIMES, "15:20")).startsWith("No goals"))
    }

    @Test fun `minute-only soccer timing never certifies the minute after its boundary as quiet`() {
        val goal = """{"id":"g","period":{"number":1},"clock":{"displayValue":"15'"},"scoringPlay":true,"team":{"id":"home"}}"""
        val value = EspnTimeline.parse(soccer(events = goal, home = 1), "mls")!!
        assertEquals(SkipAnswer.DO_NOT_SKIP, SpoilerFreeTimeline.canSkip(value, at(900), at(960)))
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeTimeline.answerAt(value, at(900), ScoreQuestion.ANY_SCORE))
        assertFalse(SoccerGoalGuide.answer(value, CatchUpRequest(CatchUpQuestion.GOAL_TIMES, "15:20")).startsWith("No goals"))
    }

    @Test fun `added-time clamped numeric clocks are independent of JSON decimal spelling`() {
        val expected = SoccerClock.provider(1, "45+2", "2700")
        assertNotNull(expected)
        for (number in listOf("2700.0", "2700.000", "2.7E3")) {
            assertEquals(number, expected, SoccerClock.provider(1, "45+2", number))
        }
    }

    @Test fun `coarse final marker follows a precise goal in its final minute`() {
        val events = """{"id":"g","period":{"number":2},"clock":{"displayValue":"90+2:30"},"scoringPlay":true,"team":{"id":"home"}},
            {"id":"end","period":{"number":2},"clock":{"displayValue":"90+2"},"scoringPlay":false,"type":{"type":"end-regular-time"}}"""
        val value = EspnTimeline.parse(soccer(events = events, home = 1, completed = true), "mls")!!
        val final = SpoilerFreeTimeline.checkpoints(value).last()
        assertEquals("End of game", final.label)
        assertEquals(SkipAnswer.DO_NOT_SKIP, SpoilerFreeTimeline.canSkip(value, at(105520), final))
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeTimeline.answerAt(value, at(105520), ScoreQuestion.OVER))
        assertEquals(ScoreAnswer.YES, SpoilerFreeTimeline.answerAt(value, final, ScoreQuestion.OVER))
        assertTrue(SoccerGoalGuide.answer(value, CatchUpRequest(CatchUpQuestion.GOAL_TIMES)).contains("match finished"))
    }

    @Test fun `ordinary final-minute event cannot erase a coarse terminal marker`() {
        val events = """{"id":"quiet","period":{"number":2},"clock":{"displayValue":"90:00"},"scoringPlay":false},
            {"id":"end","period":{"number":2},"clock":{"displayValue":"90'"},"scoringPlay":false,"type":{"type":"end-regular-time"}}"""
        val value = EspnTimeline.parse(soccer(events = events, completed = true), "mls")!!
        assertEquals(ScoreAnswer.UNAVAILABLE, SpoilerFreeTimeline.answerAt(value, at(105400), ScoreQuestion.OVER))
        val final = SpoilerFreeTimeline.checkpoints(value).last()
        assertEquals(105460, final.position)
        assertEquals(ScoreAnswer.YES, SpoilerFreeTimeline.answerAt(value, final, ScoreQuestion.OVER))
    }

    private fun play(id: String, clock: String) = """{"id":"$id","homeScore":0,"awayScore":0,"period":{"number":1},"clock":{"displayValue":"$clock"},"scoringPlay":false}"""
    private fun football(plays: String = "${play("a", "15:00")},${play("b", "14:00")}") = """
      {"header":{"competitions":[{"competitors":[{"homeAway":"home","score":"0"},{"homeAway":"away","score":"0"}],
      "status":{"type":{"state":"in","completed":false}}}]},"drives":{"previous":[{"plays":[$plays]}]}}
    """.trimIndent()
    private fun soccer(
        events: String = """{"id":"kickoff","period":{"number":1},"clock":{"displayValue":"0:00"},"scoringPlay":false}""",
        home: Int = 0, completed: Boolean = false,
    ) = """
      {"header":{"competitions":[{"competitors":[{"homeAway":"home","score":"$home","team":{"id":"home"}},
      {"homeAway":"away","score":"0","team":{"id":"away"}}],
      "status":{"period":1,"displayClock":"30:00","type":{"state":"in","completed":$completed}}}]},"keyEvents":[$events]}
    """.trimIndent()
}
