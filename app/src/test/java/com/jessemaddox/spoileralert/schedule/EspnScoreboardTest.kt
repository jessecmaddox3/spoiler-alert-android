package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parser tests against real (trimmed) ESPN league scoreboard responses captured 2026-07-17. */
class EspnScoreboardTest {
    private fun fixture(name: String): String = File("src/test/resources/espn/$name").readText()

    @Test fun `parses nfl scoreboard with and without a notes label`() {
        val games = EspnScoreboard.parse(fixture("nfl_scoreboard.json"), leagueId = "nfl")
        assertEquals(2, games.size)

        val melbourne = games.first { it.eventId == "401872657" }
        assertEquals("nfl", melbourne.leagueId)
        assertEquals("San Francisco 49ers at Los Angeles Rams", melbourne.name)
        assertEquals("SF VS LAR", melbourne.shortName)
        assertEquals(1789086900000L, melbourne.startMillis) // 2026-09-11T00:35Z
        assertFalse(melbourne.completed)
        assertEquals("14", melbourne.homeEspnId)
        assertEquals("25", melbourne.awayEspnId)
        assertEquals("NFL Melbourne Game", melbourne.label)

        val plain = games.first { it.eventId == "401872925" }
        assertEquals("TB @ CIN", plain.shortName)
        assertEquals("4", plain.homeEspnId)
        assertEquals("27", plain.awayEspnId)
        assertNull(plain.label) // empty notes array -> no label
    }

    @Test fun `parses mlb scoreboard, doubleheader headline captured as label`() {
        val games = EspnScoreboard.parse(fixture("mlb_scoreboard.json"), leagueId = "mlb")
        assertEquals(2, games.size)
        val dh = games.first { it.eventId == "401872178" }
        assertEquals("Doubleheader - Game 1 - Makeup from May 9", dh.label)
        assertEquals(1784309700000L, dh.startMillis) // 2026-07-17T17:35Z
        assertEquals("2", dh.homeEspnId)   // Red Sox
        assertEquals("30", dh.awayEspnId)  // Rays
        assertNull(games.first { it.eventId == "401816142" }.label)
    }

    @Test fun `parses nba scoreboard with completed games and play-in label`() {
        val games = EspnScoreboard.parse(fixture("nba_scoreboard.json"), leagueId = "nba")
        assertEquals(2, games.size)
        assertTrue(games.all { it.completed })
        val playIn = games.first { it.eventId == "401866755" }
        assertEquals("NBA Play-In - East - 9th Place vs 10th Place", playIn.label)
        assertEquals("30", playIn.homeEspnId)
        assertEquals("14", playIn.awayEspnId)
    }

    @Test fun `parses fifa world cup scoreboard (soccer shape, no notes)`() {
        val games = EspnScoreboard.parse(fixture("fifa_world_scoreboard.json"), leagueId = "soccer")
        assertEquals(2, games.size)
        val semi = games.first { it.eventId == "760516" }
        assertEquals("England at France", semi.name)
        assertEquals("478", semi.homeEspnId) // France
        assertEquals("448", semi.awayEspnId) // England
        assertNull(semi.label)
        assertFalse(semi.completed)
        assertEquals(1784408400000L, semi.startMillis) // 2026-07-18T21:00Z
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
              "id": "761663", "date": "2026-07-18T00:10Z",
              "name": "Atlanta United FC at Nashville SC", "shortName": "ATL @ NSH",
              "competitions": [{
                "competitors": [
                  {"homeAway": "home", "score": "1", "team": {"id": "18986"}},
                  {"homeAway": "away", "score": "2", "team": {"id": "18418"}}
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
              "id": "401811957", "date": "2026-07-16T04:00Z",
              "endDate": "2026-07-19T04:00Z", "name": "The Open", "shortName": "The Open",
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
        assertEquals(1784520000000L, event.endMillis)
        assertEquals("regular-season", event.phase)
    }

    @Test fun `world cup phase slug is retained for featured-event policy`() {
        val json = """
            {"events": [{
              "id": "760516", "date": "2026-07-18T21:00Z",
              "name": "England at France", "shortName": "ENG @ FRA",
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
