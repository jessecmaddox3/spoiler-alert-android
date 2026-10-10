package com.jessemaddox.spoileralert.data

import com.jessemaddox.spoileralert.ui.SessionDurations

/**
 * Game-time protection: hiding for a scheduled game follows the GAME's clock, never the moment
 * the user opted in. An opted-in shield ([ShieldEntity.autoProtect]) protects each of its cached
 * games from [LEAD_MS] before the listed start until the expected finish (kickoff + typical
 * length + post-game buffer, or a multi-day event's public end). A TEAM shield opts in once for
 * every game; a GAME shield opts in for its one event. Because a window is tied to its game, a
 * game days away is covered exactly like one tonight; no session has to stretch across the wait.
 *
 * Pure decisions. A start worker opens each window on time when Android lets it run, and the
 * listener evaluates the same windows by clock on every notification, so a delayed worker can
 * never leave a gap at kickoff. While a window is open and nothing is hiding for its shield, it
 * starts (again) — which also carries a multi-day event past the 72-hour session cap — unless
 * the user stopped that shield while the window was open ([ShieldEntity.gameTimeStoppedAtMillis]).
 */
object GameTimeProtection {
    /** Start hiding this long before the listed start. Matches the pregame prompt's lead. */
    const val LEAD_MS = 10L * 60 * 1000

    /** A start worker that wakes a moment early still starts; starting a minute early is harmless. */
    const val EARLY_START_TOLERANCE_MS = 2L * 60 * 1000

    private const val HOUR_MS = 3_600_000L

    data class Window(val shieldId: Long, val eventId: String, val startMillis: Long, val endMillis: Long) {
        fun isOpen(nowMillis: Long): Boolean = nowMillis in startMillis until endMillis
    }

    fun window(
        shieldId: Long,
        eventId: String,
        gameStartMillis: Long,
        leagueId: String?,
        publicEndMillis: Long? = null,
    ): Window {
        val expectedEnd = gameStartMillis + (SessionDurations.typicalGameLengthMinutes(leagueId) +
            SessionDurations.POSTGAME_BUFFER_MINUTES) * 60_000L
        return Window(shieldId, eventId, gameStartMillis - LEAD_MS, maxOf(expectedEnd, publicEndMillis ?: expectedEnd))
    }

    /** Did the user stop this shield while [window] was open? Then that game stays stopped. */
    fun stoppedDuring(window: Window, stoppedAtMillis: Long?): Boolean =
        stoppedAtMillis != null && stoppedAtMillis >= window.startMillis - EARLY_START_TOLERANCE_MS &&
            stoppedAtMillis < window.endMillis

    sealed interface Start {
        /** Start (or restart) a session of [hours] so its deadline reaches the window's end. */
        data class Arm(val hours: Int) : Start
        /** A running session already reaches the window's end. */
        data object AlreadyCovered : Start
        data class Skip(val reason: String) : Start
    }

    /**
     * Whether to start protection for [window] now. [currentDeadlineMillis] is the shield's
     * session deadline when its session is still effective, else null. [explicit] is a direct
     * user request ("Start hiding now", a reminder's action, turning protection on mid-game): it
     * may start before the window opens, needs no opt-in, and overrides an earlier stop.
     */
    fun decide(
        shield: ShieldEntity?,
        currentDeadlineMillis: Long?,
        window: Window?,
        nowMillis: Long,
        explicit: Boolean = false,
    ): Start {
        if (shield == null) return Start.Skip("shield deleted")
        if (shield.kind != "TEAM" && shield.kind != "GAME") return Start.Skip("shield kind has no schedule")
        if (window == null) return Start.Skip("game no longer in schedule cache")
        if (nowMillis >= window.endMillis) return Start.Skip("game window is over")
        if (!explicit) {
            if (!shield.autoProtect) return Start.Skip("game-time protection is off")
            if (nowMillis < window.startMillis - EARLY_START_TOLERANCE_MS) return Start.Skip("game window has not opened")
        }
        if (currentDeadlineMillis != null && currentDeadlineMillis >= window.endMillis) return Start.AlreadyCovered
        if (!explicit && stoppedDuring(window, shield.gameTimeStoppedAtMillis)) return Start.Skip("stopped during this game")
        return Start.Arm(hoursToReach(window.endMillis, nowMillis))
    }

    /** Whole hours from [nowMillis] until [endMillis], rounded up; min 1, capped like every session. */
    fun hoursToReach(endMillis: Long, nowMillis: Long): Int =
        ((endMillis - nowMillis + HOUR_MS - 1) / HOUR_MS).toInt().coerceIn(1, SessionDurations.MAX_HOURS)

    /**
     * Windows open at [nowMillis] that need a session: the shield is opted in
     * ([stoppedAtByShield] holds exactly the opted-in shields), nothing is hiding for it, and the
     * user did not stop it during this game. The listener hides for these shields at once.
     */
    fun dueWindows(
        windows: List<Window>,
        stoppedAtByShield: Map<Long, Long?>,
        effectiveShieldIds: Set<Long>,
        nowMillis: Long,
    ): List<Window> = windows.filter {
        it.isOpen(nowMillis) && stoppedAtByShield.containsKey(it.shieldId) &&
            it.shieldId !in effectiveShieldIds && !stoppedDuring(it, stoppedAtByShield[it.shieldId])
    }
}
