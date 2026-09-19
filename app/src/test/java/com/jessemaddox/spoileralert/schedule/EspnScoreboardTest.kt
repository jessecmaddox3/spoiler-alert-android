package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parser tests against independently constructed fictional league metadata. */
class EspnScoreboardTest {
    private fun fixture(name: String): String = File("src/test/resources/espn/$name").readText()

    @Test fun `football metadata preserves labels and missing labels`() {
        val games = EspnScoreboard.parse(fixture("synthetic_football_scoreboard.json"), "nfl")
        assertEquals(2, games.size)
        val first = games.first()
        assertEquals("fiction-football-a", first.eventId)
        assertEquals("nfl", first.leagueId)
        assertEquals("Cedar Comets at Harbor Kites", first.name)
        assertEquals("CED @ HAR", first.shortName)
        assertEquals(java.time.Instant.parse("2030-04-03T19:05:00Z").toEpochMilli(), first.startMillis)
        assertEquals("synthetic-home", first.homeEspnId)
        assertEquals("synthetic-away", first.awayEspnId)
        assertFalse(first.completed)
        assertEquals("Exhibition under the lights", first.label)
        assertNull(games.last().label)
    }

    @Test fun `baseball doubleheader retains two separate games`() {
        val games = EspnScoreboard.parse(fixture("synthetic_baseball_scoreboard.json"), "mlb")
        assertEquals(2, games.size)
        assertEquals("Doubleheader - Game 1", games.first().label)
        assertTrue(games.first().startMillis < games.last().startMillis)
        assertNull(games.last().label)
    }

    @Test fun `basketball completed games retain their event label`() {
        val games = EspnScoreboard.parse(fixture("synthetic_basketball_scoreboard.json"), "nba")
        assertEquals(2, games.size)
        assertTrue(games.all { it.completed })
        assertEquals("Community Play-In", games.first().label)
    }

    @Test fun `soccer metadata preserves side identity without a label`() {
        val games = EspnScoreboard.parse(fixture("synthetic_soccer_scoreboard.json"), "soccer")
        assertEquals(2, games.size)
        assertEquals("fiction-soccer-a", games.first().eventId)
        assertEquals("synthetic-home", games.first().homeEspnId)
        assertEquals("synthetic-away", games.first().awayEspnId)
        assertNull(games.first().label)
        assertFalse(games.first().completed)
    }

    @Test fun `malformed events are skipped, never thrown`() {
        val json = """
            {"events": [
              {"id": "100", "name": "no date"},
              {"id": "101", "date": "not-a-date", "name": "bad date", "shortName": "BAD"},
              {"date": "2026-09-13T17:00Z", "name": "no id"},
              "not even an object",
              {"id": "102", "date": "2026-09-13T17:00Z", "name": "Missing competitors", "shortName": "MC",
               "competitions": [{"status": {"type": {"completed": false}}}]},
              {"id": "103", "date": "2026-09-13T17:00Z", "name": "One competitor only", "shortName": "OC",
               "competitions": [{"competitors": [{"homeAway": "home", "team": {"id": "7"}}],
                                 "status": {"type": {"completed": false}}}]},
              {"id": "104", "date": "2026-09-13T17:00Z", "name": "Good Game", "shortName": "GG",
               "competitions": [{"competitors": [
                    {"homeAway": "home", "team": {"id": "7"}},
                    {"homeAway": "away", "team": {"id": "8"}}],
                 "status": {"type": {"completed": true}}}]}
            ]}
        """.trimIndent()
        val games = EspnScoreboard.parse(json, leagueId = "nfl")
        assertEquals(listOf("104"), games.map { it.eventId })
        val game = games.single()
        assertEquals("7", game.homeEspnId)
        assertEquals("8", game.awayEspnId)
        assertTrue(game.completed)
        assertNull(game.label)
    }

    @Test fun `malformed json yields empty list`() {
        assertTrue(EspnScoreboard.parse("not json at all", "nfl").isEmpty())
        assertTrue(EspnScoreboard.parse("""{"no": "events"}""", "nfl").isEmpty())
        assertTrue(EspnScoreboard.parse("[1,2,3]", "nfl").isEmpty())
    }

    @Test fun `blank note headline is treated as absent`() {
        val json = """
            {"events": [
              {"id": "1", "date": "2026-09-13T17:00Z", "name": "G", "shortName": "G",
               "competitions": [{"competitors": [
                    {"homeAway": "home", "team": {"id": "1"}},
                    {"homeAway": "away", "team": {"id": "2"}}],
                 "status": {"type": {"completed": false}},
                 "notes": [{"type": "event", "headline": "  "}]}]}
            ]}
        """.trimIndent()
        assertNull(EspnScoreboard.parse(json, "nfl").single().label)
    }

    @Test fun `parses live state and scores for spoiler free questions`() {
        val json = """
            {"events": [{
              "id": "fiction-live-soccer", "date": "2026-07-18T00:10Z",
              "name": "Cedar Comets at Harbor Kites", "shortName": "CED @ HAR",
              "competitions": [{
                "competitors": [
                  {"homeAway": "home", "score": "1", "team": {"id": "synthetic-home"}},
                  {"homeAway": "away", "score": "2", "team": {"id": "synthetic-away"}}
                ],
                "status": {"type": {"state": "in", "completed": false}}
              }]
            }]}
        """.trimIndent()

        val game = EspnScoreboard.parse(json, "mls").single()
        assertEquals("in", game.statusState)
        assertEquals(1, game.homeScore)
        assertEquals(2, game.awayScore)
        assertFalse(game.completed)
    }

    @Test fun `dates param formats a utc window`() {
        // 2026-07-17T12:00:00Z + 8 days -> 20260717-20260725
        val from = 1784289600000L
        assertEquals("20260717-20260725", EspnScoreboard.datesParam(from, from + 8L * 24 * 60 * 60 * 1000))
    }

    @Test fun `teamless multi-day event is parsed only when feed allows it`() {
        val json = """
            {"events": [{
              "id": "fiction-meadow-open", "date": "2030-04-04T04:00Z",
              "endDate": "2030-04-07T04:00Z", "name": "Meadow Open", "shortName": "Meadow Open",
              "season": {"slug": "regular-season"},
              "competitions": [{"competitors": [],
                "status": {"type": {"state": "in", "completed": false}}}]
            }]}
        """.trimIndent()

        assertTrue(EspnScoreboard.parse(json, "golf").isEmpty())
        val event = EspnScoreboard.parse(
            json, "golf", allowTeamless = true,
            minimumDurationMillis = 4L * 24 * 60 * 60 * 1000,
        ).single()
        assertEquals("", event.homeEspnId)
        assertEquals("", event.awayEspnId)
        assertEquals(1901851200000L, event.endMillis)
        assertEquals("regular-season", event.phase)
    }

    @Test fun `world cup phase slug is retained for featured-event policy`() {
        val json = """
            {"events": [{
              "id": "fiction-migration-soccer", "date": "2026-07-18T21:00Z",
              "name": "Amber Owls at Violet Finches", "shortName": "AMB @ VIO",
              "season": {"slug": "3rd-place-match"},
              "competitions": [{"competitors": [
                {"homeAway": "home", "team": {"id": "478"}},
                {"homeAway": "away", "team": {"id": "448"}}
              ], "status": {"type": {"completed": false}}}]
            }]}
        """.trimIndent()
        assertEquals("3rd-place-match", EspnScoreboard.parse(json, "soccer").single().phase)
    }
}
