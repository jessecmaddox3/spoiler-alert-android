package com.jessemaddox.spoileralert.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TeamCatalog(val leagues: List<League>) {
    /** Teams and recurring event interests in a league, alphabetized for browsing. */
    fun entriesForLeague(leagueId: String): List<Team> =
        leagues.firstOrNull { it.id == leagueId }
            ?.teams
            .orEmpty()
            .sortedBy { it.name.lowercase() }

    /** Catalog entry for a saved interest id, or null for unknown/custom ids. */
    fun teamById(teamId: String?): Team? =
        teamId?.let { id -> leagues.asSequence().flatMap { it.teams.asSequence() }.firstOrNull { it.id == id } }

    /** League a catalog team belongs to, or null for unknown/custom ids. */
    fun leagueIdForTeam(teamId: String?): String? =
        teamId?.let { id -> leagues.firstOrNull { l -> l.teams.any { it.id == id } }?.id }

    /**
     * Catalog team for an ESPN scoreboard competitor id, or null if we don't carry that team
     * (partial-coverage leagues like cfb — the game is still stored and armable one-sided).
     *
     * ESPN's scoreboard gives plain numeric team ids ("14"); the catalog's [Team.espn] field is
     * a path ("football/nfl/14"), so we match on the path's last segment, scoped to [leagueId]
     * because numeric ids collide across leagues (nfl "1" is Atlanta, mlb "1" is Baltimore).
     */
    fun teamByEspnId(leagueId: String, espnId: String): Team? {
        if (espnId.isBlank()) return null
        return leagues.firstOrNull { it.id == leagueId }
            ?.teams?.firstOrNull { it.espn?.substringAfterLast('/') == espnId }
    }

    /**
     * Flat cross-league team search for the rebuilt add flow (v3 amendment item 7).
     * Case-insensitive substring match against each team's full name (which carries the
     * city), its aliases, and its short forms. Blank query → empty list (the add sheet shows
     * the league browser instead). Results are alphabetical by name so search and league
     * browsing use the same predictable order.
     */
    fun searchTeams(query: String): List<Pair<League, Team>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<League, Team>>()
        for (league in leagues) {
            for (team in league.teams) {
                val haystack = (listOf(team.name) + team.aliases + team.short)
                if (haystack.any { it.lowercase().contains(q) }) out += league to team
            }
        }
        return out.sortedWith(
            compareBy<Pair<League, Team>> { it.second.name.lowercase() }
                .thenBy { it.first.name.lowercase() }
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): TeamCatalog = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class League(val id: String, val name: String, val teams: List<Team>)

@Serializable
data class Team(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val short: List<String> = emptyList(),
    /** When the bare team name is itself an everyday word (e.g. "Athletics"): emit the name as a
     *  short alias so STRICT messaging mode skips it unless paired with sports context. */
    val nameShort: Boolean = false,
    /** ESPN schedule path "<sport>/<league>/<teamId>" (e.g. "football/nfl/1"); null = no schedule support. */
    val espn: String? = null,
) {
    /** Full name + explicit aliases, deduped lowercase; short aliases flagged for STRICT skipping. */
    fun allAliases(): List<Alias> {
        val full = ((if (nameShort) emptyList() else listOf(name)) + aliases)
            .map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct()
        val shorts = ((if (nameShort) listOf(name) else emptyList()) + short)
            .map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct().filter { it !in full }
        return full.map { Alias(it) } + shorts.map { Alias(it, short = true) }
    }
}
