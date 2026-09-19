package com.jessemaddox.spoileralert.schedule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One game from an ESPN league-wide scoreboard. This is the DISCOVERY pool: every game in a
 * league's window, regardless of whether either team is one of the user's shields — that's the
 * point (MNF/TNF/playoff matchups must be armable even when the user follows neither team).
 * Pure public schedule metadata, same data class as team schedules; never joined with
 * notification or vault content.
 *
 * [homeEspnId]/[awayEspnId] are ESPN's numeric team ids as they appear on the scoreboard
 * (plain "14", NOT the catalog's "football/nfl/14" path form) — resolve them to catalog teams
 * with [com.jessemaddox.spoileralert.domain.TeamCatalog.teamByEspnId] at arm time.
 *
 * [label] is ESPN's event note headline when present ("NFL Melbourne Game",
 * "NBA Play-In - East - 9th Place vs 10th Place", "Doubleheader - Game 1"); most games have none.
 */
data class LeagueGame(
    val eventId: String,
    val leagueId: String,
    val name: String,
    val shortName: String,
    val startMillis: Long,
    val completed: Boolean,
    val homeEspnId: String,
    val awayEspnId: String,
    val label: String?,
    /** ESPN's coarse public state: "pre", "in", or "post". */
    val statusState: String = "pre",
    /** Transient scoreboard values used only for spoiler-free yes/no answers. Never persisted. */
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val endMillis: Long? = null,
    val phase: String? = null,
)

/**
 * Pure-Kotlin parser for ESPN's public league scoreboard endpoint
 * (`site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard?dates=...&limit=300`).
 * Shape verified 2026-07-17 against live nfl, cfb (groups=80 filters correctly on scoreboard),
 * nba, mlb, eng.1 and fifa.world responses — all share one event shape.
 *
 * Defensive by design: a malformed event (missing id/date/name or without both a home and an
 * away competitor team id) is skipped, malformed JSON yields an empty list. Never throws.
 */
object EspnScoreboard {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    internal fun hasScheduleEnvelope(text: String): Boolean = runCatching {
        json.parseToJsonElement(text).jsonObject["events"] is JsonArray
    }.getOrDefault(false)

    fun parse(
        text: String,
        leagueId: String,
        allowTeamless: Boolean = false,
        minimumDurationMillis: Long = 0L,
    ): List<LeagueGame> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val events = root["events"] as? JsonArray ?: return emptyList()
        return events.mapNotNull { element ->
            runCatching {
                parseEvent(
                    element as? JsonObject ?: return@mapNotNull null,
                    leagueId,
                    allowTeamless,
                    minimumDurationMillis,
                )
            }.getOrNull()
        }
    }

    private fun parseEvent(
        event: JsonObject,
        leagueId: String,
        allowTeamless: Boolean,
        minimumDurationMillis: Long,
    ): LeagueGame? {
        val id = event.string("id") ?: return null
        val startMillis = event.string("date")?.let(::parseInstantMillis) ?: return null
        val name = event.string("name") ?: return null
        val shortName = event.string("shortName") ?: name

        val competition = (event["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val competitors = (competition["competitors"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val home = competitors.competitor(homeAway = "home")
        val away = competitors.competitor(homeAway = "away")
        val homeEspnId = home?.teamId()
        val awayEspnId = away?.teamId()
        if (!allowTeamless && (homeEspnId == null || awayEspnId == null)) return null

        val statusType = (competition["status"] as? JsonObject)?.get("type") as? JsonObject
        val completed = statusType?.string("completed") == "true"
        val statusState = statusType?.string("state") ?: if (completed) "post" else "pre"

        val label = (competition["notes"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstNotNullOfOrNull { it.string("headline")?.trim()?.takeIf(String::isNotEmpty) }
        val suppliedEnd = (event.string("endDate") ?: competition.string("endDate"))
            ?.let(::parseInstantMillis)
        val minimumEnd = startMillis + minimumDurationMillis
        val endMillis = listOfNotNull(suppliedEnd, minimumEnd.takeIf { minimumDurationMillis > 0 })
            .maxOrNull()
        val phase = (event["season"] as? JsonObject)?.string("slug")

        return LeagueGame(
            id, leagueId, name, shortName, startMillis, completed,
            homeEspnId.orEmpty(), awayEspnId.orEmpty(), label, statusState,
            home?.score(), away?.score(), endMillis, phase,
        )
    }

    private fun List<JsonObject>.competitor(homeAway: String): JsonObject? =
        firstOrNull { it.string("homeAway") == homeAway }

    private fun JsonObject.teamId(): String? = (this["team"] as? JsonObject)?.string("id")

    private fun JsonObject.score(): Int? = string("score")?.toIntOrNull()

    /** `dates=` query value for a UTC window: "YYYYMMDD-YYYYMMDD" (both ends inclusive). */
    fun datesParam(fromMillis: Long, toMillis: Long): String {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        return "${fmt.format(Instant.ofEpochMilli(fromMillis))}-${fmt.format(Instant.ofEpochMilli(toMillis))}"
    }

    /** ESPN dates come as `2026-09-13T17:00Z` (no seconds); tolerate full ISO instants too. */
    private fun parseInstantMillis(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
