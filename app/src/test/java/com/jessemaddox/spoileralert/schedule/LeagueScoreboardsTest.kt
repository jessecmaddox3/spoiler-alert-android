package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class LeagueScoreboardsTest {
    @Test fun `MLS discovery uses ESPN usa first division scoreboard`() {
        val mls = LEAGUE_SCOREBOARDS.single { it.leagueId == "mls" }
        assertEquals("soccer/usa.1", mls.path)
        assertEquals(null, mls.extraQuery)
    }

    @Test fun `all discovery league ids are unique`() {
        assertEquals(
            LEAGUE_SCOREBOARDS.size,
            LEAGUE_SCOREBOARDS.map { it.leagueId }.distinct().size,
        )
    }

    @Test fun `long tail feeds stay discoverable without becoming catalog requirements`() {
        val byId = LEAGUE_SCOREBOARDS.associateBy { it.leagueId }
        assertEquals("hockey/nhl", byId.getValue("nhl").path)
        assertEquals("basketball/wnba", byId.getValue("wnba").path)
        assertEquals("golf/tgl", byId.getValue("tgl").path)
        assertEquals("golf/pga", byId.getValue("golf").path)
        assertEquals(true, byId.getValue("golf").allowTeamless)
        assertEquals("racing/f1", byId.getValue("f1").path)
        assertEquals(true, byId.getValue("f1").allowTeamless)
    }
}
