package com.jessemaddox.spoileralert.data

/**
 * Session-scoped lifecycle for GAME shields (v3 amendment item 2). A protected matchup is a
 * one-session shield: once its session ends disarmed with nothing left hidden, it should get
 * out of the way — without deleting vault history out from under the Hidden/History screens.
 *
 * Pure decisions; [ShieldRepository.cleanupGameShield] and Home's shield filter apply them.
 * No schema beyond the shields.gameEventId column: a "spent" shield with revealed vault rows
 * is merely HIDDEN from Home and physically deleted later by the orphan prune, once history
 * retention (or a manual clear) has removed its rows.
 */
object GameShieldLifecycle {

    /**
     * Should a shield appear on Home's list? TEAM/CUSTOM always. GAME shields show while
     * armed, while anything is still hidden (sealed vault needs its reveal path), or while
     * still unused ("Protect later") with the matchup pending. A shield whose session
     * produced vault rows that are all revealed is spent — hidden from Home even if the
     * real-world game hasn't finished. FANTASY is the sentinel shield (v3 amendment item 3,
     * see [com.jessemaddox.spoileralert.data.ShieldRepository.fantasyShieldId]) — it never
     * shows on Home; its vault rows only surface in Hidden, grouped under "Fantasy".
     */
    fun showOnHome(
        kind: String,
        armed: Boolean,
        hiddenCount: Int,
        totalVaultCount: Int,
        hasPendingGame: Boolean,
    ): Boolean {
        if (kind == "FANTASY") return false
        if (kind != "GAME") return true
        if (armed || hiddenCount > 0) return true
        return totalVaultCount == 0 && hasPendingGame
    }

    /**
     * Delete immediately after a reveal? Only when the shield ends disarmed with NO vault
     * rows at all — nothing anywhere still renders it, so it can vanish with its session.
     */
    fun deleteNowAfterReveal(kind: String, armed: Boolean, hiddenCount: Int, totalVaultCount: Int): Boolean =
        kind == "GAME" && !armed && hiddenCount == 0 && totalVaultCount == 0
}
