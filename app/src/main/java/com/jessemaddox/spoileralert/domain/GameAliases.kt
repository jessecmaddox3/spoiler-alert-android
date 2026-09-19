package com.jessemaddox.spoileralert.domain

/**
 * Alias set for a game shield (v3 amendment item 2): protecting a MATCHUP arms BOTH teams'
 * full alias sets for the session. Pure logic — the repository feeds it a league game's
 * competitor ids + event name and encodes the result.
 */
object GameAliases {

    /**
     * Union of both mapped catalog teams' aliases for a matchup, deduped by text.
     *
     * Partial coverage (cfb, World Cup): any side that doesn't map to a catalog team is
     * covered defensively by splitting the ESPN event name ("Vanderbilt Commodores at
     * Georgia Bulldogs", "England at France") and adding each side's display name as a
     * normal lowercase alias. When a text arrives both ways, the catalog's flag wins
     * (a name-split side is never marked short, and never demotes a catalog alias).
     *
     * Returns null when NEITHER side maps and the event name yields no usable sides —
     * the caller should refuse to create the shield rather than arm something that can't
     * match anything.
     */
    fun forMatchup(
        catalog: TeamCatalog,
        leagueId: String,
        homeEspnId: String,
        awayEspnId: String,
        eventName: String,
        players: PlayerCatalog? = null,
    ): List<Alias>? {
        if (homeEspnId.isBlank() && awayEspnId.isBlank()) {
            return forNamedEvent(catalog, leagueId, eventName)
        }
        val home = catalog.teamByEspnId(leagueId, homeEspnId)
        val away = catalog.teamByEspnId(leagueId, awayEspnId)

        val aliases = mutableListOf<Alias>()
        val seen = mutableSetOf<String>()
        fun add(alias: Alias) {
            if (seen.add(alias.text)) aliases += alias
        }

        // Team aliases first so their short flags win over any colliding player alias.
        home?.allAliases()?.forEach(::add)
        away?.allAliases()?.forEach(::add)
        home?.let { players?.aliasesForTeam(it.id)?.forEach(::add) }
        away?.let { players?.aliasesForTeam(it.id)?.forEach(::add) }

        // Any unmapped side: fall back to the event name's two display names. Added for
        // both sides (we can't tell which name half belongs to the unmapped competitor).
        if (home == null || away == null) {
            sidesFromEventName(eventName).forEach { add(Alias(it)) }
        }

        return aliases.ifEmpty { null }
    }

    /**
     * Teamless ESPN events, such as a PGA tournament or Formula 1 weekend. Include the full
     * event name, a matching catalog event when one exists, and the league's general spoiler
     * vocabulary (players/drivers) so "McIlroy wins" is still caught while The Open is armed.
     */
    fun forNamedEvent(catalog: TeamCatalog, leagueId: String, eventName: String): List<Alias>? {
        val normalized = eventName.lowercase().trim()
        if (normalized.isBlank()) return null
        val league = catalog.leagues.firstOrNull { it.id == leagueId }
        val exact = league?.teams?.firstOrNull { team ->
            team.allAliases().any { it.text == normalized }
        }
        val generalIds = when (leagueId) {
            "golf" -> setOf("golf-pga-tour")
            "f1" -> setOf("f1-general")
            else -> emptySet()
        }

        val out = mutableListOf<Alias>()
        val seen = mutableSetOf<String>()
        fun add(alias: Alias) {
            if (seen.add(alias.text)) out += alias
        }
        add(Alias(normalized))
        exact?.allAliases()?.forEach(::add)
        league?.teams?.filter { it.id in generalIds }?.flatMap { it.allAliases() }?.forEach(::add)
        return out.ifEmpty { null }
    }

    /** "Away at Home" / "Away vs Home" → lowercase side names; empty when unparseable. */
    fun sidesFromEventName(eventName: String): List<String> {
        val normalized = eventName.trim()
        if (normalized.isEmpty()) return emptyList()
        for (separator in SEPARATORS) {
            val idx = normalized.indexOf(separator, ignoreCase = true)
            if (idx > 0) {
                return listOf(
                    normalized.substring(0, idx),
                    normalized.substring(idx + separator.length),
                ).map { it.lowercase().trim() }.filter { it.isNotBlank() }
            }
        }
        return emptyList()
    }

    private val SEPARATORS = listOf(" at ", " vs. ", " vs ", " v ")
}
