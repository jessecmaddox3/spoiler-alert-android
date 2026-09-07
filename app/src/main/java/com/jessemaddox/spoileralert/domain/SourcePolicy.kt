package com.jessemaddox.spoileralert.domain

object SourcePolicy {

    val MESSAGING: Set<String> = setOf(
        "com.google.android.apps.messaging", // Google Messages (SMS/RCS)
        "com.samsung.android.messaging",
        "com.whatsapp",
        "com.facebook.orca",       // Messenger
        "com.groupme.android",
        "org.telegram.messenger",
        "com.discord",
        "com.Slack",
        "org.thoughtcrime.securesms", // Signal
        "com.instagram.android",   // DMs
        "com.snapchat.android",
    )

    /** Reserved for future use (e.g. onboarding badges/suggestions); modeFor does not consult it. */
    val SPORTS_NEWS: Set<String> = setOf(
        "com.espn.score_center",
        "com.fivemobile.thescore",
        "com.bleacherreport.android.teamstream",
        "com.yahoo.mobile.client.android.sportacular",
        "com.cbs.sports.fantasy",
        "com.handmark.sportcaster",     // CBS Sports
        "com.google.android.apps.magazines", // Google News
        "com.twitter.android",
        "com.foxsports.android",
        "air.com.nbcuni.com.nbcsports.liveextra", // NBC Sports
        "com.tour.pgatour",
        "com.gotv.nflgamecenter.us.lite", // NFL
        "com.nbaimd.gametime.nba2011",
        "com.bamnetworks.mobile.android.gameday.atbat", // MLB
        "com.dazn",
        "tv.fubo.mobile",
    )

    val WEATHER: Set<String> = setOf(
        "com.weather.Weather",
        "com.accuweather.android",
        "com.wunderground.android.weather",
        "com.acmeaom.android.myradar",
        "com.grailr.carrotweather",
        "gov.weather.mobile",
        "com.windyty.android",
    )

    /** System UI packages we must never touch. Browsers deliberately NOT system: web push
     *  notifications (e.g. ESPN.com score alerts) arrive under the browser's package. */
    private val SYSTEM_ALLOWED_APPS = setOf("com.android.chrome")

    fun modeFor(packageName: String, excluded: Set<String>): MatchMode = when {
        packageName in excluded -> MatchMode.OFF
        isSystem(packageName) -> MatchMode.OFF
        packageName in MESSAGING -> MatchMode.STRICT
        packageName in WEATHER -> MatchMode.OFF
        else -> MatchMode.AGGRESSIVE
    }

    private fun isSystem(packageName: String): Boolean =
        packageName !in SYSTEM_ALLOWED_APPS &&
            (packageName == "android" || packageName.startsWith("com.android."))
}
