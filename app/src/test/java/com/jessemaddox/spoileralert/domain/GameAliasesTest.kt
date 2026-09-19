package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAliasesTest {

    private val catalog = TeamCatalog.parse(
        """
        {
          "leagues": [
            {
              "id": "nfl",
              "name": "NFL",
              "teams": [
                {"id": "buf-bills", "name": "Buffalo Bills",
                 "aliases": ["bills"], "short": ["buf"], "espn": "football/nfl/2"},
                {"id": "kc-chiefs", "name": "Kansas City Chiefs",
                 "aliases": ["chiefs", "mahomes"], "short": ["kc"], "espn": "football/nfl/12"}
              ]
            },
            {
              "id": "cfb",
              "name": "College Football",
              "teams": [
                {"id": "uga", "name": "Georgia Bulldogs",
                 "aliases": ["dawgs"], "short": ["uga"], "espn": "football/college-football/61"}
              ]
            },
            {
              "id": "golf",
              "name": "Golf",
              "teams": [
                {"id": "golf-open-championship", "name": "The Open Championship",
                 "aliases": ["british open", "claret jug"], "short": ["the open"]},
                {"id": "golf-pga-tour", "name": "PGA Tour", "aliases": ["mcilroy", "scheffler"]}
              ]
            }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun `both sides mapped - union of both teams' full alias sets`() {
        val aliases = GameAliases.forMatchup(
            catalog, leagueId = "nfl", homeEspnId = "12", awayEspnId = "2",
            eventName = "Buffalo Bills at Kansas City Chiefs",
        )!!
        assertTrue(Alias("kansas city chiefs") in aliases)
        assertTrue(Alias("chiefs") in aliases)
        assertTrue(Alias("mahomes") in aliases)
        assertTrue(Alias("kc", short = true) in aliases)
        assertTrue(Alias("buffalo bills") in aliases)
        assertTrue(Alias("bills") in aliases)
        assertTrue(Alias("buf", short = true) in aliases)
    }

    @Test
    fun `both sides mapped - no duplicate alias texts`() {
        val aliases = GameAliases.forMatchup(
            catalog, "nfl", "12", "2", "Buffalo Bills at Kansas City Chiefs",
        )!!
        assertEquals(aliases.size, aliases.distinctBy { it.text }.size)
    }

    @Test
    fun `one side mapped - keeps mapped side and adds both event-name sides as normal aliases`() {
        // Partial-coverage cfb: Vanderbilt isn't in the catalog, Georgia is.
        val aliases = GameAliases.forMatchup(
            catalog, leagueId = "cfb", homeEspnId = "61", awayEspnId = "238",
            eventName = "Vanderbilt Commodores at Georgia Bulldogs",
        )!!
        assertTrue(Alias("georgia bulldogs") in aliases)
        assertTrue(Alias("dawgs") in aliases)
        assertTrue(Alias("uga", short = true) in aliases)
        // The unmapped opponent's display name from the event name, as a normal alias.
        assertTrue(Alias("vanderbilt commodores") in aliases)
    }

    @Test
    fun `event-name sides never duplicate an existing alias text`() {
        val aliases = GameAliases.forMatchup(
            catalog, "cfb", "61", "238", "Vanderbilt Commodores at Georgia Bulldogs",
        )!!
        assertEquals(aliases.size, aliases.distinctBy { it.text }.size)
        // "georgia bulldogs" arrives from BOTH the catalog and the name split — the
        // catalog's normal (non-short) form must win and appear once.
        assertEquals(1, aliases.count { it.text == "georgia bulldogs" })
        assertTrue(Alias("georgia bulldogs") in aliases)
    }

    @Test
    fun `neither side mapped - falls back to parsing the event name`() {
        // World Cup: the soccer league carries no per-country teams.
        val aliases = GameAliases.forMatchup(
            catalog, leagueId = "soccer", homeEspnId = "448", awayEspnId = "478",
            eventName = "England at France",
        )!!
        assertTrue(Alias("england") in aliases)
        assertTrue(Alias("france") in aliases)
        assertEquals(2, aliases.size)
    }

    @Test
    fun `vs separator parses too`() {
        val aliases = GameAliases.forMatchup(
            catalog, "soccer", "448", "478", "England vs France",
        )!!
        assertTrue(Alias("england") in aliases)
        assertTrue(Alias("france") in aliases)
    }

    @Test
    fun `neither side mapped and unparseable name - null so the caller can refuse honestly`() {
        assertNull(GameAliases.forMatchup(catalog, "soccer", "448", "478", "TBD"))
        assertNull(GameAliases.forMatchup(catalog, "soccer", "448", "478", ""))
    }

    @Test
    fun `unknown league behaves like unmapped sides`() {
        val aliases = GameAliases.forMatchup(
            catalog, "nhl", "10", "12", "Boston Bruins at Toronto Maple Leafs",
        )!!
        assertTrue(Alias("boston bruins") in aliases)
        assertTrue(Alias("toronto maple leafs") in aliases)
    }

    private val playerCatalog = PlayerCatalog.parse(
        """
        {"leagues":[{"id":"nfl","teams":[
          {"id":"kc-chiefs","players":[
            {"name":"Patrick Mahomes","aliases":["mahomes"]},
            {"name":"Rashee Rice","short":["rice"]}
          ]},
          {"id":"buf-bills","players":[
            {"name":"Josh Allen","short":["allen"]}
          ]}
        ]}]}
        """.trimIndent()
    )

    @Test
    fun `mapped matchup includes both sides player aliases with flags preserved`() {
        val aliases = GameAliases.forMatchup(
            catalog, "nfl", "12", "2", "Buffalo Bills at Kansas City Chiefs", playerCatalog,
        )!!
        assertTrue(Alias("mahomes") in aliases)
        assertTrue(Alias("rice", short = true) in aliases)
        assertTrue(Alias("allen", short = true) in aliases)
        // Team aliases are still present alongside the players.
        assertTrue(Alias("chiefs") in aliases)
        assertTrue(Alias("bills") in aliases)
    }

    @Test
    fun `null player catalog keeps prior behavior`() {
        val withNull = GameAliases.forMatchup(
            catalog, "nfl", "12", "2", "Buffalo Bills at Kansas City Chiefs", null,
        )
        val withoutArg = GameAliases.forMatchup(
            catalog, "nfl", "12", "2", "Buffalo Bills at Kansas City Chiefs",
        )
        assertEquals(withoutArg, withNull)
        // "rice"/"allen" exist only in the player catalog, so they must be absent when it is null.
        assertFalse(withNull!!.any { it.text == "rice" })
        assertFalse(withNull.any { it.text == "allen" })
    }

    @Test
    fun `teamless named event includes event major and league-wide player aliases`() {
        val aliases = GameAliases.forMatchup(
            catalog, "golf", "", "", "The Open",
        )!!
        assertTrue(Alias("the open") in aliases)
        assertTrue(Alias("the open championship") in aliases)
        assertTrue(Alias("british open") in aliases)
        assertTrue(Alias("mcilroy") in aliases)
        assertTrue(Alias("scheffler") in aliases)
    }
}
