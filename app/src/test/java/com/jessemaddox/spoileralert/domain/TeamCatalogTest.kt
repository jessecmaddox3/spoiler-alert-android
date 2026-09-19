package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamCatalogTest {
    private val json = """
    {
      "leagues": [
        {
          "id": "nfl",
          "name": "NFL",
          "teams": [
            {"id": "atl-falcons", "name": "Atlanta Falcons",
             "aliases": ["falcons", "dirty birds"], "short": ["atl"]}
          ]
        }
      ]
    }
    """.trimIndent()

    @Test fun `parses leagues and teams`() {
        val catalog = TeamCatalog.parse(json)
        assertEquals(1, catalog.leagues.size)
        assertEquals("NFL", catalog.leagues[0].name)
        assertEquals("Atlanta Falcons", catalog.leagues[0].teams[0].name)
    }

    @Test fun `team aliases include the full team name and explicit aliases, shorts flagged`() {
        val team = TeamCatalog.parse(json).leagues[0].teams[0]
        val aliases = team.allAliases()
        assertTrue(Alias("atlanta falcons") in aliases)
        assertTrue(Alias("falcons") in aliases)
        assertTrue(Alias("dirty birds") in aliases)
        assertTrue(Alias("atl", short = true) in aliases)
    }

    @Test fun `leagueIdForTeam finds the owning league, null for unknown or custom`() {
        val catalog = TeamCatalog.parse(json)
        assertEquals("nfl", catalog.leagueIdForTeam("atl-falcons"))
        assertEquals(null, catalog.leagueIdForTeam("not-a-team"))
        assertEquals(null, catalog.leagueIdForTeam(null))
    }

    @Test fun `no duplicate aliases`() {
        val team = TeamCatalog.parse(json).leagues[0].teams[0]
        val aliases = team.allAliases()
        assertEquals(aliases.size, aliases.distinct().size)
    }

    @Test fun `nameShort demotes the bare team name to a short alias`() {
        val nameShortJson = """
        {"leagues":[{"id":"mlb","name":"MLB","teams":[
          {"id":"ath-athletics","name":"Athletics","aliases":["oakland athletics"],
           "short":["a's"],"nameShort":true}
        ]}]}
        """.trimIndent()
        val aliases = TeamCatalog.parse(nameShortJson).leagues[0].teams[0].allAliases()
        assertTrue(Alias("athletics", short = true) in aliases)
        assertFalse(Alias("athletics") in aliases)
        assertTrue(Alias("oakland athletics") in aliases)
        assertTrue(Alias("a's", short = true) in aliases)
    }

    private val multiLeagueJson = """
    {
      "leagues": [
        {
          "id": "nfl",
          "name": "NFL",
          "teams": [
            {"id": "atl-falcons", "name": "Atlanta Falcons", "espn": "football/nfl/1"},
            {"id": "no-espn-team", "name": "No Espn Team"}
          ]
        },
        {
          "id": "mlb",
          "name": "MLB",
          "teams": [
            {"id": "bal-orioles", "name": "Baltimore Orioles", "espn": "baseball/mlb/1"}
          ]
        }
      ]
    }
    """.trimIndent()

    @Test fun `teamByEspnId matches the numeric id within the requested league only`() {
        val catalog = TeamCatalog.parse(multiLeagueJson)
        // The same numeric espn id "1" exists in both leagues — league scoping disambiguates.
        assertEquals("atl-falcons", catalog.teamByEspnId("nfl", "1")?.id)
        assertEquals("bal-orioles", catalog.teamByEspnId("mlb", "1")?.id)
    }

    @Test fun `teamByEspnId is null for unknown league, unknown id, or teams without espn`() {
        val catalog = TeamCatalog.parse(multiLeagueJson)
        assertEquals(null, catalog.teamByEspnId("nba", "1"))
        assertEquals(null, catalog.teamByEspnId("nfl", "999"))
        assertEquals(null, catalog.teamByEspnId("nfl", ""))
    }

    // --- searchTeams (v3 add-flow cross-league search) ---

    private val searchJson = """
    {
      "leagues": [
        {
          "id": "nfl",
          "name": "NFL",
          "teams": [
            {"id": "atl-falcons", "name": "Atlanta Falcons",
             "aliases": ["dirty birds"], "short": ["atl"]},
            {"id": "no-saints", "name": "New Orleans Saints", "short": ["saints"]}
          ]
        },
        {
          "id": "mlb",
          "name": "MLB",
          "teams": [
            {"id": "atl-braves", "name": "Atlanta Braves", "short": ["braves"]}
          ]
        }
      ]
    }
    """.trimIndent()

    @Test fun `searchTeams matches the full name including city`() {
        val results = TeamCatalog.parse(searchJson).searchTeams("falcons")
        assertEquals(1, results.size)
        assertEquals("atl-falcons", results[0].second.id)
        assertEquals("nfl", results[0].first.id)
    }

    @Test fun `searchTeams matches by city across leagues in alphabetical order`() {
        val results = TeamCatalog.parse(searchJson).searchTeams("atlanta")
        assertEquals(listOf("atl-braves", "atl-falcons"), results.map { it.second.id })
        assertEquals(listOf("mlb", "nfl"), results.map { it.first.id })
    }

    @Test fun `league browser entries are alphabetical regardless of catalog order`() {
        val names = TeamCatalog.parse(searchJson).entriesForLeague("nfl").map { it.name }
        assertEquals(listOf("Atlanta Falcons", "New Orleans Saints"), names)
    }

    @Test fun `searchTeams matches aliases and short forms, case-insensitively`() {
        val catalog = TeamCatalog.parse(searchJson)
        assertEquals("atl-falcons", catalog.searchTeams("DIRTY").single().second.id) // alias
        assertEquals("no-saints", catalog.searchTeams("SAINTS").single().second.id)  // short form
    }

    @Test fun `searchTeams is empty for blank query or no match`() {
        val catalog = TeamCatalog.parse(searchJson)
        assertTrue(catalog.searchTeams("").isEmpty())
        assertTrue(catalog.searchTeams("   ").isEmpty())
        assertTrue(catalog.searchTeams("zzz").isEmpty())
    }
}
