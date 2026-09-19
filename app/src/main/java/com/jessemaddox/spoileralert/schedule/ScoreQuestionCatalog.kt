package com.jessemaddox.spoileralert.schedule

data class ScoreQuestionSpec(val question: ScoreQuestion, val label: String)

/** Small, sport-specific menus. Unsupported questions never appear as dead controls. */
object ScoreQuestionCatalog {
    fun forLeague(leagueId: String): List<ScoreQuestionSpec> = when (leagueId) {
        "nfl", "cfb" -> specs(
            ScoreQuestion.STARTED to "Has it started?",
            ScoreQuestion.PROGRESS to "Where are we in the game?",
            ScoreQuestion.ANY_SCORE to "Has anyone scored?",
            ScoreQuestion.CLOSE to "Is it within one possession?",
            ScoreQuestion.BLOWOUT to "Is it a blowout?",
            ScoreQuestion.OVER to "Is it over?",
        )
        "nba", "wnba" -> specs(
            ScoreQuestion.STARTED to "Has it started?",
            ScoreQuestion.PROGRESS to "What quarter is it?",
            ScoreQuestion.TIED to "Is it tied?",
            ScoreQuestion.CLOSE to "Is it close?",
            ScoreQuestion.BLOWOUT to "Is it a blowout?",
            ScoreQuestion.OVER to "Is it over?",
        )
        "epl", "mls", "soccer" -> specs(
            ScoreQuestion.TOTAL_GOALS to "How many goals in total?",
            ScoreQuestion.STARTED to "Has it started?",
            ScoreQuestion.PROGRESS to "What point in the match?",
            ScoreQuestion.ANY_SCORE to "Has anyone scored?",
            ScoreQuestion.TIED to "Is it tied?",
            ScoreQuestion.CLOSE to "Is it within one goal?",
            ScoreQuestion.OVER to "Is it over?",
        )
        "nhl" -> specs(
            ScoreQuestion.STARTED to "Has it started?",
            ScoreQuestion.PROGRESS to "What period is it?",
            ScoreQuestion.ANY_SCORE to "Has anyone scored?",
            ScoreQuestion.TIED to "Is it tied?",
            ScoreQuestion.CLOSE to "Is it within one goal?",
            ScoreQuestion.OVER to "Is it over?",
        )
        "mlb" -> specs(
            ScoreQuestion.STARTED to "Has it started?",
            ScoreQuestion.PROGRESS to "What inning is it?",
            ScoreQuestion.ANY_SCORE to "Has anyone scored?",
            ScoreQuestion.TIED to "Is it tied?",
            ScoreQuestion.CLOSE to "Is it within one run?",
            ScoreQuestion.BLOWOUT to "Is it a blowout?",
            ScoreQuestion.OVER to "Is it over?",
        )
        "golf" -> specs(
            ScoreQuestion.STARTED to "Has the tournament started?",
            ScoreQuestion.CURRENT_ROUND to "What round is it?",
            ScoreQuestion.LEADERS_STARTED to "Have the leaders teed off?",
            ScoreQuestion.BACK_NINE to "Are the leaders on the back nine?",
            ScoreQuestion.TIE_FOR_LEAD to "Is there a tie for the lead?",
            ScoreQuestion.WITHIN_TWO to "Is anyone else within two shots?",
            ScoreQuestion.PLAYOFF to "Is it in a playoff?",
            ScoreQuestion.WEATHER_DELAY to "Is play delayed by weather?",
            ScoreQuestion.OVER to "Is the event over?",
        )
        "tgl" -> specs(
            ScoreQuestion.STARTED to "Has it started?",
            ScoreQuestion.TIED to "Is it tied?",
            ScoreQuestion.CLOSE to "Is it close?",
            ScoreQuestion.BLOWOUT to "Is it a blowout?",
            ScoreQuestion.OVER to "Is it over?",
        )
        "f1" -> specs(
            ScoreQuestion.STARTED to "Has the race started?",
            ScoreQuestion.OVER to "Is the race over?",
        )
        else -> specs(
            ScoreQuestion.STARTED to "Has it started?",
            ScoreQuestion.PROGRESS to "Where are we?",
            ScoreQuestion.ANY_SCORE to "Has anyone scored?",
            ScoreQuestion.TIED to "Is it tied?",
            ScoreQuestion.BLOWOUT to "Is it a blowout?",
            ScoreQuestion.OVER to "Is it over?",
        )
    }

    private fun specs(vararg entries: Pair<ScoreQuestion, String>) =
        entries.map { ScoreQuestionSpec(it.first, it.second) }
}
