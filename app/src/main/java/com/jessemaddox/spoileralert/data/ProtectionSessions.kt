package com.jessemaddox.spoileralert.data

import androidx.room.withTransaction
import com.jessemaddox.spoileralert.domain.ArmedShield
import com.jessemaddox.spoileralert.domain.FantasyPolicy
import com.jessemaddox.spoileralert.domain.MatchMode
import com.jessemaddox.spoileralert.domain.Matcher
import com.jessemaddox.spoileralert.domain.SourcePolicy
import com.jessemaddox.spoileralert.domain.ConversationIdentity
import com.jessemaddox.spoileralert.domain.EventIdentity
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

data class SessionToken(val shieldId: Long, val sessionId: String, val revision: Long) {
    fun matches(shield: ShieldEntity): Boolean = shield.id == shieldId &&
        shield.currentSessionId == sessionId && shield.sessionRevision == revision
    val scope: String get() = "$sessionId:$revision"

    companion object {
        fun from(shield: ShieldEntity): SessionToken? = shield.currentSessionId?.let {
            SessionToken(shield.id, it, shield.sessionRevision)
        }
    }
}

/** Captured from the listener's immutable cache before cancellation, never looked up afterward. */
data class CaptureContext(
    val sessionId: String,
    val shieldId: Long,
    val shieldKind: String,
    val displayName: String,
    val matchMode: String,
    val capturedAtMillis: Long,
)

data class ActiveProtection(
    val token: SessionToken,
    val shield: ArmedShield,
    val kind: String,
    val expiresAtMillis: Long,
) {
    fun capture(mode: MatchMode, now: Long) = CaptureContext(
        token.sessionId, token.shieldId, kind, shield.name, mode.name, now,
    )
}

data class ProtectionSnapshot(
    val protections: List<ActiveProtection>,
    val fantasy: CaptureContext?,
)

data class ProtectionPolicy(
    val excludedPackages: Set<String>,
    val fantasyPackages: Set<String>,
    val ownPackage: String,
)

/** Both sets are captured together when an action is offered; later epochs cannot join it. */
data class RevealSelection(val sessionIds: Set<String>, val fantasySessionIds: Set<String> = emptySet())

data class ProtectionUiSnapshot(
    val shields: List<ShieldEntity> = emptyList(),
    val actions: Map<Long, RevealSelection> = emptyMap(),
)

/** Consent requirements only grow. Content deletion never removes this metadata. */
object ConsentGuards {
    private val serializer = ListSerializer(String.serializer())
    fun encode(ids: Set<String>): String = Json.encodeToString(serializer, ids.sorted())
    fun forBinding(binding: CaptureOwnership): Set<String> {
        val parsed = runCatching { Json.decodeFromString(serializer, binding.consentSessionIdsJson).toSet() }
            .getOrNull()?.takeIf { it.isNotEmpty() && it.all(String::isNotBlank) &&
                binding.captureSessionId in it && binding.ownerSessionId in it }
        // A corrupted metadata record creates sealed recovery authority, never an empty all-true check.
        return parsed ?: setOf(binding.captureSessionId, binding.ownerSessionId,
            "recovery:" + captureIdentityHash(binding.captureSessionId, "consent", binding.notificationKeyHash))
    }
}

