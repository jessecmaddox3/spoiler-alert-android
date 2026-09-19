package com.jessemaddox.spoileralert.schedule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Reduces ESPN's full PGA scoreboard to coarse, spoiler-safe golf status fields. */
object EspnGolfScoreboard {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseScoreSnapshot(text: String, eventId: String): ScoreSnapshot? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val event = (root["events"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.string("id") == eventId }
            ?: return null
        val competition = (event["competitions"] as? JsonArray)
            ?.firstOrNull() as? JsonObject ?: return null
        val status = event["status"] as? JsonObject ?: competition["status"] as? JsonObject
        val statusType = status?.get("type") as? JsonObject
        val state = statusType?.string("state") ?: return null
        val completed = statusType?.string("completed") == "true"
        val competitors = (competition["competitors"] as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
        val ranked = competitors.mapNotNull { competitor ->
            val score = competitor.string("score")?.toGolfScore() ?: return@mapNotNull null
            val rounds = (competitor["linescores"] as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty()
            val currentRound = rounds.maxByOrNull { it.int("period") ?: 0 }
            GolfCompetitor(
                order = competitor.int("order"),
                score = score,
                round = currentRound?.int("period"),
                holesCompleted = (currentRound?.get("linescores") as? JsonArray)?.size,
            )
        }.sortedWith(compareBy<GolfCompetitor> { it.order ?: Int.MAX_VALUE }.thenBy { it.score })
        val leader = ranked.firstOrNull()
        val bestScore = leader?.score
        val detail = listOfNotNull(
            statusType?.string("name"), statusType?.string("description"),
            statusType?.string("detail"),
        ).joinToString(" ").lowercase()
        return ScoreSnapshot(
            leagueId = "golf",
            statusState = state,
            completed = completed,
            homeScore = null,
            awayScore = null,
            statusName = statusType?.string("name"),
            statusDetail = statusType?.string("detail") ?: statusType?.string("description"),
            currentRound = ranked.mapNotNull { it.round }.maxOrNull(),
            leadersStarted = leader?.holesCompleted?.let { it > 0 },
            leaderHolesCompleted = leader?.holesCompleted,
            tiedForLead = bestScore?.let { score -> ranked.count { it.score == score } > 1 },
            contenderWithinTwo = bestScore?.let { score ->
                ranked.drop(1).any { it.score <= score + 2.0 }
            },
            playoff = "playoff" in detail,
            weatherDelay = "weather" in detail || "suspend" in detail || "delay" in detail,
        )
    }

    private data class GolfCompetitor(
        val order: Int?,
        val score: Double,
        val round: Int?,
        val holesCompleted: Int?,
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
    private fun String.toGolfScore(): Double? = if (equals("E", ignoreCase = true)) 0.0
        else toDoubleOrNull()
}
