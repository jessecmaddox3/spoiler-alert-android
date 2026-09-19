package com.jessemaddox.spoileralert.schedule

import kotlin.math.abs

/** Fixed questions the app can answer from a public scoreboard without exposing the score. */
enum class ScoreQuestion {
    STARTED, PROGRESS, ANY_SCORE, TOTAL_GOALS, TIED, CLOSE, BLOWOUT, OVER,
    CURRENT_ROUND, LEADERS_STARTED, BACK_NINE, TIE_FOR_LEAD, WITHIN_TWO,
    PLAYOFF, WEATHER_DELAY,
}

/** Intentionally coarse result. No score, leader, or scoring sequence crosses this boundary. */
enum class ScoreAnswer { YES, NO, UNAVAILABLE }

/** The only live scoreboard fields needed to answer fixed questions. Never persisted. */
data class ScoreSnapshot(
    val leagueId: String,
    val statusState: String,
    val completed: Boolean,
    val homeScore: Int?,
    val awayScore: Int?,
    /** Public game clock/period only. These can describe progress without revealing performance. */
    val displayClock: String? = null,
    val period: Int? = null,
    val statusName: String? = null,
    val statusDetail: String? = null,
    val currentRound: Int? = null,
    val leadersStarted: Boolean? = null,
    val leaderHolesCompleted: Int? = null,
    val tiedForLead: Boolean? = null,
    val contenderWithinTwo: Boolean? = null,
    val playoff: Boolean? = null,
    val weatherDelay: Boolean? = null,
)

object SpoilerFreeScore {
    fun answer(game: LeagueGame, question: ScoreQuestion): ScoreAnswer = answer(
        ScoreSnapshot(
            game.leagueId, game.statusState, game.completed, game.homeScore, game.awayScore,
        ),
        question,
    )

    fun answer(snapshot: ScoreSnapshot, question: ScoreQuestion): ScoreAnswer {
        if (question == ScoreQuestion.STARTED) {
            return if (snapshot.statusState == "pre") ScoreAnswer.NO else ScoreAnswer.YES
        }
        if (question == ScoreQuestion.PROGRESS) {
            return if (progress(snapshot) != null) ScoreAnswer.YES else ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.TOTAL_GOALS) {
            return if (totalGoals(snapshot) != null) ScoreAnswer.YES else ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.CURRENT_ROUND) {
            return if (snapshot.currentRound != null) ScoreAnswer.YES else ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.LEADERS_STARTED) {
            return snapshot.leadersStarted?.let(::yesNo) ?: ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.BACK_NINE) {
            return snapshot.leaderHolesCompleted?.let { yesNo(it >= 9) } ?: ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.TIE_FOR_LEAD) {
            return snapshot.tiedForLead?.let(::yesNo) ?: ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.WITHIN_TWO) {
            return snapshot.contenderWithinTwo?.let(::yesNo) ?: ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.PLAYOFF) {
            return snapshot.playoff?.let(::yesNo) ?: ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.WEATHER_DELAY) {
            return snapshot.weatherDelay?.let(::yesNo) ?: ScoreAnswer.UNAVAILABLE
        }
        if (question == ScoreQuestion.OVER) {
            return if (snapshot.completed || snapshot.statusState == "post") ScoreAnswer.YES else ScoreAnswer.NO
        }
        if (snapshot.statusState == "pre") return ScoreAnswer.UNAVAILABLE

        val home = snapshot.homeScore ?: return ScoreAnswer.UNAVAILABLE
        val away = snapshot.awayScore ?: return ScoreAnswer.UNAVAILABLE
        return when (question) {
            ScoreQuestion.STARTED, ScoreQuestion.PROGRESS, ScoreQuestion.TOTAL_GOALS,
            ScoreQuestion.CURRENT_ROUND, ScoreQuestion.LEADERS_STARTED,
            ScoreQuestion.BACK_NINE, ScoreQuestion.TIE_FOR_LEAD,
            ScoreQuestion.WITHIN_TWO, ScoreQuestion.PLAYOFF,
            ScoreQuestion.WEATHER_DELAY -> error("handled above")
            ScoreQuestion.ANY_SCORE -> yesNo(home > 0 || away > 0)
            ScoreQuestion.TIED -> yesNo(home == away)
            ScoreQuestion.CLOSE -> {
                val threshold = closeThreshold(snapshot.leagueId) ?: return ScoreAnswer.UNAVAILABLE
                yesNo(abs(home - away) <= threshold)
            }
            ScoreQuestion.BLOWOUT -> {
                val threshold = blowoutThreshold(snapshot.leagueId) ?: return ScoreAnswer.UNAVAILABLE
                yesNo(abs(home - away) >= threshold)
            }
            ScoreQuestion.OVER -> error("handled above")
        }
    }

