package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class EspnGolfScoreboardTest {
    @Test fun `reduces live leaderboard without exposing names or scores`() {
        val json = """
            {"events":[{"id":"open","status":{"type":{"state":"in","completed":false,
              "name":"STATUS_IN_PROGRESS","description":"In Progress"}},"competitions":[{
              "competitors":[
                {"order":1,"score":"-11","linescores":[
                  {"period":3,"linescores":[{},{},{}]},
                  {"period":4,"linescores":[{},{},{},{},{},{},{},{},{},{}]}]},
                {"order":2,"score":"-9","linescores":[
                  {"period":4,"linescores":[{},{},{},{},{},{},{},{},{},{},{},{}]}]},
                {"order":3,"score":"-8","linescores":[
                  {"period":4,"linescores":[{},{}]}]}
              ]}] }]}
        """.trimIndent()

        val parsed = EspnGolfScoreboard.parseScoreSnapshot(json, "open")!!
        assertEquals(4, parsed.currentRound)
        assertEquals(true, parsed.leadersStarted)
        assertEquals(10, parsed.leaderHolesCompleted)
        assertEquals(false, parsed.tiedForLead)
        assertEquals(true, parsed.contenderWithinTwo)
        assertEquals(false, parsed.playoff)
        assertEquals(false, parsed.weatherDelay)
    }

    @Test fun `separates playoff from weather delay`() {
        fun payload(description: String) = """
          {"events":[{"id":"open","status":{"type":{"state":"in","completed":false,
          "description":"$description"}},"competitions":[{"competitors":[]}]}]}
        """.trimIndent()

        assertEquals(true, EspnGolfScoreboard.parseScoreSnapshot(payload("Playoff"), "open")?.playoff)
        assertEquals(false, EspnGolfScoreboard.parseScoreSnapshot(payload("Playoff"), "open")?.weatherDelay)
        assertEquals(true, EspnGolfScoreboard.parseScoreSnapshot(payload("Weather Delay"), "open")?.weatherDelay)
        assertEquals(false, EspnGolfScoreboard.parseScoreSnapshot(payload("Weather Delay"), "open")?.playoff)
    }

    @Test fun `even par leader remains part of tie and proximity answers`() {
        val json = """
          {"events":[{"id":"open","status":{"type":{"state":"in","completed":false}},
          "competitions":[{"competitors":[
            {"order":1,"score":"E","linescores":[{"period":1,"linescores":[{}]}]},
            {"order":2,"score":"E","linescores":[{"period":1,"linescores":[{}]}]}
          ]}]}]}
        """.trimIndent()

        val parsed = EspnGolfScoreboard.parseScoreSnapshot(json, "open")!!
        assertEquals(true, parsed.tiedForLead)
        assertEquals(true, parsed.contenderWithinTwo)
    }
}
