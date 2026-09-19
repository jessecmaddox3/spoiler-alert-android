package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.data.ShieldEntity

/** Minimal public-schedule metadata needed to render, restore, and query one selected game. */
data class ProtectedLiveGame(
    val eventId: String,
    val leagueId: String,
    val name: String,
    val shortName: String,
    val startMillis: Long,
    val completed: Boolean,
    val label: String?,
    val endMillis: Long? = null,
    val phase: String? = null,
    val homeEspnId: String = "",
    val awayEspnId: String = "",
) : java.io.Serializable

object ProtectedGames {
    fun fromEvent(game: LeagueGameEntity) = ProtectedLiveGame(
        game.eventId, game.leagueId, game.name, game.shortName, game.startMillis,
        game.completed, game.label, game.endMillis, game.phase, game.homeEspnId, game.awayEspnId,
    )

    fun asEvent(game: ProtectedLiveGame) = LeagueGameEntity(
        eventId = game.eventId, leagueId = game.leagueId, name = game.name,
        shortName = game.shortName, startMillis = game.startMillis, completed = game.completed,
        homeEspnId = game.homeEspnId, awayEspnId = game.awayEspnId, label = game.label,
        fetchedAtMillis = 0, endMillis = game.endMillis, phase = game.phase,
    )

    /**
     * Build Home's protected-game list primarily from each shield's team schedule cache. The
     * league-wide discovery row is optional for TEAM shields, so an ESPN scoreboard refresh
     * failure cannot make a known live team game disappear from Home.
     */
    fun forHome(
        shields: List<ShieldEntity>,
        games: List<GameEntity>,
        leagueGamesById: Map<String, LeagueGameEntity>,
        leagueIdForTeam: (String?) -> String?,
    ): List<ProtectedLiveGame> {
        val shieldsById = shields.associateBy { it.id }
        val fromTeamCache = games.mapNotNull { game ->
            val shield = shieldsById[game.shieldId] ?: return@mapNotNull null
            val discovery = leagueGamesById[game.id]
            val leagueId = leagueIdForTeam(shield.catalogTeamId)
                ?: discovery?.leagueId
                ?: return@mapNotNull null
            ProtectedLiveGame(
                eventId = game.id,
                leagueId = leagueId,
                name = game.name,
                shortName = game.shortName,
                startMillis = game.startMillis,
                completed = game.completed,
                label = discovery?.label,
                endMillis = discovery?.endMillis,
                phase = discovery?.phase,
                homeEspnId = discovery?.homeEspnId.orEmpty(),
                awayEspnId = discovery?.awayEspnId.orEmpty(),
            )
        }
        // Multi-day events can outlive the short GameEntity look-back. GAME shields retain the
        // durable ESPN event id, so join that directly to the league pool rather than dropping
        // the active event's status controls after day one.
        val fromGameShields = shields.asSequence()
            .filter { it.kind == "GAME" }
            .mapNotNull { shield ->
                val eventId = shield.gameEventId ?: return@mapNotNull null
                val discovery = leagueGamesById[eventId] ?: return@mapNotNull null
                ProtectedLiveGame(
                    eventId = discovery.eventId,
                    leagueId = discovery.leagueId,
                    name = discovery.name,
                    shortName = discovery.shortName,
                    startMillis = discovery.startMillis,
                    completed = discovery.completed,
                    label = discovery.label,
                    endMillis = discovery.endMillis,
                    phase = discovery.phase,
                    homeEspnId = discovery.homeEspnId,
                    awayEspnId = discovery.awayEspnId,
                )
            }
            .toList()
        return (fromTeamCache + fromGameShields)
            .distinctBy { it.eventId }
            .sortedBy { it.startMillis }
    }
}
