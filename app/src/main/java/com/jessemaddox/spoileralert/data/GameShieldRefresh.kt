package com.jessemaddox.spoileralert.data

/**
 * Pure diff decision for [ShieldRepository.refreshGameShield] (collated review finding 2):
 * [ShieldRepository.createGameShield] snapshots a GAME shield's name/aliases/start once at
 * protect time and never refreshes them, so a late schedule correction (kickoff moved, a
 * team's alias mapping fixed) leaves the shield stale for its whole life. This decides
 * WHETHER a refresh actually changes anything; the repository does the re-derivation
 * (matching aliases needs the team catalog) and the writes.
 */
object GameShieldRefresh {
    data class Diff(val name: String, val aliasesJson: String, val startMillis: Long, val changed: Boolean)

    /**
     * [currentStartMillis] is the per-shield cached `games` row's startMillis, or null when
     * that row is missing (e.g. a "protect later" reuse dropped it — see finding 3). A
     * missing row always counts as changed so the caller re-provisions it.
     */
    fun diff(
        currentName: String,
        currentAliasesJson: String,
        currentStartMillis: Long?,
        freshName: String,
        freshAliasesJson: String,
        freshStartMillis: Long,
    ): Diff {
        val changed = currentName != freshName ||
            currentAliasesJson != freshAliasesJson ||
            currentStartMillis != freshStartMillis
        return Diff(name = freshName, aliasesJson = freshAliasesJson, startMillis = freshStartMillis, changed = changed)
    }
}
