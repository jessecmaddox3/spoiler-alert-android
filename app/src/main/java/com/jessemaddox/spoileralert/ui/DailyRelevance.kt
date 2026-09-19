package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.GameWindows
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.domain.Team
import com.jessemaddox.spoileralert.domain.TeamCatalog
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

data class PersonalToday(
    val shield: ShieldEntity,
    val game: GameEntity,
    val leagueId: String? = null,
)

data class FeaturedToday(
    val game: LeagueGameEntity,
    val reason: String,
)

data class InterestToday(
    val shield: ShieldEntity,
    val game: LeagueGameEntity,
)

data class DailyEventFeed(
    val personal: List<PersonalToday>,
    val interests: List<InterestToday>,
    val featured: List<FeaturedToday>,
    val otherToday: OtherToday = OtherToday(),
)

data class OtherToday(
    val count: Int = 0,
    val leagueIds: List<String> = emptyList(),
)

/**
 * Pure daily-feed policy. The full ESPN pool stays searchable; only explicit personal and
 * featured rules earn scarce Home space. Empty output is a successful, quiet day.
 */
object DailyRelevance {
    fun build(
        shields: List<ShieldEntity>,
        games: List<GameEntity>,
        leagueGames: List<LeagueGameEntity>,
        nowMillis: Long,
        zoneId: ZoneId,
        catalog: TeamCatalog? = null,
    ): DailyEventFeed {
        val shieldsById = shields.associateBy { it.id }
        val personal = games.asSequence()
            .filterNot { it.completed }
            .mapNotNull { game ->
                val shield = shieldsById[game.shieldId] ?: return@mapNotNull null
                if (shield.kind !in setOf("TEAM", "GAME") || HiddenRecency.isHiding(shield, nowMillis)) return@mapNotNull null
                PersonalToday(shield, game, catalog?.leagueIdForTeam(shield.catalogTeamId)
                    ?: leagueGames.firstOrNull { it.eventId == game.id }?.leagueId)
            }
            .filter { happensToday(it.game.startMillis,
                leagueGames.firstOrNull { row -> row.eventId == it.game.id }?.endMillis,
                nowMillis, zoneId) }
            .distinctBy { it.game.id to it.shield.id }
            .sortedBy { it.game.startMillis }
            .toList()
        // Any cached per-shield copy means the event is already represented by a team or game
        // shield. Never duplicate it as an interest or featured card.
        val coveredIds = games.mapTo(mutableSetOf()) { it.id }
        // GAME shields carry durable event identity even after a multi-day event's kickoff falls
        // outside the short in-progress GameEntity look-back used elsewhere on Home. An armed Open
        // Championship shield must therefore suppress its available card directly by event id.
        shields.asSequence()
            .filter { HiddenRecency.isHiding(it, nowMillis) }
            .mapNotNullTo(coveredIds) { it.gameEventId }
        val todayLeagueGames = leagueGames.asSequence()
            .filterNot { it.completed || it.eventId in coveredIds }
            .filter { happensToday(it.startMillis, it.endMillis, nowMillis, zoneId) }
            .toList()

        // Event-style interests do not have a conventional team schedule endpoint. Match their
        // public league-wide event instead, so saving The Open, Formula 1, or the World Cup has
        // the same Home effect as saving a team. The event is protected through a GAME shield
        // when tapped, which adds the event plus the league's wider spoiler vocabulary.
        val interestMatches = if (catalog == null) emptyList() else shields.asSequence()
            .filter { it.kind == "TEAM" && it.catalogTeamId != null }
            .flatMap { shield ->
                val team = catalog.teamById(shield.catalogTeamId) ?: return@flatMap emptySequence()
                val leagueId = catalog.leagueIdForTeam(team.id) ?: return@flatMap emptySequence()
                todayLeagueGames.asSequence()
                    .filter { it.leagueId == leagueId && matchesEventInterest(team, it, zoneId) }
                    .map { InterestToday(shield, it) }
            }
            .distinctBy { it.game.eventId }
            .sortedBy { it.game.startMillis }
            .toList()
        val interestEventIds = interestMatches.mapTo(mutableSetOf()) { it.game.eventId }
        val interests = interestMatches.filterNot { HiddenRecency.isHiding(it.shield, nowMillis) }

        val featured = leagueGames.asSequence()
            .filterNot { it.completed || it.eventId in coveredIds || it.eventId in interestEventIds }
            .filter { happensToday(it.startMillis, it.endMillis, nowMillis, zoneId) }
            .mapNotNull { game -> featuredReason(game, zoneId)?.let { FeaturedToday(game, it) } }
            .distinctBy { it.game.eventId }
            .sortedBy { it.game.startMillis }
            .toList()
        val featuredEventIds = featured.mapTo(mutableSetOf()) { it.game.eventId }
        val otherGames = todayLeagueGames.filterNot {
            it.eventId in interestEventIds || it.eventId in featuredEventIds
        }
        return DailyEventFeed(
            personal = personal,
            interests = interests,
            featured = featured,
            otherToday = OtherToday(
                count = otherGames.size,
                leagueIds = otherGames.map { it.leagueId }.distinct(),
            ),
        )
    }

