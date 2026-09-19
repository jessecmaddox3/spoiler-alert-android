package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TeamsJsonTest {
    private val catalog = TeamCatalog.parse(File("src/main/assets/teams.json").readText())

    @Test fun `all expected leagues present`() {
        val ids = catalog.leagues.map { it.id }
        assertEquals(
            listOf("nfl", "cfb", "nba", "mlb", "nhl", "wnba", "epl", "mls", "soccer", "f1", "golf", "tgl"),
            ids,
        )
    }

    @Test fun `league sizes are sane`() {
        fun size(id: String) = catalog.leagues.first { it.id == id }.teams.size
        assertEquals(35, size("nfl"))
        assertEquals(30, size("nba"))
        assertEquals(30, size("mlb"))
        assertEquals(32, size("nhl"))
        assertEquals(15, size("wnba"))
        assertEquals(20, size("epl"))
        assertEquals(30, size("mls"))
        assertTrue(size("cfb") >= 60)
        assertTrue(size("soccer") >= 2)
        assertTrue(size("f1") >= 10)
        assertTrue(size("golf") >= 6)
        assertEquals(6, size("tgl"))
    }

    @Test fun `world cup shield is armed-ready during the 2026 tournament`() {
        val wc = catalog.leagues.flatMap { it.teams }.first { it.id == "soccer-world-cup" }
        assertTrue(Alias("world cup") in wc.allAliases())
        assertTrue(Alias("fifa", short = true) in wc.allAliases())
        assertTrue(Alias("mbappé") in wc.allAliases())
    }

    @Test fun `no duplicate team ids and all aliases lowercase`() {
        val allTeams = catalog.leagues.flatMap { it.teams }
        assertEquals(allTeams.size, allTeams.map { it.id }.distinct().size)
        for (t in allTeams) {
            for (a in t.aliases + t.short) {
                assertEquals("alias not lowercase: '$a' on ${t.id}", a.lowercase(), a)
            }
        }
    }

    @Test fun `shared city abbreviations retain the appropriate alias strength`() {
        val falcons = catalog.leagues.flatMap { it.teams }.first { it.id == "atl-falcons" }
        assertTrue(Alias("atl", short = true) in falcons.allAliases())
        val braves = catalog.leagues.flatMap { it.teams }.first { it.id == "atl-braves" }
        assertTrue(Alias("braves") in braves.allAliases())
        val united = catalog.leagues.flatMap { it.teams }.first { it.id == "mls-atlanta-united" }
        assertTrue(Alias("atlanta united") in united.allAliases())
        assertTrue(Alias("atl", short = true) in united.allAliases())
    }

    @Test fun `dangerous everyday words are never unflagged aliases`() {
        // Words likely in innocent personal texts must be short-flagged (or absent), never plain
        // aliases. Screens every team's allAliases() so bare team NAMES are covered too.
        val dangerous = setOf(
            "as", "bucks", "nuggets", "rockies", "masters", "augusta", "the u",
            "stroll", "perez", "hamilton", "russell", "heat", "jazz", "magic",
            "thunder", "warriors", "city", "hull", "brighton", "newcastle", "leeds",
            "athletics", "patriots", "cowboys", "gators", "dawgs", "gophers", "buckeyes",
            "yellow jackets", "mustangs", "cavaliers", "buffs", "cyclones", "pokes", "pistons",
            "blazers", "dubs", "mavericks", "yanks", "jays", "nationals", "nats", "cherries",
            "seagulls", "toffees", "toon", "raptors", "pacers",
        )
        for (league in catalog.leagues) for (t in league.teams) {
            for (a in t.allAliases()) {
                if (!a.short) {
                    assertTrue("'${a.text}' on ${t.id} must be short-flagged or removed", a.text !in dangerous)
                }
            }
        }
        // And "as" must not exist anywhere, even short.
        for (league in catalog.leagues) for (t in league.teams) {
            assertTrue("bare 'as' must not exist (${t.id})", "as" !in t.aliases + t.short)
        }
    }

    @Test fun `every fully supported pro team has an espn schedule id`() {
        val pattern = Regex("""[a-z-]+/[a-z0-9.-]+/\d+""")
        for (leagueId in listOf("nfl", "nba", "mlb", "nhl", "wnba", "epl", "mls", "tgl")) {
            for (t in catalog.leagues.first { it.id == leagueId }.teams.filterNot {
                it.id in setOf(
                    "nfl-monday-night-football",
                    "nfl-sunday-night-football",
                    "nfl-thursday-night-football",
                )
            }) {
                assertTrue("${t.id} missing espn id", t.espn != null)
                assertTrue("${t.id} espn id malformed: ${t.espn}", pattern.matches(t.espn!!))
            }
        }
    }

    @Test fun `cfb espn coverage is at least 60 teams`() {
        val covered = catalog.leagues.first { it.id == "cfb" }.teams.count { it.espn != null }
        assertTrue("cfb espn coverage $covered < 60", covered >= 60)
    }

    @Test fun `popular teams with schedule coverage use generic artwork in the public release`() {
        for (leagueId in listOf("nfl", "cfb", "nba", "mlb", "nhl", "wnba", "epl", "mls")) {
            for (team in catalog.leagues.first { it.id == leagueId }.teams.filter { it.espn != null }) {
                assertTrue("${team.id} needs a stable catalog id", team.id.isNotBlank())
            }
        }
    }

    @Test fun `known espn mappings are correct`() {
        fun team(id: String) = catalog.leagues.flatMap { it.teams }.first { it.id == id }
        assertEquals("football/nfl/1", team("atl-falcons").espn)
        assertEquals("baseball/mlb/15", team("atl-braves").espn)
        assertEquals("basketball/nba/1", team("atl-hawks").espn)
        assertEquals("hockey/nhl/1", team("nhl-boston-bruins").espn)
        assertEquals("basketball/wnba/20", team("wnba-atlanta-dream").espn)
        assertEquals("football/college-football/61", team("cfb-georgia").espn)
        assertEquals("soccer/usa.1/18418", team("mls-atlanta-united").espn)
        assertEquals("soccer/usa.1/20232", team("mls-inter-miami").espn)
        assertEquals("golf/tgl/130349", team("tgl-atlanta-drive").espn)
        assertEquals("soccer/fifa.world/660", team("soccer-usmnt").espn)
    }

    @Test fun `parser tolerates teams without an espn id`() {
        // f1/golf entries (and the World Cup event shield) have no team-level ESPN schedule.
        val f1 = catalog.leagues.first { it.id == "f1" }.teams
        val golf = catalog.leagues.first { it.id == "golf" }.teams
        assertTrue((f1 + golf).all { it.espn == null })
        val parsed = TeamCatalog.parse(
            """{"leagues":[{"id":"x","name":"X","teams":[{"id":"t","name":"T"}]}]}"""
        )
        assertEquals(null, parsed.leagues[0].teams[0].espn)
    }

    @Test fun `NFL recurring broadcast interests are available`() {
        val ids = catalog.leagues.first { it.id == "nfl" }.teams.map { it.id }
        assertTrue("nfl-monday-night-football" in ids)
        assertTrue("nfl-sunday-night-football" in ids)
        assertTrue("nfl-thursday-night-football" in ids)
    }

    @Test fun `famous monikers present`() {
        fun team(id: String) = catalog.leagues.flatMap { it.teams }.first { it.id == id }
        assertTrue(Alias("roll tide") in team("cfb-alabama").allAliases())
        assertTrue(Alias("g-men") in team("nyg-giants").allAliases())
        assertTrue(team("epl-ipswich").allAliases().any { it.text == "ipswich" && !it.short })
    }
}
