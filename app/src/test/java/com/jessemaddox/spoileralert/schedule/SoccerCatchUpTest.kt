package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.*
import org.junit.Test

class SoccerCatchUpTest {
    @Test fun `summary combines only requested current facts and a neutral viewing cue`() {
        val json = summary(goal("g", 1, "15'", 900))
        val answer = CatchUpSummary.answer(EspnSummary.parseScoreSnapshot(json, "mls")!!,
            EspnTimeline.parse(json, "mls"), CatchUpRequest(CatchUpQuestion.SUMMARY, "5"))
        assertTrue(answer, "30th minute" in answer)
        assertTrue(answer, "1 goal in total" in answer)
        assertTrue(answer, "Resume at 10′" in answer)
        assertFalse(answer, "15′" in answer || "1-0" in answer || "home" in answer)
    }

    @Test fun `football summary does not infer performance from the score`() {
        val snapshot = ScoreSnapshot("nfl", "in", false, 35, 0, "8:10", 3)
        val answer = CatchUpSummary.answer(snapshot, null, CatchUpRequest(CatchUpQuestion.SUMMARY))
        assertEquals("Latest checked game state\n8:10 left in the 3rd quarter.", answer)
    }
    @Test fun `total goals never reveals their allocation or includes shootouts`() {
        val snapshot = EspnSummary.parseScoreSnapshot(summary("", home = 2, away = 1), "mls")!!
        assertEquals("3 goals in total.", SpoilerFreeScore.detail(snapshot, ScoreQuestion.TOTAL_GOALS))
        assertEquals(ScoreAnswer.YES, SpoilerFreeScore.answer(snapshot, ScoreQuestion.TOTAL_GOALS))
        assertEquals(null, SpoilerFreeScore.detail(snapshot.copy(homeScore = null), ScoreQuestion.TOTAL_GOALS))
    }

    @Test fun `added time is ordered before the next half and never concatenated`() {
        val stoppage = SoccerClock.parse(1, "45'+2'", 2700.0)!!
        val secondHalf = SoccerClock.parse(2, "46:00")!!
        assertEquals("45+2′", stoppage.label)
        assertTrue(stoppage.position < secondHalf.position)
        assertEquals("42′", stoppage.leadIn(5).label)
        assertEquals("Start of second half", secondHalf.leadIn(5).label)
        assertNull(SoccerClock.parse(1, "45+who won"))
    }

    @Test fun `goal at fifteen suggests ten with five minutes of buildup`() {
        val timeline = EspnTimeline.parse(summary(goal("g", 1, "15'", 900)), "mls")!!
        val answer = SoccerGoalGuide.answer(timeline, CatchUpRequest(CatchUpQuestion.RESUME, "5", 5))
        assertTrue(answer, "Resume at 10′" in answer)
        assertFalse(answer, "15′" in answer)
        assertFalse(answer, "home" in answer || "away" in answer || "scorer" in answer)
    }

    @Test fun `goal minutes and Eastern times are revealed only by their requested guide`() {
        val timeline = EspnTimeline.parse(summary(goal("g", 1, "15'", 900)), "mls")!!
        val answer = SoccerGoalGuide.answer(timeline, CatchUpRequest(CatchUpQuestion.GOAL_TIMES, "", 5, true))
        assertTrue(answer, "15′" in answer)
        assertTrue(answer, "7:45 PM EDT" in answer)
        assertTrue(answer, "approx" in answer.lowercase())
    }

    @Test fun `duplicate goal and shootout attempts do not inflate the goal guide`() {
        val goal = goal("g", 1, "15'", 900)
        val shootout = goal("pen", 1, "20'", 1200).replace("\"scoringPlay\":true", "\"scoringPlay\":true,\"shootout\":true")
        val timeline = EspnTimeline.parse(summary("$goal,$goal,$shootout"), "mls")!!
        assertEquals(1, timeline.points.count { it.scoring })
        assertTrue(timeline.complete)
    }

    @Test fun `missing goal history never produces a cue or a safe skip`() {
        val timeline = EspnTimeline.parse(summary(goal("g", 1, "15'", 900), home = 2), "mls")!!
        assertFalse(timeline.complete)
        assertEquals(SkipAnswer.UNAVAILABLE, SpoilerFreeTimeline.canSkip(timeline,
            ScoreCheckpoint("a", "Start", 0), ScoreCheckpoint("b", "10", 600)))
        assertTrue(SoccerGoalGuide.answer(timeline, CatchUpRequest(CatchUpQuestion.RESUME, "5"))
            .contains("can't verify", ignoreCase = true))
    }

    @Test fun `a goal at the entered minute or inside its lead-in says keep watching`() {
        val timeline = EspnTimeline.parse(summary(goal("g", 1, "15'", 900)), "mls")!!
        for (point in listOf("12", "15")) {
            assertTrue(SoccerGoalGuide.answer(timeline, CatchUpRequest(CatchUpQuestion.RESUME, point))
                .startsWith("Keep watching"))
        }
        assertTrue(SoccerGoalGuide.answer(timeline, CatchUpRequest(CatchUpQuestion.RESUME, "35"))
            .contains("ahead of", ignoreCase = true))
    }

