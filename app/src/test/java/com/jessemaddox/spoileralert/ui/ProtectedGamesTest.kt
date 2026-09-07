package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.data.ShieldEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectedGamesTest {
    private val atlantaShield = ShieldEntity(
        id = 7, name = "Atlanta United FC", aliasesJson = "[]", kind = "TEAM",
        catalogTeamId = "mls-atlanta-united", armed = true,
    )
    private val game = GameEntity(
        id = "761663", shieldId = 7, name = "Atlanta United FC at Nashville SC",
        shortName = "ATL @ NSH", startMillis = 100L, completed = false, fetchedAtMillis = 90L,
    )

    @Test fun `team cached game reaches Home even when league discovery row is missing`() {
        val out = ProtectedGames.forHome(
            shields = listOf(atlantaShield), games = listOf(game), leagueGamesById = emptyMap(),
            leagueIdForTeam = { if (it == "mls-atlanta-united") "mls" else null },
        )

        assertEquals(listOf(ProtectedLiveGame(
            eventId = "761663", leagueId = "mls", name = game.name,
            shortName = "ATL @ NSH", startMillis = 100L, completed = false, label = null,
        )), out)
    }

    @Test fun `game shield falls back to discovery league and duplicate event appears once`() {
        val gameShield = atlantaShield.copy(
            id = 8, kind = "GAME", catalogTeamId = null, gameEventId = game.id,
        )
        val discovery = LeagueGameEntity(
            eventId = game.id, leagueId = "mls", name = game.name, shortName = game.shortName,
            startMillis = game.startMillis, completed = false, homeEspnId = "1", awayEspnId = "2",
            label = "Special", fetchedAtMillis = 90L,
        )
        val out = ProtectedGames.forHome(
            shields = listOf(atlantaShield, gameShield),
            games = listOf(game, game.copy(shieldId = 8)),
            leagueGamesById = mapOf(game.id to discovery),
            leagueIdForTeam = { "mls" },
        )

        assertEquals(1, out.size)
        assertEquals("Special", out.single().label)
    }

    @Test fun `multi-day game shield reaches Home without recent team cache row`() {
        val gameShield = ShieldEntity(
            id = 8, name = "The Open", aliasesJson = "[]", kind = "GAME",
            gameEventId = "open", armed = true,
        )
        val discovery = LeagueGameEntity(
            eventId = "open", leagueId = "golf", name = "The Open",
            shortName = "The Open", startMillis = 10L, completed = false,
            homeEspnId = "", awayEspnId = "", label = "Round 4", fetchedAtMillis = 90L,
            endMillis = 1_000L, phase = "final-round",
        )

        val out = ProtectedGames.forHome(
            shields = listOf(gameShield), games = emptyList(),
            leagueGamesById = mapOf("open" to discovery), leagueIdForTeam = { null },
        )

        assertEquals("open", out.single().eventId)
        assertEquals(1_000L, out.single().endMillis)
        assertEquals("final-round", out.single().phase)
    }
}
