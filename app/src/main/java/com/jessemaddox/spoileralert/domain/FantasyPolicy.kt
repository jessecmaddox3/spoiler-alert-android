package com.jessemaddox.spoileralert.domain

/**
 * Fantasy-app shields (v3 amendment item 3): fantasy sports apps spoil by proxy — a
 * notification that your QB scored gives away the game without ever naming a team. There's no
 * player-name matching (deferred to roadmap); instead, while ANY protection session is active,
 * EVERY notification from a user-designated fantasy app is hidden, package-level, regardless of
 * content. See [com.jessemaddox.spoileralert.service.InterceptorService.handleSbn] for the call
 * site — this check runs BEFORE normal shield keyword matching.
 */
object FantasyPolicy {
    fun shouldHide(
        pkg: String,
        fantasyPackages: Set<String>,
        excludedPackages: Set<String>,
        anySessionActive: Boolean,
    ): Boolean = anySessionActive && pkg in fantasyPackages && pkg !in excludedPackages
}
