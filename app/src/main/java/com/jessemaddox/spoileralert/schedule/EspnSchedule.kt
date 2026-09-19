package com.jessemaddox.spoileralert.schedule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * One game from an ESPN team schedule. Pure schedule metadata — this module never sees
 * notification or vault content.
 */
data class ScheduledGame(
    val eventId: String,
    val startMillis: Long,
    val name: String,
    val shortName: String,
    val completed: Boolean,
)

/**
 * Pure-Kotlin parser for ESPN's public team schedule endpoint
 * (`site.api.espn.com/apis/site/v2/sports/{sport}/{league}/teams/{id}/schedule`).
 *
 * Defensive by design: a malformed event is skipped, malformed JSON yields an empty list,
 * and a response for a different team than requested is rejected. Never throws.
 */
object EspnSchedule {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseTeamSchedule(text: String, teamId: String): List<ScheduledGame> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()

        // ESPN sometimes serves a default team for unknown ids — reject a mismatched response.
        val responseTeamId = (root["team"] as? JsonObject)?.string("id")
        if (responseTeamId != null && responseTeamId != teamId) return emptyList()

        val events = root["events"] as? JsonArray ?: return emptyList()
        return events.mapNotNull { element ->
            runCatching { parseEvent(element as? JsonObject ?: return@mapNotNull null) }.getOrNull()
        }
    }

    private fun parseEvent(event: JsonObject): ScheduledGame? {
        val id = event.string("id") ?: return null
        val startMillis = event.string("date")?.let(::parseInstantMillis) ?: return null
        val name = event.string("name") ?: return null
        val shortName = event.string("shortName") ?: name
        val completed = event["competitions"]?.let { comps ->
            val status = (comps.jsonArray.firstOrNull() as? JsonObject)
                ?.get("status")?.jsonObject
                ?.get("type")?.jsonObject
            (status?.get("completed") as? JsonPrimitive)?.content == "true"
        } ?: false
        return ScheduledGame(id, startMillis, name, shortName, completed)
    }

    /** ESPN dates come as `2026-09-13T17:00Z` (no seconds); tolerate full ISO instants too. */
    private fun parseInstantMillis(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