    private fun matchesEventInterest(team: Team, game: LeagueGameEntity, zoneId: ZoneId): Boolean {
        return when (team.id) {
            "nfl-monday-night-football" -> featuredReason(game, zoneId) == "Monday Night Football"
            "nfl-thursday-night-football" -> featuredReason(game, zoneId) == "Thursday Night Football"
            "nfl-sunday-night-football" -> featuredReason(game, zoneId) == "Sunday Night Football"
            "golf-pga-tour" -> true
            "golf-fedex-cup" -> {
                val name = game.name.lowercase()
                "fedex" in name || "tour championship" in name
            }
            "soccer-world-cup" -> true
            else -> when (game.leagueId) {
                // ESPN's F1 events are race weekends, not team-versus-team rows. Any saved F1
                // constructor or the general F1 interest therefore maps to that day's race.
                "f1" -> true
                "golf" -> {
                    val name = game.name.lowercase().trim()
                    team.allAliases().any { alias ->
                        name == alias.text || (alias.text.length >= 5 && alias.text in name)
                    }
                }
                else -> false
            }
        }
    }

    fun featuredReason(game: LeagueGameEntity, zoneId: ZoneId): String? {
        // NFL broadcast brands are defined by Eastern kickoff time, not the viewer's local
        // wall clock. This keeps SNF working on the West Coast and excludes Thanksgiving's
        // Thursday afternoon games from TNF.
        val eventZone = if (game.leagueId == "nfl") NFL_BROADCAST_ZONE else zoneId
        val start = Instant.ofEpochMilli(game.startMillis).atZone(eventZone)
        if (game.leagueId == "nfl") {
            if (start.hour < 19) return null
            return when (start.dayOfWeek) {
                DayOfWeek.MONDAY -> "Monday Night Football"
                DayOfWeek.THURSDAY -> "Thursday Night Football"
                DayOfWeek.SUNDAY -> "Sunday Night Football"
                else -> null
            }
        }

        if (game.leagueId == "soccer") {
            return when (game.phase?.lowercase()) {
                "round-of-16" -> "World Cup knockout match"
                "quarterfinals" -> "World Cup quarterfinal"
                "semifinals" -> "World Cup semifinal"
                "3rd-place-match" -> "World Cup third-place match"
                "final" -> "World Cup final"
                else -> null
            }
        }

        if (game.leagueId == "golf") {
            val name = game.name.lowercase()
            return when {
                "masters tournament" in name || name == "the masters" -> "Golf major"
                "pga championship" in name -> "Golf major"
                name == "u.s. open" || "u.s. open golf" in name -> "Golf major"
                name == "the open" || "open championship" in name -> "Golf major"
                "ryder cup" in name -> "Ryder Cup"
                "presidents cup" in name -> "Presidents Cup"
                else -> null
            }
        }

        val description = listOfNotNull(game.name, game.label, game.phase)
            .joinToString(" ").lowercase()
        return when {
            "super bowl" in description -> "Super Bowl"
            "national championship" in description -> "National championship"
            "championship game" in description -> "Championship game"
            else -> null
        }
    }

    private val NFL_BROADCAST_ZONE: ZoneId = ZoneId.of("America/New_York")

    private fun happensToday(
        startMillis: Long,
        endMillis: Long?,
        nowMillis: Long,
        zoneId: ZoneId,
    ): Boolean {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val dayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val effectiveEnd = endMillis ?: (startMillis + GameWindows.IN_PROGRESS_LOOKBACK_MS)
        return startMillis < dayEnd && effectiveEnd >= dayStart
    }
}
