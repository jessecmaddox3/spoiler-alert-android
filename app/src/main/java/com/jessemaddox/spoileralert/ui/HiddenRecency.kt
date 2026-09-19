package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.ShieldCodec

data class HiddenSessionSections(val recent: List<SessionGroup>, val older: List<SessionGroup>)

/** Metadata-only presentation policy. Age changes prominence, never reveal or retention state. */
object HiddenRecency {
    private const val RECENT_MILLIS = 7 * 24 * 60 * 60 * 1000L

    fun isHiding(shield: ShieldEntity?, nowMillis: Long): Boolean =
        shield?.armed == true && (ShieldCodec.expiresAtMillis(shield) ?: Long.MIN_VALUE) > nowMillis

    fun recentEndedShields(
        shields: List<ShieldEntity>, lastHiddenAtByShield: Map<Long, Long>, nowMillis: Long,
    ): List<ShieldEntity> = shields.filter {
        !isHiding(it, nowMillis) && isRecent(lastHiddenAtByShield[it.id], nowMillis)
    }.sortedByDescending { lastHiddenAtByShield[it.id] }

    fun split(
        sessions: List<SessionGroup>, shieldsById: Map<Long, ShieldEntity>, nowMillis: Long,
    ): HiddenSessionSections {
        // Keep each shield intact: reveal-all is scoped to the whole shield, not a date slice.
        val (recent, older) = sessions.partition {
            isHiding(shieldsById[it.shieldId], nowMillis) || isRecent(it.lastPostedAt, nowMillis)
        }
        return HiddenSessionSections(
            recent.sortedWith(compareByDescending<SessionGroup> {
                isHiding(shieldsById[it.shieldId], nowMillis)
            }.thenByDescending { it.lastPostedAt }),
            older.sortedByDescending { it.lastPostedAt },
        )
    }

    private fun isRecent(lastHiddenAtMillis: Long?, nowMillis: Long): Boolean =
        lastHiddenAtMillis != null && lastHiddenAtMillis >= nowMillis - RECENT_MILLIS
}