fun captureIdentityHash(session: String, kind: String, value: String): String =
    MessageDigest.getInstance("SHA-256").digest("$session\u0000$kind\u0000$value".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun captureConversationHash(session: String, conversation: String): String =
    captureIdentityHash(session, "conversation", ConversationIdentity.canonical(conversation))

/** Identical matching input on first capture, release and late persistence. */
fun notificationMatchText(title: String, conversation: String?, body: String): String =
    listOfNotNull(title, conversation, body).joinToString("\n")

fun ShieldWithSession.isEffective(now: Long): Boolean = shield.kind != "FANTASY" && shield.armed &&
    session != null && session.endedAtMillis == null && shield.currentSessionId == session.sessionId &&
    (ShieldCodec.expiresAtMillis(shield) ?: Long.MIN_VALUE) > now

fun List<ShieldWithSession>.protectionSnapshot(now: Long): ProtectionSnapshot {
    val active = filter { it.isEffective(now) }.mapNotNull { row ->
        runCatching { ActiveProtection(SessionToken.from(row.shield)!!,
            ShieldCodec.toArmedShield(row.shield), row.shield.kind, ShieldCodec.expiresAtMillis(row.shield)!!) }
            .getOrNull()
    }
    val fantasy = firstOrNull { it.shield.kind == "FANTASY" && it.session?.endedAtMillis == null && it.session != null }
        ?.let { CaptureContext(it.session!!.sessionId, it.shield.id, "FANTASY", it.shield.name, "LEGACY", now) }
    return ProtectionSnapshot(active, fantasy)
}

/** Database-only lifecycle. Android work/notification effects happen after the transaction. */
class ProtectionSessions(
    private val db: AppDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val shields get() = db.shieldDao()
    private val sessions get() = db.protectionSessionDao()
    private val vault get() = db.vaultDao()
    private val ownership get() = db.captureOwnershipDao()

    data class Transition(val previous: SessionToken?, val current: ShieldEntity)
    data class Expired(val token: SessionToken, val shield: ShieldEntity, val hidden: List<VaultEntity>)
    data class Release(
        val ended: List<SessionToken>,
        val released: List<VaultEntity>,
        val retained: List<VaultEntity>,
    )
    data class Persisted(val item: VaultEntity, val inserted: Boolean)

    /** Migration may leave an active real session without a fantasy epoch. Repair before caching. */
    suspend fun initialize() = db.withTransaction {
        val now = nowMillis()
        maintainFantasy(now, startNew = false)
    }

    suspend fun snapshot(): ProtectionSnapshot = db.withTransaction {
        shields.protectionRecords().protectionSnapshot(nowMillis())
    }

    /** Displayed shields and the action scopes offered beside them come from one transaction. */
    suspend fun uiSnapshot(now: Long): ProtectionUiSnapshot = db.withTransaction {
        val records = shields.protectionRecords()
        val stored = sessions.all()
        val items = vault.allHidden()
        val known = records.mapTo(mutableSetOf()) { it.shield.id }
        val orphans = (items.map { it.shieldId } + stored.map { it.shieldId }).distinct().filter { it !in known }.map { id ->
            ShieldEntity(id = id, name = stored.lastOrNull { it.shieldId == id }?.displayName ?: "Earlier protection",
                aliasesJson = "[]", kind = "ARCHIVED")
        }
        val displayed = records.map { it.shield.copy(armed = it.isEffective(now)) } + orphans
        val fantasy = stored.filter { it.shieldKind == "FANTASY" }.mapTo(mutableSetOf()) { it.sessionId }
        ProtectionUiSnapshot(displayed, displayed.associate { shield -> shield.id to RevealSelection(
            (stored.filter { it.shieldId == shield.id }.map { it.sessionId } +
                items.filter { it.shieldId == shield.id }.mapNotNull { it.ownerSessionId }).toSet(), fantasy,
        ) })
    }

    /** Explicit developer control, scoped like an extension and never revives an ended session. */
    suspend fun adjustDeadlineForDebug(token: SessionToken, armedAt: Long): Transition? = db.withTransaction {
        val old = shields.byId(token.shieldId)?.takeIf(token::matches) ?: return@withTransaction null
        if (!old.armed || sessions.byId(token.sessionId)?.endedAtMillis != null) return@withTransaction null
        val current = old.copy(armedAtMillis = armedAt, sessionRevision = old.sessionRevision + 1)
        shields.update(current)
        Transition(token, current)
    }

    suspend fun arm(shieldId: Long, hours: Int? = null): Transition? = db.withTransaction {
        val old = shields.byId(shieldId)?.takeUnless { it.kind == "FANTASY" } ?: return@withTransaction null
        val now = nowMillis()
        val hadCoverage = active(now).isNotEmpty()
        old.currentSessionId?.let { sessions.end(it, now, "REARMED") }
        val id = newId()
        sessions.insert(ProtectionSessionEntity(id, old.id, old.kind, old.name, now))
        val current = old.copy(armed = true, armedAtMillis = now,
            autoDisarmHours = hours?.coerceIn(1, 72) ?: old.autoDisarmHours,
            currentSessionId = id, sessionRevision = 0)
        shields.update(current)
        maintainFantasy(now, startNew = !hadCoverage)
        Transition(SessionToken.from(old), current)
    }

    suspend fun extend(token: SessionToken): Transition? = db.withTransaction {
        val old = shields.byId(token.shieldId)?.takeIf(token::matches) ?: return@withTransaction null
        val now = nowMillis()
        val record = sessions.byId(token.sessionId) ?: return@withTransaction null
        if (!ShieldWithSession(old, record).isEffective(now)) return@withTransaction null
        val plan = SessionExtension.plan(old, now) ?: return@withTransaction null
        val current = old.copy(armedAtMillis = plan.newArmedAtMillis, sessionRevision = old.sessionRevision + 1)
        shields.update(current)
        Transition(token, current)
    }

    suspend fun expire(token: SessionToken): Expired? = db.withTransaction {
        val old = shields.byId(token.shieldId)?.takeIf { it.armed && token.matches(it) }
            ?: return@withTransaction null
        val now = nowMillis()
        val end = ShieldCodec.expiresAtMillis(old) ?: Long.MIN_VALUE
        if (end > now) return@withTransaction null
        sessions.end(token.sessionId, now, "EXPIRED")
        shields.update(old.copy(armed = false, armedAtMillis = null))
        maintainFantasy(now, startNew = false)
        Expired(token, old, vault.hiddenOwnedBy(listOf(token.sessionId)))
    }

    /** Automatic teardown closes a known generation without authorizing any content reveal. */
    suspend fun close(token: SessionToken, reason: String): Boolean = db.withTransaction {
        val old = shields.byId(token.shieldId)?.takeIf(token::matches) ?: return@withTransaction false
        val now = nowMillis()
        sessions.end(token.sessionId, now, reason)
        shields.update(old.copy(armed = false, armedAtMillis = null))
        maintainFantasy(now, startNew = false)
        true
    }

    /** Caller captures these IDs when presenting the action; later sessions are never included. */
    suspend fun revealScope(shieldId: Long): RevealSelection = db.withTransaction {
        RevealSelection(
            (sessions.forShield(shieldId).map { it.sessionId } + vault.hiddenOwnerSessions(shieldId)).toSet(),
            sessions.fantasySessions().mapTo(mutableSetOf()) { it.sessionId },
        )
    }

    suspend fun reveal(
        selection: RevealSelection,
        policy: ProtectionPolicy,
        deleteShieldId: Long? = null,
        expectedCurrentSessionId: String? = null,
    ): Release = db.withTransaction {
        val now = nowMillis()
        val selected = selection.sessionIds.mapNotNull { sessions.byId(it) }.toMutableList()
        val ended = mutableListOf<SessionToken>()
        for (session in selected) {
            sessions.end(session.sessionId, now, "EXPLICIT_REVEAL")
            val shield = shields.byId(session.shieldId)
            if (shield?.currentSessionId == session.sessionId && shield.armed) {
                SessionToken.from(shield)?.let(ended::add)
                shields.update(shield.copy(armed = false, armedAtMillis = null))
            }
        }
        val remaining = active(now)
        // The final explicit group release includes existing fantasy epochs, never future ones.
        if (selected.any { it.shieldKind != "FANTASY" } && remaining.isEmpty()) {
            val already = selected.mapTo(mutableSetOf()) { it.sessionId }
            selection.fantasySessionIds.mapNotNull { sessions.byId(it) }
                .filter { it.shieldKind == "FANTASY" && it.sessionId !in already }.forEach {
                sessions.end(it.sessionId, now, "EXPLICIT_REVEAL")
                selected += it
            }
        }
        val ids = selected.map { it.sessionId }
        sessions.authorizeReveal(ids, now)
        maintainFantasy(now, startNew = false)
        val released = mutableListOf<VaultEntity>()
        val retained = mutableListOf<VaultEntity>()
        for (item in vault.hiddenOwnedBy(ids)) {
            val owner = matchingOwner(item, remaining, policy, now)
            val updated = if (owner != null) item.copy(shieldId = owner.shieldId, ownerSessionId = owner.sessionId)
                else item.copy(revealedAtMillis = now)
            vault.update(updated)
            recordOwnership(updated)
            if (owner == null) released += updated else retained += updated
        }
        if (deleteShieldId != null) {
            val shield = shields.byId(deleteShieldId)
            if (shield != null && shield.currentSessionId == expectedCurrentSessionId && !shield.armed) {
                shields.delete(shield.id)
                db.gameDao().deleteForShield(shield.id)
            }
        }
        Release(ended, released, retained)
    }

    suspend fun persist(capture: CaptureContext, payload: VaultEntity, policy: ProtectionPolicy): Persisted =
        db.withTransaction {
            val now = nowMillis()
            // A missing identity is a sealed recovery record, never the shield's latest session.
            if (sessions.byId(capture.sessionId) == null) {
                sessions.insert(ProtectionSessionEntity(capture.sessionId, capture.shieldId, capture.shieldKind,
                    capture.displayName, capture.capturedAtMillis, now, "RECOVERED"))
            }
            val prior = vault.latestForCapture(capture.sessionId, payload.sourcePackage, payload.notificationKey)
                ?: payload.conversation?.takeIf { payload.isConversation && it.isNotBlank() }?.let {
                    vault.latestConversationForCapture(capture.sessionId, payload.sourcePackage, it)
                }
            // Revealed rows still retain transfer provenance. Their row-level reveal is NOT consent
            // for future content and new updates never append to an already-revealed row.
            val keyHash = captureIdentityHash(capture.sessionId, "key", payload.notificationKey)
            val conversationHash = payload.conversation?.takeIf { payload.isConversation && it.isNotBlank() }
                ?.let { captureConversationHash(capture.sessionId, it) }
            val (_, bindings) = relatedBindings(capture.sessionId, payload.sourcePackage, keyHash, conversationHash)
            val binding = bindings.firstOrNull()
            val guards = bindings
                .flatMapTo(mutableSetOf()) { ConsentGuards.forBinding(it) }
            guards += capture.sessionId
            prior?.ownerSessionId?.let(guards::add)
            val guardRecords = guards.map { id ->
                sessions.byId(id) ?: ProtectionSessionEntity(id, capture.shieldId, capture.shieldKind,
                    capture.displayName, capture.capturedAtMillis, now, "RECOVERED").also { sessions.insert(it) }
            }
            // The routing tombstone advances even if an older revealed content row remains.
            val authorityId = binding?.ownerSessionId ?: prior?.ownerSessionId ?: capture.sessionId
            val authority = sessions.byId(authorityId)
            val incoming = payload.copy(captureSessionId = capture.sessionId,
                ownerSessionId = authorityId, captureMatchMode = capture.matchMode)
            val active = active(now)
            val protectedOwner = matchingOwner(incoming, active, policy, now)
            val unresolved = guardRecords.filter { it.revealAuthorizedAtMillis == null }
            val owner = protectedOwner ?: unresolved.firstOrNull { it.sessionId == authorityId }
                ?: unresolved.firstOrNull() ?: authority ?: sessions.byId(capture.sessionId)!!
            val reveal = protectedOwner == null && unresolved.isEmpty() && owner.revealAuthorizedAtMillis != null
            // An incoming payload's match is not proof that another owner's older text is covered.
            val hidden = prior?.takeIf { it.revealedAtMillis == null && it.ownerSessionId == owner.sessionId }
            val merge = hidden != null && payload.isConversation && hidden.isConversation &&
                hidden.conversation == payload.conversation
            val item = incoming.copy(
                id = hidden?.id ?: 0,
                shieldId = owner.shieldId,
                ownerSessionId = owner.sessionId,
                text = if (merge) ConversationUpdates.mergeText(hidden!!.text, payload.text,
                    hidden.messageCount, payload.messageCount) else payload.text,
                messageCount = if (merge) ConversationUpdates.mergedMessageCount(hidden!!, payload.messageCount,
                    payload.text) else payload.messageCount,
                revealedAtMillis = if (reveal) now else null,
            )
            recordOwnership(item, prior?.ownerSessionId)
            if (hidden != null) {
                vault.update(item)
                Persisted(item, false)
            } else {
                val id = vault.insert(item)
                Persisted(item.copy(id = id), true)
            }
        }

    /** Repair equivalent event attribution while preserving the original capture identity. */
    suspend fun reconcileEquivalentAttribution(targetShieldId: Long? = null) = db.withTransaction {
        val all = shields.all().associateBy { it.id }
        val targets = active(nowMillis()).filter { targetShieldId == null || it.shield.id == targetShieldId }
            .sortedWith(compareByDescending<ShieldWithSession> { it.shield.kind == "GAME" }
                .thenByDescending { it.shield.armedAtMillis ?: Long.MIN_VALUE })
        for (item in vault.allHidden()) {
            val sourceName = all[item.shieldId]?.name
                ?: item.ownerSessionId?.let { sessions.byId(it)?.displayName } ?: continue
            val target = targets.firstOrNull {
                EventIdentity.canonical(it.shield.name).key == EventIdentity.canonical(sourceName).key
            } ?: continue
            if (target.shield.id == item.shieldId && target.session!!.sessionId == item.ownerSessionId) continue
            val updated = item.copy(shieldId = target.shield.id, ownerSessionId = target.session!!.sessionId)
            vault.update(updated)
            recordOwnership(updated)
        }
    }

    /** A known key joins old/new chat aliases conservatively, before any reveal decision.
     * All references point directly to one group; merging never changes stored content/owners. */
    private suspend fun relatedBindings(capture: String, source: String, key: String, incoming: String?):
        Pair<String?, List<CaptureOwnership>> {
        val keyed = ownership.byKey(capture, source, key)
        val incomingGroup = incoming?.let { ownership.alias(capture, source, it)?.conversationHash ?: it }
        val groups = listOfNotNull(keyed?.conversationHash, incomingGroup).distinct()
        val group = groups.firstOrNull()
        val bindings = (listOfNotNull(keyed) + groups.flatMap {
            ownership.allForConversation(capture, source, it)
        }).distinctBy { it.notificationKeyHash }
        if (group != null) {
            val guards = bindings.flatMapTo(mutableSetOf(capture)) { ConsentGuards.forBinding(it) }
            val encoded = ConsentGuards.encode(guards)
            for (old in groups) {
                ownership.mergeConversation(capture, source, old, group, encoded)
                ownership.mergeAliases(capture, source, old, group)
                ownership.rememberAlias(CaptureConversationAlias(capture, source, old, group))
            }
            incoming?.let { ownership.rememberAlias(CaptureConversationAlias(capture, source, it, group)) }
        }
        return group to bindings
    }

    /** Must share the same Room transaction as a vault transfer or persistence operation. */
    private suspend fun recordOwnership(item: VaultEntity, previousOwner: String? = null) {
        val capture = item.captureSessionId ?: return
        val owner = item.ownerSessionId ?: return
        val incomingConversation = item.conversation?.takeIf { item.isConversation && it.isNotBlank() }
            ?.let { captureConversationHash(capture, it) }
        val key = captureIdentityHash(capture, "key", item.notificationKey)
        val (conversation, existing) = relatedBindings(capture, item.sourcePackage, key, incomingConversation)
        val guards = existing.flatMapTo(mutableSetOf()) { ConsentGuards.forBinding(it) }
        guards += capture
        guards += owner
        previousOwner?.let(guards::add)
        val encoded = ConsentGuards.encode(guards)
        if (conversation != null) ownership.reassignConversation(capture, item.sourcePackage, conversation, owner, encoded)
        ownership.put(CaptureOwnership(capture, item.sourcePackage, key, conversation, owner, encoded))
    }

    private suspend fun active(now: Long) = shields.protectionRecords().filter { it.isEffective(now) }

    private suspend fun matchingOwner(
        item: VaultEntity, active: List<ShieldWithSession>, policy: ProtectionPolicy, now: Long,
    ): ProtectionSessionEntity? {
        if (item.sourcePackage != policy.ownPackage && FantasyPolicy.shouldHide(item.sourcePackage,
                policy.fantasyPackages, policy.excludedPackages, active.isNotEmpty())) {
            return maintainFantasy(now, startNew = false)
        }
        if (item.sourcePackage in policy.excludedPackages) return null
        val mode = runCatching { MatchMode.valueOf(item.captureMatchMode) }.getOrElse {
            SourcePolicy.modeFor(item.sourcePackage, policy.excludedPackages)
        }
        val ordered = active.sortedWith(compareByDescending<ShieldWithSession> {
            it.session?.sessionId == item.ownerSessionId
        }.thenBy { it.shield.id })
        val candidates = ordered.mapNotNull { runCatching { ShieldCodec.toArmedShield(it.shield) }.getOrNull() }
        val hit = Matcher.match(notificationMatchText(item.title, item.conversation, item.text), candidates, mode)
            ?: return null
        return ordered.first { it.shield.id == hit.id }.session
    }

    /** One durable fantasy identity per continuous period with any effective real protection. */
    private suspend fun maintainFantasy(now: Long, startNew: Boolean): ProtectionSessionEntity? {
        val active = active(now)
        var sentinel = shields.fantasyShield()
        val previous = sentinel?.currentSessionId?.let { sessions.byId(it) }
        if (active.isEmpty()) {
            previous?.let { sessions.end(it.sessionId, now, "COVERAGE_ENDED") }
            return null
        }
        if (!startNew && previous != null && previous.endedAtMillis == null) return previous
        previous?.let { sessions.end(it.sessionId, now, "COVERAGE_REPLACED") }
        if (sentinel == null) {
            val id = shields.insert(ShieldEntity(name = "Fantasy", aliasesJson = "[]", kind = "FANTASY",
                pregamePrompts = false))
            sentinel = shields.byId(id)!!
        }
        val epoch = ProtectionSessionEntity(newId(), sentinel.id, "FANTASY", sentinel.name, now)
        sessions.insert(epoch)
        shields.update(sentinel.copy(currentSessionId = epoch.sessionId, sessionRevision = 0))
        return epoch
    }
}
