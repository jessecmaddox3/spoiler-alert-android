package com.jessemaddox.spoileralert.service

/**
 * Copy for the "session ended, vault stays sealed" notification (consent rule: an unanswered
 * expiry stops intercepting but NEVER reveals — reveal requires an explicit user action).
 * Pure so the copy logic is unit-testable.
 */
object SessionEndCopy {
    const val TITLE = "Hiding stopped"

    fun text(shieldName: String, sealedCount: Int): String = when (sealedCount) {
        0 -> "$shieldName · no notifications were hidden"
        1 -> "$shieldName · 1 notification is still hidden"
        else -> "$shieldName · $sealedCount notifications are still hidden"
    }
}
