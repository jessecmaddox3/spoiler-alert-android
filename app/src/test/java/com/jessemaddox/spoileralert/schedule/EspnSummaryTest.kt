package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EspnSummaryTest {
    @Test fun `parses event specific live score snapshot`() {
        val json = """
            {"header":{"competitions":[{
              "id":"761663",
              "competitors":[
                {"homeAway":"home","score":"1","team":{"id":"18986"}},
                {"homeAway":"away","score":"2","team":{"id":"18418"}}
              ],
              "status":{"displayClock":"15:34","period":3,
                "type":{"state":"in","completed":false,"name":"STATUS_IN_PROGRESS","shortDetail":"15:34 - 3rd"}}
            }]}}
        """.trimIndent()

        assertEquals(
            ScoreSnapshot(
                "mls", statusState = "in", completed = false, homeScore = 1, awayScore = 2,
                displayClock = "15:34", period = 3, statusName = "STATUS_IN_PROGRESS",
                statusDetail = "15:34 - 3rd",
            ),
            EspnSummary.parseScoreSnapshot(json, "mls"),
        )
    }

    @Test fun `malformed or incomplete summary is unavailable`() {
        assertNull(EspnSummary.parseScoreSnapshot("not json", "mls"))
        assertNull(EspnSummary.parseScoreSnapshot("""{"header":{"competitions":[]}}""", "mls"))
        val missingScores = """
            {"header":{"competitions":[{"competitors":[],
            "status":{"type":{"state":"pre","completed":false}}}]}}
        """.trimIndent()
        val parsed = EspnSummary.parseScoreSnapshot(missingScores, "mls")
        assertEquals("pre", parsed?.statusState)
        assertFalse(parsed?.completed ?: true)
    }

    @Test fun `golf summary reduces leaderboard to spoiler-safe fields`() {
        val json = """
            {"header":{"competitions":[{
              "competitors":[
                {"score":"-10","status":{"period":4,"displayThrough":"12","type":{"state":"in"}}},
                {"score":"-9","status":{"period":4,"displayThrough":"11","type":{"state":"in"}}},
                {"score":"-6","status":{"period":4,"displayThrough":"F","type":{"state":"post"}}}
              ],
              "status":{"period":4,"type":{"state":"in","completed":false,"name":"STATUS_IN_PROGRESS"}}
            }]}}
        """.trimIndent()

        val parsed = EspnSummary.parseScoreSnapshot(json, "golf")!!
        assertEquals(4, parsed.currentRound)
        assertEquals(true, parsed.leadersStarted)
        assertEquals(12, parsed.leaderHolesCompleted)
        assertEquals(false, parsed.tiedForLead)
        assertEquals(true, parsed.contenderWithinTwo)
        assertEquals(false, parsed.playoff)
        assertEquals(false, parsed.weatherDelay)
    }

    @Test fun `golf summary treats even par as a numeric score`() {
        val json = """
            {"header":{"competitions":[{
              "competitors":[
                {"score":"E","status":{"period":1,"displayThrough":"1","type":{"state":"in"}}},
                {"score":"E","status":{"period":1,"displayThrough":"1","type":{"state":"in"}}}
              ],
              "status":{"period":1,"type":{"state":"in","completed":false}}
            }]}}
        """.trimIndent()

        val parsed = EspnSummary.parseScoreSnapshot(json, "golf")!!
        assertEquals(true, parsed.tiedForLead)
        assertEquals(true, parsed.contenderWithinTwo)
    }
}
