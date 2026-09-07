package com.jessemaddox.spoileralert.schedule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Parses only the public play fields needed by the deterministic historical question engine. */
internal object EspnTimeline {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, leagueId: String): ScoreTimeline? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val competition = ((root["header"] as? JsonObject)?.get("competitions") as? JsonArray)
            ?.firstOrNull() as? JsonObject ?: return null
        val completed = (((competition["status"] as? JsonObject)?.get("type") as? JsonObject)
            ?.primitive("completed")?.booleanOrNull) ?: false
        val rawPlays = when (leagueId) {
            "nfl", "cfb" -> footballPlays(root)
            "epl", "mls", "soccer" -> (root["keyEvents"] as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty()
            else -> (root["plays"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        }
        if (rawPlays.isEmpty()) return null

        val parsedPoints = if (leagueId in setOf("epl", "mls", "soccer")) {
            soccerPoints(rawPlays, competition)
        } else {
            rawPlays.mapIndexedNotNull { index, play -> standardPoint(play, leagueId, index) }
        }
        val currentSoccerPoint = if (leagueId in setOf("epl", "mls", "soccer")) {
            currentSoccerPoint(competition)
        } else null
        val points = (parsedPoints + listOfNotNull(currentSoccerPoint))
            .distinctBy { it.position to listOf(it.homeScore, it.awayScore, it.scoring) }
            .sortedBy { it.position }
        return points.takeIf { it.isNotEmpty() }?.let { ScoreTimeline(leagueId, completed, it) }
    }

    private fun footballPlays(root: JsonObject): List<JsonObject> {
        val drives = root["drives"] as? JsonObject ?: return emptyList()
        val previous = (drives["previous"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .flatMap { drive -> (drive["plays"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
        val current = ((drives["current"] as? JsonObject)?.get("plays") as? JsonArray)
            .orEmpty().filterIsInstance<JsonObject>()
        return (previous + current).distinctBy { it.string("id") ?: it.string("sequenceNumber") }
    }

    private fun standardPoint(play: JsonObject, leagueId: String, index: Int): ScoreTimelinePoint? {
        val periodObject = play["period"] as? JsonObject ?: return null
        val period = periodObject.int("number") ?: return null
        val home = play.int("homeScore")
        val away = play.int("awayScore")
        val scoring = play.boolean("scoringPlay") ?: false
        if (leagueId == "mlb") {
            val half = when (periodObject.string("type")?.lowercase()) {
                "top" -> InningHalf.TOP
                "bottom" -> InningHalf.BOTTOM
                else -> return null
            }
            val unit = (period - 1) * 2 + if (half == InningHalf.BOTTOM) 1 else 0
            return ScoreTimelinePoint(
                // MLB sequenceNumber restarts inside at-bats. Array order is the reliable order
                // within the half-inning for this reduced, in-memory timeline.
                position = unit * SpoilerFreeTimeline.BASEBALL_HALF_POSITION + index,
                period = period,
                clockSecondsRemaining = null,
                inningHalf = half,
                homeScore = home,
                awayScore = away,
                scoring = scoring,
            )
        }

        val clock = ((play["clock"] as? JsonObject)?.string("displayValue"))?.toClockSeconds()
            ?: return null
        val duration = periodDuration(leagueId, period) ?: return null
        val elapsedInPeriod = if (leagueId == "nhl") clock else duration - clock
        if (elapsedInPeriod !in 0..duration) return null
        val position = periodStart(leagueId, period) + elapsedInPeriod
        return ScoreTimelinePoint(
            position = position,
            period = period,
            clockSecondsRemaining = if (leagueId == "nhl") duration - clock else clock,
            inningHalf = null,
            homeScore = home,
            awayScore = away,
            scoring = scoring,
        )
    }

    private fun soccerPoints(raw: List<JsonObject>, competition: JsonObject): List<ScoreTimelinePoint> {
        val competitors = (competition["competitors"] as? JsonArray)
            .orEmpty().filterIsInstance<JsonObject>()
        val homeId = competitors.firstOrNull { it.string("homeAway") == "home" }
            ?.objectValue("team")?.string("id")
        val awayId = competitors.firstOrNull { it.string("homeAway") == "away" }
            ?.objectValue("team")?.string("id")
        var home = 0
        var away = 0
        return raw.mapNotNull { play ->
            if (play.boolean("shootout") == true) return@mapNotNull null
            val period = play.objectValue("period")?.int("number") ?: return@mapNotNull null
            val clockObject = play.objectValue("clock") ?: return@mapNotNull null
            val position = clockObject.primitive("value")?.let { primitive ->
                primitive.contentOrNull?.toDoubleOrNull()?.toInt()
            } ?: clockObject.string("displayValue")?.filter(Char::isDigit)?.toIntOrNull()?.times(60)
            ?: return@mapNotNull null
            val scoring = play.boolean("scoringPlay") == true
            if (scoring) {
                when (play.objectValue("team")?.string("id")) {
                    homeId -> home += 1
                    awayId -> away += 1
                    else -> return@mapNotNull null
                }
            }
            ScoreTimelinePoint(position, period, null, null, home, away, scoring)
        }
    }

    /** Key events can be quiet for long stretches. The public header clock safely extends the
     * selectable timeline to the actual live point without exposing either team's score. */
    private fun currentSoccerPoint(competition: JsonObject): ScoreTimelinePoint? {
        val status = competition.objectValue("status") ?: return null
        val displayClock = status.string("displayClock") ?: return null
        val position = displayClock.substringBefore(':').filter(Char::isDigit)
            .toIntOrNull()?.times(60) ?: return null
        val period = status.int("period") ?: return null
        val competitors = (competition["competitors"] as? JsonArray)
            .orEmpty().filterIsInstance<JsonObject>()
        val home = competitors.firstOrNull { it.string("homeAway") == "home" }?.int("score")
        val away = competitors.firstOrNull { it.string("homeAway") == "away" }?.int("score")
        return ScoreTimelinePoint(position, period, null, null, home, away, false)
    }

    private fun periodStart(leagueId: String, period: Int): Int {
        val regulationPeriods = if (leagueId == "nhl") 3 else 4
        val regulationDuration = periodDuration(leagueId, 1) ?: 0
        if (period <= regulationPeriods) return (period - 1) * regulationDuration
        val overtimeDuration = periodDuration(leagueId, period) ?: regulationDuration
        return regulationPeriods * regulationDuration + (period - regulationPeriods - 1) * overtimeDuration
    }

    private fun periodDuration(leagueId: String, period: Int): Int? = when (leagueId) {
        "nfl", "cfb" -> if (period <= 4) 15 * 60 else 10 * 60
        "nba" -> if (period <= 4) 12 * 60 else 5 * 60
        "wnba" -> if (period <= 4) 10 * 60 else 5 * 60
        "nhl" -> if (period <= 3) 20 * 60 else 5 * 60
        else -> null
    }

    private fun String.toClockSeconds(): Int? {
        Regex("^(\\d{1,2}):(\\d{2})(?:\\.\\d+)?$").matchEntire(this)?.let { match ->
            return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        }
        return toDoubleOrNull()?.toInt()
    }

    private fun JsonObject.objectValue(key: String) = this[key] as? JsonObject
    private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
    private fun JsonObject.string(key: String) = primitive(key)?.contentOrNull
    private fun JsonObject.int(key: String) = primitive(key)?.intOrNull ?: string(key)?.toIntOrNull()
    private fun JsonObject.boolean(key: String) = primitive(key)?.booleanOrNull
}