    /** Spoiler-free point in the game. Raw provider detail is never returned: only validated
     * clock/period fields and a tiny allowlist of non-performance states cross this boundary. */
    fun progress(snapshot: ScoreSnapshot): String? {
        if (snapshot.statusState == "pre") return "Not started yet."
        if (snapshot.completed || snapshot.statusState == "post") return "The game is over."
        if (snapshot.statusName?.contains("HALFTIME", ignoreCase = true) == true) return "Halftime."

        return when (snapshot.leagueId) {
            "nfl", "cfb", "nba", "wnba" -> timedPeriodProgress(snapshot)
            "nhl" -> hockeyProgress(snapshot)
            "epl", "mls", "soccer" -> soccerProgress(snapshot)
            "mlb" -> baseballProgress(snapshot)
            else -> genericProgress(snapshot)
        }
    }

    fun detail(snapshot: ScoreSnapshot, question: ScoreQuestion): String? = when (question) {
        ScoreQuestion.TOTAL_GOALS -> totalGoals(snapshot)?.let { "$it goal${if (it == 1) "" else "s"} in total." }
        ScoreQuestion.PROGRESS -> progress(snapshot)
        ScoreQuestion.CURRENT_ROUND -> snapshot.currentRound?.let { "Round $it." }
        else -> null
    }

    internal fun totalGoals(snapshot: ScoreSnapshot): Int? {
        if (snapshot.leagueId !in setOf("epl", "mls", "soccer") || snapshot.statusState == "pre") return null
        val home = snapshot.homeScore?.takeIf { it >= 0 } ?: return null
        val away = snapshot.awayScore?.takeIf { it >= 0 } ?: return null
        return (home.toLong() + away).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun timedPeriodProgress(snapshot: ScoreSnapshot): String? {
        val period = snapshot.period ?: return null
        val clock = snapshot.displayClock?.takeIf(::safeClock) ?: return null
        val segment = when {
            period in 1..4 -> "the ${ordinal(period)} quarter"
            period == 5 -> "overtime"
            else -> "the ${ordinal(period - 4)} overtime"
        }
        return "$clock left in $segment."
    }

    private fun soccerProgress(snapshot: ScoreSnapshot): String? {
        val clock = SoccerClock.parse(snapshot.period ?: return null, snapshot.displayClock) ?: return null
        val segment = when (snapshot.period) {
            1 -> "First half"
            2 -> "Second half"
            3, 4 -> "Extra time"
            else -> "In progress"
        }
        return if (clock.added > 0) "$segment, ${clock.minute}+${clock.added} minutes."
            else "$segment, ${ordinal(clock.minute)} minute."
    }

    private fun hockeyProgress(snapshot: ScoreSnapshot): String? {
        val period = snapshot.period ?: return null
        val clock = snapshot.displayClock?.takeIf(::safeClock) ?: return null
        val segment = if (period <= 3) "the ${ordinal(period)} period" else "overtime"
        return "$clock left in $segment."
    }

    private fun baseballProgress(snapshot: ScoreSnapshot): String? {
        val detail = snapshot.statusDetail ?: return null
        val match = Regex("^(Top|Middle|Bottom|End) (\\d+)(?:st|nd|rd|th)$").matchEntire(detail)
            ?: return null
        val phase = match.groupValues[1]
        val inning = match.groupValues[2].toIntOrNull() ?: return null
        return when (phase) {
            "Top" -> "Top of the ${ordinal(inning)} inning."
            "Bottom" -> "Bottom of the ${ordinal(inning)} inning."
            "Middle" -> "Middle of the ${ordinal(inning)} inning."
            else -> "End of the ${ordinal(inning)} inning."
        }
    }

    private fun genericProgress(snapshot: ScoreSnapshot): String? {
        val period = snapshot.period ?: return null
        val clock = snapshot.displayClock?.takeIf(::safeClock) ?: return null
        return "Period $period, $clock remaining."
    }

    private fun safeClock(value: String): Boolean = Regex("^\\d{1,2}:\\d{2}$").matches(value)

    private fun ordinal(value: Int): String {
        val suffix = if (value % 100 in 11..13) "th" else when (value % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$value$suffix"
    }

    private fun yesNo(value: Boolean) = if (value) ScoreAnswer.YES else ScoreAnswer.NO

    private fun blowoutThreshold(leagueId: String): Int? = when (leagueId) {
        "nfl", "cfb" -> 17
        "nba", "wnba" -> 20
        "mlb" -> 6
        "nhl" -> 4
        "tgl" -> 5
        "epl", "mls", "soccer" -> 3
        else -> null
    }

    private fun closeThreshold(leagueId: String): Int? = when (leagueId) {
        "nfl", "cfb" -> 8
        "nba", "wnba" -> 5
        "mlb", "nhl", "epl", "mls", "soccer" -> 1
        "tgl" -> 2
        else -> null
    }
}
