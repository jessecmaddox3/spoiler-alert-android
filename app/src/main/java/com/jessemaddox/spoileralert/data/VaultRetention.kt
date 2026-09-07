package com.jessemaddox.spoileralert.data

/** Single source of truth for the user-facing revealed-history privacy promise. */
object VaultRetention {
    const val DAYS = 365
    const val WINDOW_MS = DAYS * 24L * 60 * 60 * 1000

    fun cutoff(nowMillis: Long): Long = nowMillis - WINDOW_MS
}
