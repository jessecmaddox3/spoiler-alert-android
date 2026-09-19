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
import java.time.Instant

/** Parses only the public play fields needed by the deterministic historical question engine. */
internal object EspnTimeline {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, leagueId: String): ScoreTimeline? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val competition = ((root["header"] as? JsonObject)?.get("competitions") as? JsonArray)
            ?.firstOrNull() as? JsonObject ?: return null
        val competitors = objects(competition["competitors"]) ?: return null
        if (competitors.size != 2 || listOf("home", "away").any { side ->
            competitors.count { it.string("homeAway") == side } != 1
        }) return null
        val completed = (((competition["status"] as? JsonObject)?.get("type") as? JsonObject)
            ?.primitive("completed")?.booleanOrNull) ?: false
        val soccerLeague = leagueId in setOf("epl", "mls", "soccer")
        val suppliedPlays = when (leagueId) {
            "nfl", "cfb" -> footballPlays(root)
            "epl", "mls", "soccer" -> objects(root["keyEvents"])
            else -> objects(root["plays"])
        } ?: return null
        if (suppliedPlays.isEmpty()) return null
        if (suppliedPlays.any { play -> listOf("scoringPlay", "shootout").any { it in play && play.boolean(it) == null } }) return null
        val (rawPlays, duplicatesAgree) = uniquePlays(suppliedPlays, leagueId)
        val soccer = if (soccerLeague) soccerPoints(rawPlays, competition) else null
        val parsedPoints = soccer?.first ?: rawPlays.mapIndexedNotNull { index, play ->
            standardPoint(play, leagueId, index)
        }.sortedBy { it.position }
        val endpoint = if (soccerLeague) soccerEndpoint(competition, rawPlays, completed) else null
        var points = (parsedPoints + listOfNotNull(endpoint))
            .sortedBy { it.position }
        val (headerHome, headerAway) = headerScores(competition)
        val last = parsedPoints.lastOrNull()
        val scoresAgree = headerHome != null && headerAway != null &&
            (last?.homeScore ?: 0) == headerHome && (last?.awayScore ?: 0) == headerAway
        val complete = duplicatesAgree && scoresAgree && if (soccer != null) {
            soccer.second && endpoint != null &&
                (if (completed) endpoint.latestMillis else endpoint.earliestMillis) >= (last?.latestMillis ?: 0)
        } else {
            parsedPoints.size == rawPlays.size && parsedPoints.all {
                it.homeScore != null && it.homeScore >= 0 && it.awayScore != null && it.awayScore >= 0
            } && parsedPoints.firstOrNull()?.let { it.homeScore == 0 && it.awayScore == 0 } == true &&
                parsedPoints.zipWithNext().all { (before, after) ->
                    after.homeScore!! >= before.homeScore!! && after.awayScore!! >= before.awayScore!! &&
                        ((after.homeScore == before.homeScore && after.awayScore == before.awayScore) || after.scoring)
                } && (!completed || verifiedStandardEnd(leagueId, last))
        }
        // A complete, reconciled goal feed proves the pre-goal kickoff state. This inference
        // belongs here; consumers never substitute zero for missing evidence.
        if (soccerLeague && complete && points.firstOrNull()?.position != 0) {
            points = listOf(ScoreTimelinePoint(0, 1, null, null, 0, 0, false, SoccerClock(1, 0))) + points
        }
        return points.takeIf { it.isNotEmpty() }?.let { ScoreTimeline(leagueId, completed, it, complete) }
    }

    private fun objects(value: JsonElement?): List<JsonObject>? {
        val array = value as? JsonArray ?: return null
        if (array.any { it !is JsonObject }) return null
        return array.map { it as JsonObject }
    }

    private fun footballPlays(root: JsonObject): List<JsonObject>? {
        val drives = root["drives"] as? JsonObject ?: return null
        val result = mutableListOf<JsonObject>()
        if ("previous" in drives) {
            val previous = objects(drives["previous"]) ?: return null
            for (drive in previous) result += objects(drive["plays"]) ?: return null
        }
        if ("current" in drives) {
            val current = drives["current"] as? JsonObject ?: return null
            result += objects(current["plays"]) ?: return null
        }
        return result
    }

    /** Identical repeats are common across current/previous drives; revised facts need a fresh feed. */
    private fun uniquePlays(raw: List<JsonObject>, leagueId: String): Pair<List<JsonObject>, Boolean> {
        val groups = raw.mapIndexed { index, play ->
            val id = play.string("id")?.takeIf { it.isNotBlank() }
            val sequence = play.string("sequenceNumber")?.takeIf { it.isNotBlank() && leagueId != "mlb" }
            val identity = when {
                id != null -> "id" to id
                sequence != null -> "sequence" to sequence
                else -> "row" to index.toString()
            }
            identity to play
        }.groupBy({ it.first }, { it.second })
        val agree = groups.values.all { copies ->
            copies.map { play -> listOf(
                play["period"], play["clock"], play["homeScore"], play["awayScore"],
                play["scoringPlay"], play["shootout"], play.objectValue("team")?.get("id"), play["wallclock"],
                play["type"],
            ) }.distinct().size == 1
        }
        return groups.values.map { it.first() } to agree
    }

    private fun headerScores(competition: JsonObject): Pair<Int?, Int?> {
        val teams = (competition["competitors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        fun score(side: String) = teams.singleOrNull { it.string("homeAway") == side }
            ?.int("score")?.takeIf { it >= 0 }
        return score("home") to score("away")
    }

    private fun verifiedStandardEnd(league: String, last: ScoreTimelinePoint?): Boolean {
        if (last == null) return false
        val regulationPeriods = when (league) {
            "nfl", "cfb", "nba", "wnba" -> 4
            "nhl" -> 3
            // Baseball can finish mid-inning. Header totals alone don't prove final play coverage.
            else -> return false
        }
        return last.period >= regulationPeriods && last.clockSecondsRemaining == 0
    }

    private fun standardPoint(play: JsonObject, leagueId: String, index: Int): ScoreTimelinePoint? {
        val periodObject = play["period"] as? JsonObject ?: return null
        val period = periodObject.int("number")?.takeIf { it in 1..100 } ?: return null
        val home = play.int("homeScore")
        val away = play.int("awayScore")
        val scoring = play.boolean("scoringPlay") ?: false
        if (leagueId == "mlb") {
            if (index >= SpoilerFreeTimeline.BASEBALL_HALF_POSITION) return null
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

        val clockMillis = ((play["clock"] as? JsonObject)?.string("displayValue"))?.toClockMillis()
            ?: return null
        val duration = periodDuration(leagueId, period) ?: return null
        if (clockMillis !in 0..duration * 1_000L) return null
        val elapsedInPeriod = if (leagueId == "nhl") clockMillis else duration * 1_000L - clockMillis
        val elapsedMillis = periodStart(leagueId, period) * 1_000L + elapsedInPeriod
        val position = (elapsedMillis / 1_000).toInt()
        return ScoreTimelinePoint(
            position = position,
            period = period,
            clockSecondsRemaining = ((duration * 1_000L - elapsedInPeriod + 999) / 1_000).toInt(),
            inningHalf = null,
            homeScore = home,
            awayScore = away,
            scoring = scoring,
            earliestMillis = elapsedMillis,
            latestMillis = elapsedMillis,
        )
    }

    private fun soccerPoints(raw: List<JsonObject>, competition: JsonObject): Pair<List<ScoreTimelinePoint>, Boolean> {
        val competitors = (competition["competitors"] as? JsonArray)
            .orEmpty().filterIsInstance<JsonObject>()
        val homeId = competitors.firstOrNull { it.string("homeAway") == "home" }
            ?.objectValue("team")?.string("id")
        val awayId = competitors.firstOrNull { it.string("homeAway") == "away" }
            ?.objectValue("team")?.string("id")
        var home = 0
        var away = 0
        var complete = !homeId.isNullOrBlank() && !awayId.isNullOrBlank() && homeId != awayId
        val timed = raw.mapNotNull { play ->
            val period = play.objectValue("period")?.int("number")
            // ESPN's period-5 shootout entries can have shootout=false. They are not match goals.
            if (period == 5 || play.boolean("shootout") == true) return@mapNotNull null
            val clockObject = play.objectValue("clock")
            val clock = period?.let { SoccerClock.provider(it, clockObject?.string("displayValue"),
                clockObject?.string("value")) }
            if (clock == null) { complete = false; return@mapNotNull null }
            play to clock
        }.sortedBy { it.second.earliestMillis }
        val points = timed.mapNotNull { (play, evidence) ->
            val clock = evidence.clock
            val scoring = play.boolean("scoringPlay") == true
            // Final markers establish endpoint coverage below. They are not earlier score
            // snapshots at the start of a coarse minute containing a later precise goal.
            if (play.objectValue("type")?.string("type") in setOf("end-regular-time", "end-extra-time")) {
                if (scoring) complete = false
                return@mapNotNull null
            }
            if (scoring) {
                val teamId = play.objectValue("team")?.string("id")
                when {
                    !homeId.isNullOrBlank() && teamId == homeId -> home += 1
                    !awayId.isNullOrBlank() && teamId == awayId -> away += 1
                    else -> { complete = false; return@mapNotNull null }
                }
            }
            val wallclock = play.string("wallclock")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ScoreTimelinePoint(clock.position, clock.period, null, null, home, away, scoring, clock, wallclock,
                evidence.earliestMillis, evidence.latestMillis)
        }
        return points to complete
    }

    /** A live header or a verified final phase marker establishes coverage beyond the last goal. */
    private fun soccerEndpoint(
        competition: JsonObject, plays: List<JsonObject>, completed: Boolean,
    ): ScoreTimelinePoint? {
        val status = competition.objectValue("status") ?: return null
        val evidence = if (!completed) {
            status.int("period")?.let { SoccerClock.provider(it, status.string("displayClock")) }
        } else {
            val statusName = status.objectValue("type")?.string("name").orEmpty()
            val extraTime = plays.any { it.objectValue("period")?.int("number") in 3..4 } ||
                listOf("EXTRA", "AET").any { it in statusName }
            val expectedPeriod = if (extraTime) 4 else 2
            val expectedType = if (extraTime) "end-extra-time" else "end-regular-time"
            plays.filter {
                it.objectValue("period")?.int("number") == expectedPeriod &&
                    it.objectValue("type")?.string("type") == expectedType
            }.mapNotNull {
                val sourceClock = it.objectValue("clock")
                SoccerClock.provider(expectedPeriod, sourceClock?.string("displayValue"),
                    sourceClock?.string("value"))
            }.maxByOrNull { it.earliestMillis }
        } ?: return null
        val clock = evidence.clock
        val (home, away) = headerScores(competition)
        return ScoreTimelinePoint(clock.position, clock.period, null, null, home, away, false, clock,
            earliestMillis = evidence.earliestMillis, latestMillis = evidence.latestMillis, terminal = completed)
    }

    private fun periodStart(leagueId: String, period: Int): Int {
        val regulationPeriods = if (leagueId == "nhl") 3 else 4
        val regulationDuration = periodDuration(leagueId, 1) ?: 0
        if (period <= regulationPeriods) return (period - 1) * regulationDuration
        val overtimeDuration = periodDuration(leagueId, period) ?: regulationDuration
        return regulationPeriods * regulationDuration + (period - regulationPeriods - 1) * overtimeDuration
    }

    private fun periodDuration(leagueId: String, period: Int): Int? = when (leagueId) {
        // Overtime differs by competition/season. Do not invent a clock without that metadata.
        "nfl", "cfb" -> if (period <= 4) 15 * 60 else null
        "nba" -> if (period <= 4) 12 * 60 else 5 * 60
        "wnba" -> if (period <= 4) 10 * 60 else 5 * 60
        "nhl" -> if (period <= 3) 20 * 60 else null
        else -> null
    }

    private fun String.toClockMillis(): Long? {
        Regex("^(\\d{1,2}):([0-5]\\d(?:\\.\\d{1,3})?)$").matchEntire(this)?.let { match ->
            return match.groupValues[1].toLong() * 60_000 +
                (match.groupValues[2].toBigDecimal() * java.math.BigDecimal(1_000)).toLong()
        }
        // More precision than milliseconds cannot safely be rounded into an earlier event.
        val seconds = toBigDecimalOrNull()?.takeIf { it.scale() <= 3 && it >= java.math.BigDecimal.ZERO &&
            it <= java.math.BigDecimal(1_200) } ?: return null
        return (seconds * java.math.BigDecimal(1_000)).toLong()
    }

    private fun JsonObject.objectValue(key: String) = this[key] as? JsonObject
    private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
    private fun JsonObject.string(key: String) = primitive(key)?.contentOrNull
    private fun JsonObject.int(key: String) = primitive(key)?.intOrNull ?: string(key)?.toIntOrNull()
    private fun JsonObject.boolean(key: String) = primitive(key)?.booleanOrNull
}