    @Test fun `conflicting duplicate goal revisions make the guide unavailable`() {
        val events = goal("g", 1, "15'", 900) + "," + goal("g", 1, "18'", 1080)
        assertFalse(EspnTimeline.parse(summary(events), "mls")!!.complete)
    }

    @Test fun `Eastern cue uses the lead-in actually available in this half`() {
        val json = summary(goal("g", 2, "46'", 2760))
            .replace("\"displayClock\":\"30:00\",\"period\":1", "\"displayClock\":\"60:00\",\"period\":2")
        val answer = SoccerGoalGuide.answer(EspnTimeline.parse(json, "mls"),
            CatchUpRequest(CatchUpQuestion.RESUME, "40", 5, true))
        assertTrue(answer, "Start of second half" in answer)
        assertTrue(answer, "7:44 PM EDT" in answer)
    }

    @Test fun `ambiguous half boundary input requires its half`() {
        assertNull(SoccerClock.input("45:20"))
        assertNull(SoccerClock.input("92"))
        assertEquals(2, SoccerClock.input("2h 45:20")!!.period)
        assertEquals(1, SoccerClock.input("45+2")!!.period)
    }

    @Test fun `recorded match uses verified final event when header clock is absent`() {
        val end = """{"id":"end","period":{"number":2},"clock":{"value":5400,"displayValue":"90'+8'"},"type":{"id":"83","type":"end-regular-time"},"scoringPlay":false}"""
        val json = summary(goal("g", 1, "15'", 900) + "," + end)
            .replace("\"displayClock\":\"30:00\",\"period\":1,", "")
            .replace("\"state\":\"in\",\"completed\":false", "\"state\":\"post\",\"completed\":true")
        val timeline = EspnTimeline.parse(json, "mls")!!
        assertTrue(timeline.complete)
        assertTrue(SoccerGoalGuide.answer(timeline, CatchUpRequest(CatchUpQuestion.GOAL_TIMES))
            .contains("90+8′ (match finished)"))
        val withoutEnd = json.replace("," + end, "")
        assertFalse(EspnTimeline.parse(withoutEnd, "mls")!!.complete)
    }

    @Test fun `penalties phase markers do not enter match goal history`() {
        val end = """{"id":"end","period":{"number":4},"clock":{"displayValue":"120'+2'"},"type":{"id":"87","type":"end-extra-time"},"scoringPlay":false}"""
        val penalty = """{"id":"pen","period":{"number":5},"clock":{"value":0},"type":{"type":"penalty"},"scoringPlay":false,"shootout":false}"""
        val json = summary(goal("g", 1, "15'", 900) + "," + end + "," + penalty)
            .replace("\"displayClock\":\"30:00\",\"period\":1,", "")
            .replace("\"state\":\"in\",\"completed\":false", "\"state\":\"post\",\"completed\":true")
        val timeline = EspnTimeline.parse(json, "mls")!!
        assertTrue(timeline.complete)
        assertEquals(1, timeline.points.count { it.scoring })
        assertEquals(4, timeline.points.last().period)
    }

    @Test fun `direct regulation to penalties uses regulation goal coverage`() {
        val end = """{"id":"end","period":{"number":2},"clock":{"displayValue":"90'+2'"},"type":{"type":"end-regular-time"},"scoringPlay":false}"""
        val penalty = """{"id":"pen","period":{"number":5},"clock":{"value":0},"scoringPlay":false,"shootout":false}"""
        val json = summary(goal("g", 1, "15'", 900) + "," + end + "," + penalty)
            .replace("\"displayClock\":\"30:00\",\"period\":1,", "")
            .replace("\"state\":\"in\",\"completed\":false", "\"name\":\"STATUS_FINAL_PEN\",\"state\":\"post\",\"completed\":true")
        assertTrue(EspnTimeline.parse(json, "mls")!!.complete)
    }

    private fun goal(id: String, period: Int, display: String, value: Int) = """
        {"id":"$id","period":{"number":$period},"clock":{"value":$value,"displayValue":"$display"},
         "scoringPlay":true,"team":{"id":"home"},"wallclock":"2030-04-03T23:45:00Z"}
    """.trimIndent()

    private fun summary(events: String, home: Int = 1, away: Int = 0) = """
        {"header":{"competitions":[{
          "competitors":[
            {"homeAway":"home","score":"$home","shootoutScore":"5","aggregateScore":"9","team":{"id":"home"}},
            {"homeAway":"away","score":"$away","shootoutScore":"4","aggregateScore":"8","team":{"id":"away"}}
          ],
          "status":{"displayClock":"30:00","period":1,"type":{"state":"in","completed":false}}
        }]},"keyEvents":[
          {"id":"start","period":{"number":1},"clock":{"value":0},"scoringPlay":false}
          ${if (events.isBlank()) "" else ",$events"}
        ]}
    """.trimIndent()
}
