package com.jessemaddox.spoileralert.schedule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Parser for ESPN's event-specific public summary endpoint. */
object EspnSummary {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseScoreSnapshot(text: String, leagueId: String): ScoreSnapshot? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val header = root["header"] as? JsonObject ?: return null
        val competition = (header["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return null
        val status = competition["status"] as? JsonObject ?: return null
        val statusType = (status["type"] as? JsonObject)
            ?: return null
        val state = statusType.string("state") ?: return null
        val completed = statusType.string("completed") == "true"
        val competitors = (competition["competitors"] as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
        val golf = if (leagueId == "golf") golfFields(competitors, status, statusType) else GolfFields()
        return ScoreSnapshot(
            leagueId = leagueId,
            statusState = state,
            completed = completed,
            homeScore = competitors.score("home"),
            awayScore = competitors.score("away"),
            displayClock = status.string("displayClock"),
            period = status.string("period")?.toIntOrNull(),
            statusName = statusType.string("name"),
            statusDetail = statusType.string("shortDetail") ?: statusType.string("detail"),
            currentRound = golf.currentRound,
            leadersStarted = golf.leadersStarted,
            leaderHolesCompleted = golf.leaderHolesCompleted,
            tiedForLead = golf.tiedForLead,
            contenderWithinTwo = golf.contenderWithinTwo,
            playoff = golf.playoff,
            weatherDelay = golf.weatherDelay,
        )
    }

    private data class GolfFields(
        val currentRound: Int? = null,
        val leadersStarted: Boolean? = null,
        val leaderHolesCompleted: Int? = null,
        val tiedForLead: Boolean? = null,
        val contenderWithinTwo: Boolean? = null,
        val playoff: Boolean? = null,
        val weatherDelay: Boolean? = null,
    )

    private fun golfFields(
        competitors: List<JsonObject>,
        competitionStatus: JsonObject,
        statusType: JsonObject,
    ): GolfFields {
        val ranked = competitors.mapNotNull { competitor ->
            val score = competitor.string("score")?.toGolfScore() ?: return@mapNotNull null
            val playerStatus = competitor["status"] as? JsonObject
            GolfCompetitor(
                score = score,
                holes = playerStatus?.string("displayThrough")?.let(::holesCompleted),
                started = playerStatus?.let { status ->
                    val state = (status["type"] as? JsonObject)?.string("state")
                    state != "pre" || !status.string("displayThrough").isNullOrBlank()
                },
                round = playerStatus?.string("period")?.toIntOrNull(),
            )
        }.sortedBy { it.score }
        val leader = ranked.firstOrNull()
        val best = leader?.score
        val detail = listOfNotNull(
            statusType.string("name"), statusType.string("shortDetail"), statusType.string("detail"),
        ).joinToString(" ").lowercase()
        return GolfFields(
            currentRound = competitionStatus.string("period")?.toIntOrNull()
                ?: ranked.mapNotNull { it.round }.maxOrNull(),
            leadersStarted = leader?.started,
            leaderHolesCompleted = leader?.holes,
            tiedForLead = best?.let { top -> ranked.count { it.score == top } > 1 },
            contenderWithinTwo = best?.let { top -> ranked.drop(1).any { it.score <= top + 2.0 } },
            playoff = "playoff" in detail,
            weatherDelay = "weather" in detail || "suspend" in detail || "delay" in detail,
        )
    }

    private data class GolfCompetitor(
        val score: Double,
        val holes: Int?,
        val started: Boolean?,
        val round: Int?,
    )

    private fun holesCompleted(value: String): Int? = when {
        value.equals("F", ignoreCase = true) -> 18
        else -> value.filter(Char::isDigit).toIntOrNull()
    }

    private fun List<JsonObject>.score(homeAway: String): Int? =
        firstOrNull { it.string("homeAway") == homeAway }?.string("score")?.toIntOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun String.toGolfScore(): Double? = if (equals("E", ignoreCase = true)) 0.0
        else toDoubleOrNull()
}
