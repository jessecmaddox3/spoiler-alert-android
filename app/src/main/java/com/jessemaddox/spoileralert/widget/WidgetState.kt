package com.jessemaddox.spoileralert.widget

import android.content.Context
import com.jessemaddox.spoileralert.data.AppDatabase
import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.GameWindows
import com.jessemaddox.spoileralert.data.ShieldCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import com.jessemaddox.spoileralert.data.isEffective

/**
 * Snapshot the home-screen widget renders from. Built ONLY from public metadata —
 * shields, the cached public game schedule, and counts. Never reads vault content
 * (titles/texts); the widget must never display hidden notification content.
 */
data class WidgetState(
    val hasShields: Boolean = false,
    /** Next game among TEAM shields (cached games only exist for TEAM shields). */
    val nextGame: GameEntity? = null,
    val nextShieldName: String? = null,
    val nextShieldArmed: Boolean = false,
    val armedCount: Int = 0,
    /** Whether ANYTHING is hidden — deliberately not a count: a ticking number on the home
     *  screen is itself a meta-spoiler ("something just happened in the game"). */
    val hasHidden: Boolean = false,
    /** Soonest-expiring armed shield (mirrors the Home hero): name + expiry for the
     *  protected widget card. Public metadata only. */
    val armedName: String? = null,
    val armedExpiresAtMillis: Long? = null,
) {
    companion object {
        /**
         * Live widget state. A Flow (not a one-shot read) so a running Glance session
         * recomposes when the DB changes — a one-shot snapshot captured in provideGlance
         * would go stale the moment the widget's own Arm button fires.
         */
        fun observe(context: Context): Flow<WidgetState> {
            val db = AppDatabase.get(context.applicationContext)
            // Look back so a game in progress (started, not yet marked completed in the
            // cache) still shows on the widget as the current game — see [GameWindows].
            val since = GameWindows.sinceMillis(System.currentTimeMillis())
            return combine(
                db.shieldDao().observeProtectionRecords(),
                db.gameDao().observeUpcoming(since), // ordered by startMillis
                db.vaultDao().observeHiddenCount(),
                flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } },
            ) { records, games, hiddenCount, now ->
                val shields = records.filter { it.shield.kind != "FANTASY" }.map { it.shield.copy(armed = it.isEffective(now)) }
                // First upcoming game whose shield still exists (guards a delete race).
                val next = games.firstNotNullOfOrNull { game ->
                    shields.find { it.id == game.shieldId }?.let { game to it }
                }
                val heroArmed = shields.filter { it.armed }
                    .minByOrNull { ShieldCodec.sessionDeadlineMillis(it) ?: Long.MAX_VALUE }
                WidgetState(
                    hasShields = shields.isNotEmpty(),
                    nextGame = next?.first,
                    nextShieldName = next?.second?.name,
                    nextShieldArmed = next?.second?.armed == true,
                    armedCount = shields.count { it.armed },
                    hasHidden = hiddenCount > 0,
                    armedName = heroArmed?.name,
                    armedExpiresAtMillis = heroArmed?.let { if ((ShieldCodec.sessionDeadlineMillis(it) ?: 0) > now)
                        ShieldCodec.sessionDeadlineMillis(it) else ShieldCodec.expiresAtMillis(it) },
                )
            }
        }
    }
}
