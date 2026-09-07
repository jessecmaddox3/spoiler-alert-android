package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayersJsonTest {
    private val players = PlayerCatalog.parse(File("src/main/assets/players.json").readText())
    private val teams = TeamCatalog.parse(File("src/main/assets/teams.json").readText())

    @Test fun `every alias and short form is lowercase`() {
        for (lg in players.leagues) for (t in lg.teams) for (p in t.players) {
            for (a in p.aliases + p.short) {
                assertEquals("not lowercase: '$a' on ${t.id}", a.lowercase(), a)
            }
        }
    }

    @Test fun `no duplicate team ids`() {
        val ids = players.leagues.flatMap { it.teams }.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun `every player team id exists in teams json`() {
        val teamIds = teams.leagues.flatMap { it.teams }.map { it.id }.toSet()
        for (lg in players.leagues) for (t in lg.teams) {
            assertTrue("unknown team id: ${t.id}", t.id in teamIds)
        }
    }

    @Test fun `denylisted everyday words are never non-short aliases`() {
        val deny = setOf(
            "allen", "bass", "brown", "bush", "chase", "cook", "cousins", "day", "fields",
            "flowers", "green", "gray", "hill", "hurts", "jones", "king", "law", "london",
            "love", "miller", "moon", "moore", "moss", "price", "rice", "smith", "wall",
            "white", "williams", "worthy", "young",
        )
        for (lg in players.leagues) for (t in lg.teams) for (p in t.players) {
            for (a in p.allAliases()) {
                if (!a.short) assertTrue("'${a.text}' on ${t.id} must be short-flagged", a.text !in deny)
            }
        }
    }
}
