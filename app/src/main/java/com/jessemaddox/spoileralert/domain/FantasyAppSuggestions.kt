package com.jessemaddox.spoileralert.domain

/**
 * Fantasy-app picker (v3 amendment item 3): known fantasy-sports package names bubble to the
 * top of the installed-app list when present, purely as a sorting convenience — the user still
 * selects explicitly, nothing is pre-checked. Package names are best-effort; an unmatched app
 * simply sorts alphabetically like everything else, so a stale/wrong guess here is harmless.
 */
object FantasyAppSuggestions {
    val KNOWN: Set<String> = setOf(
        "com.yahoo.mobile.client.android.fantasyfootball",
        "com.espn.fantasy.lm.football",
        "com.sleeperbot",
        "com.draftkings.dfs.app",
        "com.fanduel.fantasy",
    )

    /** Known packages first (alphabetical among themselves), then everything else
     *  alphabetically by label. Generic over the caller's row type so this stays testable
     *  without an Android [android.content.pm.PackageManager] dependency. */
    fun <T> sorted(apps: List<T>, packageOf: (T) -> String, labelOf: (T) -> String): List<T> =
        apps.sortedWith(
            compareByDescending<T> { packageOf(it) in KNOWN }.thenBy { labelOf(it).lowercase() }
        )
}
