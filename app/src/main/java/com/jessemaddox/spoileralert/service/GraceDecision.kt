package com.jessemaddox.spoileralert.service

import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldEntity

/**
 * Pure fire-time decision for [AutoDisarmWorker] (v3 amendment item 1: grace hour). No
 * Android/WorkManager/DB deps — the worker performs the side effects this points to.
 *
 * An unanswered check-in gets one extra hour of interception before the vault seals.
 * [ShieldCodec.expiresAtMillis] includes that hour from the moment the session is armed, and
 * WorkManager is scheduled for the same absolute deadline. A delayed worker can therefore
 * only delay sealing; it can never make interception stop early.
 *
 * The firing is [Outcome.NOOP] when the shield isn't armed, was never armed, or its
 * expiry is still in the future — the last case means an explicit answer intervened since
 * this worker was scheduled (Extend advanced the deadline and armedAtMillis CAS token, or End & reveal
 * disarmed and a stale grace firing raced a later REPLACE). The DAO-level CAS is the actual
 * enforcement for races that land between this decision and the write; this only decides
 * what the worker should ATTEMPT from the state it read.
 */
object GraceDecision {
    enum class Outcome { NOOP, SEAL }

    fun decide(shield: ShieldEntity?, nowMillis: Long): Outcome {
        if (shield == null || !shield.armed) return Outcome.NOOP
        val expiresAt = ShieldCodec.expiresAtMillis(shield) ?: return Outcome.NOOP
        if (expiresAt > nowMillis) return Outcome.NOOP // re-armed/extended since scheduling
        return Outcome.SEAL
    }
}
