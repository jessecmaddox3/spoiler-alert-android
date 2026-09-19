package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnTimelineTest {
    @Test fun `parses countdown basketball plays into ordered timeline positions`() {
        val json = summary(
            completed = true, home = 103, away = 91,
            body = """
              "plays":[
                {"sequenceNumber":"4","awayScore":0,"homeScore":0,"period":{"number":1},"clock":{"displayValue":"12:00"},"scoringPlay":false},
                {"sequenceNumber":"20","awayScore":0,"homeScore":2,"period":{"number":1},"clock":{"displayValue":"11:31"},"scoringPlay":true},
                {"sequenceNumber":"500","awayScore":50,"homeScore":52,"period":{"number":3},"clock":{"displayValue":"5:00"},"scoringPlay":true},
                {"sequenceNumber":"900","awayScore":91,"homeScore":103,"period":{"number":4},"clock":{"displayValue":"0.0"},"scoringPlay":false}
              ]
            """.trimIndent(),
        )

        val timeline = EspnTimeline.parse(json, "nba")!!
        assertEquals(listOf(0, 29, 1_860, 2_880), timeline.points.map { it.position })
        assertEquals(2, timeline.points[1].homeScore)
        assertTrue(timeline.points[1].scoring)
        assertTrue(timeline.completed)
    }

    @Test fun `parses nfl drive plays when root plays are absent`() {
        val json = summary(home = 7,
            body = """
              "drives":{"previous":[{"plays":[
                {"id":"a","sequenceNumber":"40","awayScore":0,"homeScore":0,"period":{"number":1},"clock":{"displayValue":"15:00"},"scoringPlay":false},
                {"id":"b","sequenceNumber":"60","awayScore":0,"homeScore":7,"period":{"number":1},"clock":{"displayValue":"6:15"},"scoringPlay":true}
              ]}]}
            """.trimIndent(),
        )

        val timeline = EspnTimeline.parse(json, "nfl")!!
        assertEquals(2, timeline.points.size)
        assertEquals(525, timeline.points.last().position)
    }

    @Test fun `normalizes nhl count up clocks`() {
        val json = summary(away = 1,
            body = """
              "plays":[
                {"awayScore":0,"homeScore":0,"period":{"number":1},"clock":{"displayValue":"0:00"},"scoringPlay":false},
                {"awayScore":1,"homeScore":0,"period":{"number":1},"clock":{"displayValue":"7:30"},"scoringPlay":true}
              ]
            """.trimIndent(),
        )

        val timeline = EspnTimeline.parse(json, "nhl")!!
        assertEquals(listOf(0, 450), timeline.points.map { it.position })
        assertEquals(750, timeline.points.last().clockSecondsRemaining)
    }

    @Test fun `parses baseball inning halves`() {
        val json = summary(away = 1,
            body = """
              "plays":[
                {"sequenceNumber":"1","awayScore":0,"homeScore":0,"period":{"type":"Top","number":1},"scoringPlay":false},
                {"sequenceNumber":"7","awayScore":1,"homeScore":0,"period":{"type":"Top","number":1},"scoringPlay":true},
                {"sequenceNumber":"1","awayScore":1,"homeScore":0,"period":{"type":"Bottom","number":1},"scoringPlay":false}
              ]
            """.trimIndent(),
        )

        val timeline = EspnTimeline.parse(json, "mlb")!!
        assertEquals(InningHalf.TOP, timeline.points.first().inningHalf)
        assertEquals(InningHalf.BOTTOM, timeline.points.last().inningHalf)
        assertTrue(timeline.points.last().position >= 1_000_000)
    }

    @Test fun `soccer key events derive cumulative goals without returning event text`() {
        val json = """
            {"header":{"competitions":[{
              "competitors":[
                {"homeAway":"home","team":{"id":"home"}},
                {"homeAway":"away","team":{"id":"away"}}
              ],
              "status":{"type":{"state":"in","completed":false}}
            }]},
            "keyEvents":[
              {"id":"1","period":{"number":1},"clock":{"value":0},"scoringPlay":false},
              {"id":"2","period":{"number":1},"clock":{"value":1106},"scoringPlay":true,"team":{"id":"home"}}
            ]}
        """.trimIndent()

        val timeline = EspnTimeline.parse(json, "mls")
        assertNotNull(timeline)
        assertEquals(1, timeline!!.points.last().homeScore)
        assertEquals(0, timeline.points.last().awayScore)
        assertTrue(timeline.points.last().scoring)
    }

    @Test fun `soccer live header extends a quiet key event timeline to the current minute`() {
        val json = """
            {"header":{"competitions":[{
              "competitors":[
                {"homeAway":"home","score":"1","team":{"id":"home"}},
                {"homeAway":"away","score":"0","team":{"id":"away"}}
              ],
              "status":{"displayClock":"70:00","period":2,
                "type":{"state":"in","completed":false}}
            }]},
            "keyEvents":[
              {"id":"1","period":{"number":1},"clock":{"value":0},"scoringPlay":false},
              {"id":"2","period":{"number":1},"clock":{"value":1106},"scoringPlay":true,"team":{"id":"home"}}
            ]}
        """.trimIndent()

        val timeline = EspnTimeline.parse(json, "mls")!!
        assertEquals(104_200, timeline.points.last().position)
        assertEquals("70′", timeline.points.last().soccerClock?.label)
        assertEquals(1, timeline.points.last().homeScore)
    }

    @Test fun `missing away score never certifies a non-scoring stretch`() {
        val timeline = EspnTimeline.parse(summary(body = """
            "drives":{"previous":[{"plays":[
              {"id":"a","homeScore":0,"awayScore":0,"period":{"number":1},"clock":{"displayValue":"15:00"}},
              {"id":"b","homeScore":1,"period":{"number":1},"clock":{"displayValue":"14:00"}}
            ]}]}
        """), "nfl")!!
        assertEquals(SkipAnswer.UNAVAILABLE, SpoilerFreeTimeline.canSkip(timeline,
            ScoreCheckpoint("start", "Start", 0), ScoreCheckpoint("end", "End", 60)))
    }

    @Test fun `completed header cannot turn truncated first quarter into end of game`() {
        val json = summary(completed = true, home = 7, body = """
            "drives":{"previous":[{"plays":[
              {"id":"a","homeScore":0,"awayScore":0,"period":{"number":1},"clock":{"displayValue":"15:00"}},
              {"id":"b","homeScore":0,"awayScore":0,"period":{"number":1},"clock":{"displayValue":"14:00"}}
            ]}]}
        """)
        val timeline = EspnTimeline.parse(json, "nfl")!!
        assertEquals(false, timeline.complete)
        assertTrue(SpoilerFreeTimeline.checkpoints(timeline).none { it.label == "End of game" })
    }

    private fun summary(completed: Boolean = false, home: Int = 0, away: Int = 0, body: String) = """
        {"header":{"competitions":[{
          "competitors":[{"homeAway":"home","score":"$home"},{"homeAway":"away","score":"$away"}],
          "status":{"type":{"state":"in","completed":$completed}}
        }]},$body}
    """.trimIndent()
}
