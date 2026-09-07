package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCatalogTest {
    private val json = """
    {
      "leagues": [
        {
          "id": "nfl",
          "teams": [
            {
              "id": "atl-falcons",
              "players": [
                {"name": "Bijan Robinson", "aliases": ["bijan"]},
                {"name": "Drake London", "short": ["london"]},
                {"name": "Kirk Cousins", "short": ["cousins"]}
              ]
            }
          ]
        }
      ]
    }
    """.trimIndent()

    @Test fun `full name and explicit aliases are non-short`() {
        val bijan = PlayerCatalog.parse(json).leagues[0].teams[0].players[0].allAliases()
        assertTrue(Alias("bijan robinson") in bijan)
        assertTrue(Alias("bijan") in bijan)
    }

    @Test fun `short list is flagged short and never emitted non-short`() {
        val london = PlayerCatalog.parse(json).leagues[0].teams[0].players[1].allAliases()
        assertTrue(Alias("drake london") in london)
        assertTrue(Alias("london", short = true) in london)
        assertFalse(Alias("london") in london)
    }

    @Test fun `aliasesForTeam unions every player deduped by text`() {
        val aliases = PlayerCatalog.parse(json).aliasesForTeam("atl-falcons")
        assertTrue(Alias("bijan robinson") in aliases)
        assertTrue(Alias("bijan") in aliases)
        assertTrue(Alias("drake london") in aliases)
        assertTrue(Alias("london", short = true) in aliases)
        assertTrue(Alias("kirk cousins") in aliases)
        assertTrue(Alias("cousins", short = true) in aliases)
        assertEquals(aliases.size, aliases.distinctBy { it.text }.size)
    }

    @Test fun `aliasesForTeam is empty for unknown team`() {
        assertTrue(PlayerCatalog.parse(json).aliasesForTeam("no-such-team").isEmpty())
    }
}
