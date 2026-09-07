package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parser tests against real (trimmed) ESPN team-schedule responses captured 2026-07-16. */
class EspnScheduleTest {
    private fun fixture(name: String): String = File("src/test/resources/espn/$name").readText()

    @Test fun `parses falcons nfl schedule`() {
        val games = EspnSchedule.parseTeamSchedule(fixture("falcons_nfl_schedule.json"), teamId = "1")
        assertEquals(3, games.size)
        val first = games.first()
        assertEquals("401872658", first.eventId)
        assertEquals(1789318800000L, first.startMillis) // 2026-09-13T17:00Z
        assertEquals("Atlanta Falcons at Pittsburgh Steelers", first.name)
        assertEquals("ATL @ PIT", first.shortName)
        assertFalse(first.completed)
    }

    @Test fun `parses braves mlb schedule with completed and upcoming games`() {
        val games = EspnSchedule.parseTeamSchedule(fixture("braves_mlb_schedule.json"), teamId = "15")
        assertEquals(4, games.size)
        assertEquals(listOf(true, true, false, false), games.map { it.completed })
        val done = games.first { it.eventId == "401814691" }
        assertEquals(1774653300000L, done.startMillis) // 2026-03-27T23:15Z
        assertTrue(done.completed)
        val upcoming = games.first { it.eventId == "401817087" }
        assertEquals("ATL @ MIA", upcoming.shortName)
        assertFalse(upcoming.completed)
    }

    @Test fun `parses usmnt fifa schedule (soccer shape, non-ascii names)`() {
        val games = EspnSchedule.parseTeamSchedule(fixture("usmnt_fifa_schedule.json"), teamId = "660")
        assertEquals(3, games.size)
        assertTrue(games.all { it.completed })
        assertEquals("United States at Türkiye", games.first { it.eventId == "760470" }.name)
    }

    @Test fun `response for a different team id is rejected`() {
        val games = EspnSchedule.parseTeamSchedule(fixture("falcons_nfl_schedule.json"), teamId = "999")
        assertTrue(games.isEmpty())
    }

    @Test fun `malformed events are skipped, never thrown`() {
        val json = """
            {"team": {"id": "1"}, "events": [
              {"id": "100", "name": "no date at all"},
              {"id": "101", "date": "not-a-date", "name": "bad date", "shortName": "BAD"},
              {"date": "2026-09-13T17:00Z", "name": "no id"},
              "not even an object",
              {"id": "102", "date": "2026-09-13T17:00Z", "name": "Good Game", "shortName": "GG",
               "competitions": [{"status": {"type": {"completed": false}}}]}
            ]}
        """.trimIndent()
        val games = EspnSchedule.parseTeamSchedule(json, teamId = "1")
        assertEquals(listOf("102"), games.map { it.eventId })
        assertEquals("Good Game", games[0].name)
    }

    @Test fun `event without shortName falls back to name`() {
        val json = """
            {"team": {"id": "1"}, "events": [
              {"id": "1", "date": "2026-09-13T17:00Z", "name": "A at B"}
            ]}
        """.trimIndent()
        val games = EspnSchedule.parseTeamSchedule(json, teamId = "1")
        assertEquals("A at B", games.single().shortName)
        assertFalse(games.single().completed) // missing status defaults to not-completed
    }

    @Test fun `garbage input returns empty list`() {
        assertTrue(EspnSchedule.parseTeamSchedule("", "1").isEmpty())
        assertTrue(EspnSchedule.parseTeamSchedule("<html>503</html>", "1").isEmpty())
        assertTrue(EspnSchedule.parseTeamSchedule("{}", "1").isEmpty())
        assertTrue(EspnSchedule.parseTeamSchedule("""{"events": "nope"}""", "1").isEmpty())
    }

    @Test fun `full seconds timestamps also parse`() {
        val json = """
            {"team": {"id": "1"}, "events": [
              {"id": "1", "date": "2026-09-13T17:00:30Z", "name": "A at B", "shortName": "A@B"}
            ]}
        """.trimIndent()
        assertEquals(1789318830000L, EspnSchedule.parseTeamSchedule(json, "1").single().startMillis)
    }
}
