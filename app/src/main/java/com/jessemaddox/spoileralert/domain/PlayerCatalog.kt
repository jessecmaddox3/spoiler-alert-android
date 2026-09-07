package com.jessemaddox.spoileralert.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Star players and head coaches per catalog team (parses `assets/players.json`). Mirrors
 * [TeamCatalog]: a player's full name and distinctive nicknames are normal aliases, while
 * everyday-word surnames (e.g. "London", "Cousins", "Allen") are `short` so STRICT messaging
 * mode skips them unless paired with sports context. Loaded at arm time to broaden a team
 * shield's alias set (see [ShieldRepository.refreshTeamShield]).
 */
@Serializable
data class PlayerCatalog(val leagues: List<PlayerLeague>) {
    private val byTeam: Map<String, List<Player>> by lazy {
        leagues.flatMap { it.teams }.associate { it.id to it.players }
    }

    /** Union of a team's players' aliases (name + aliases + short), deduped by text. */
    fun aliasesForTeam(teamId: String): List<Alias> {
        val players = byTeam[teamId] ?: return emptyList()
        val out = mutableListOf<Alias>()
        val seen = mutableSetOf<String>()
        for (p in players) for (a in p.allAliases()) if (seen.add(a.text)) out += a
        return out
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): PlayerCatalog = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class PlayerLeague(val id: String, val name: String = "", val teams: List<PlayerTeam>)

@Serializable
data class PlayerTeam(val id: String, val players: List<Player>)

@Serializable
data class Player(
    val name: String,
    val aliases: List<String> = emptyList(),
    val short: List<String> = emptyList(),
) {
    /** Full name + explicit aliases, deduped lowercase; short aliases flagged for STRICT skipping. */
    fun allAliases(): List<Alias> {
        val full = (listOf(name) + aliases).map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct()
        val shorts = short.map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct().filter { it !in full }
        return full.map { Alias(it) } + shorts.map { Alias(it, short = true) }
    }
}
