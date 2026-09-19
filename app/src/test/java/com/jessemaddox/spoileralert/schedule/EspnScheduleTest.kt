package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parser tests against independently constructed fictional team schedules. */
class EspnScheduleTest {
    private fun fixture(name: String): String = File("src/test/resources/espn/$name").readText()

    @Test fun `football schedule preserves metadata and ordering`() {
        val games = EspnSchedule.parseTeamSchedule(fixture("synthetic_football_schedule.json"), "synthetic-team")
        assertEquals(3, games.size)
        assertEquals("fiction-schedule-f0", games.first().eventId)
        assertEquals(java.time.Instant.parse("2030-04-03T19:05:00Z").toEpochMilli(), games.first().startMillis)
        assertEquals("Cedar Comets at Harbor Kites", games.first().name)
        assertEquals("CED @ HAR", games.first().shortName)
        assertFalse(games.first().completed)
    }

    @Test fun `baseball schedule keeps completed and upcoming games`() {
        val games = EspnSchedule.parseTeamSchedule(fixture("synthetic_baseball_schedule.json"), "synthetic-team")
        assertEquals(4, games.size)
        assertEquals(listOf(true, true, false, false), games.map { it.completed })
        assertEquals("fiction-schedule-b3", games.last().eventId)
    }

    @Test fun `soccer schedule preserves unicode names`() {
        val games = EspnSchedule.parseTeamSchedule(fixture("synthetic_soccer_schedule.json"), "synthetic-team")
        assertEquals(3, games.size)
        assertTrue(games.all { it.completed })
        assertEquals("Étoile Comets at Harbor Kites", games.first().name)
    }

    @Test fun `a response for a different team is rejected`() {
        assertTrue(EspnSchedule.parseTeamSchedule(fixture("synthetic_football_schedule.json"), "other-team").isEmpty())
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
