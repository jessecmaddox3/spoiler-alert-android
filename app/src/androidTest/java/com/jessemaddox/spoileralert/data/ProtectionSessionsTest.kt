package com.jessemaddox.spoileralert.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jessemaddox.spoileralert.domain.Alias
import com.jessemaddox.spoileralert.domain.MatchMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Transaction ordering is deterministic; each test owns a new database and an injected clock. */
@RunWith(AndroidJUnit4::class)
class ProtectionSessionsTest {
    private lateinit var db: AppDatabase
    private lateinit var store: ProtectionSessions
    private var now = 1_000L
    private var sequence = 0
    private val policy = ProtectionPolicy(emptySet(), setOf("example.fantasy"), "example.thisapp")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        store = ProtectionSessions(db, { now }, { "test-session-${++sequence}" })
    }
    @After fun close() { db.close() }

    private suspend fun arm(name: String = "Cedar Comets"): SessionToken {
        val id = db.shieldDao().insert(ShieldEntity(name = name, kind = "CUSTOM",
            aliasesJson = ShieldCodec.encodeAliases(listOf(Alias("comets")))))
        return SessionToken.from(store.arm(id)!!.current)!!
    }
    private suspend fun capture(token: SessionToken) = store.snapshot().protections.single { it.token == token }
        .capture(MatchMode.AGGRESSIVE, now)
    private fun payload(capture: CaptureContext, key: String = "demo-key", text: String = "An invented update",
        title: String = "Comets news", conversation: String? = null, count: Int = 1, source: String = "example.sports") =
        VaultEntity(shieldId = capture.shieldId, sourcePackage = source, sourceAppLabel = "Demo app",
            title = title, text = text, postedAtMillis = now, notificationKey = key,
            conversation = conversation, isConversation = conversation != null, messageCount = count)
    private suspend fun expire(token: SessionToken) {
        now = ShieldCodec.expiresAtMillis(db.shieldDao().byId(token.shieldId)!!)!!
        assertNotNull(store.expire(token))
    }

    @Test fun captureThenExpiryThenPersistenceStaysSealed() = runBlocking {
        val token = arm(); val context = capture(token)
        expire(token)
        val item = store.persist(context, payload(context), policy).item
        assertNull(item.revealedAtMillis)
        assertEquals(token.sessionId, item.ownerSessionId)
        assertNull(db.protectionSessionDao().byId(token.sessionId)!!.revealAuthorizedAtMillis)
    }

    @Test fun explicitRevealAuthorizesLateCaptureFromThatSession() = runBlocking {
        val token = arm(); val context = capture(token)
        store.reveal(RevealSelection(setOf(token.sessionId)), policy)
        val item = store.persist(context, payload(context), policy).item
        assertNotNull(item.revealedAtMillis)
        assertEquals(token.sessionId, item.ownerSessionId)
    }

    @Test fun persistedThenExpiredHasSameSealedDisposition() = runBlocking {
        val token = arm(); val context = capture(token)
        val id = store.persist(context, payload(context), policy).item.id
        expire(token)
        assertNull(db.vaultDao().byId(id)!!.revealedAtMillis)
    }

    @Test fun oldConsentCannotExposeNewProtection() = runBlocking {
        val old = arm(); val context = capture(old)
        store.reveal(RevealSelection(setOf(old.sessionId)), policy)
        val current = SessionToken.from(store.arm(old.shieldId)!!.current)!!
        val item = store.persist(context, payload(context), policy).item
        assertNull(item.revealedAtMillis)
        assertEquals(old.sessionId, item.captureSessionId)
        assertEquals(current.sessionId, item.ownerSessionId)
        store.reveal(RevealSelection(setOf(old.sessionId)), policy)
        assertTrue(db.shieldDao().byId(old.shieldId)!!.armed)
    }

    @Test fun extensionInvalidatesOldWorkerAndExpiryCannotResurrect() = runBlocking {
        val old = arm()
        val revised = SessionToken.from(store.extend(old)!!.current)!!
        assertEquals(old.sessionId, revised.sessionId)
        assertEquals(old.revision + 1, revised.revision)
        assertNull(store.expire(old))
        assertNull(store.extend(old))
        expire(revised)
        assertNull(store.extend(revised))
    }

    @Test fun transferredOwnerConsentControlsDelayedOriginalCapture() = runBlocking {
        val a = arm("First demo"); val context = capture(a); val b = arm("Second demo")
        val original = store.persist(context, payload(context, title = "Update", conversation = "Comets discussion"), policy).item
        store.reveal(RevealSelection(setOf(a.sessionId)), policy)
        assertEquals(b.sessionId, db.vaultDao().byId(original.id)!!.ownerSessionId)
        expire(b)
        val update = store.persist(context, payload(context, title = "Update", conversation = "Comets discussion", text = "Second invented line"), policy).item
        assertEquals(original.id, update.id)
        assertEquals(a.sessionId, update.captureSessionId)
        assertEquals(b.sessionId, update.ownerSessionId)
        assertNull(update.revealedAtMillis)
        assertTrue(update.text.contains("An invented update"))
        assertTrue(update.text.contains("Second invented line"))
    }

    @Test fun itemRevealDoesNotAuthorizeFutureUpdatesOrLoseTransferredOwnership() = runBlocking {
        val a = arm("First demo"); val context = capture(a); val b = arm("Second demo")
        val original = store.persist(context, payload(context), policy).item
        store.reveal(RevealSelection(setOf(a.sessionId)), policy)
        db.vaultDao().reveal(original.id, now)
        expire(b)
        val update = store.persist(context, payload(context, text = "A different later update"), policy).item
        assertNotEquals(original.id, update.id)
        assertEquals(b.sessionId, update.ownerSessionId)
        assertNull(update.revealedAtMillis)
        assertEquals("An invented update", db.vaultDao().byId(original.id)!!.text)
    }

    @Test fun sameAndRotatingKeysMergeOnlyWithinOriginalSession() = runBlocking {
        val token = arm(); val context = capture(token)
        val first = store.persist(context, payload(context, text = "First", conversation = "Comets chat"), policy).item
        val next = store.persist(context, payload(context, text = "Second", conversation = "Comets chat"), policy).item
        val cumulative = store.persist(context, payload(context, key = "rotated", text = "First\nSecond\nThird",
            conversation = "Comets chat", count = 3), policy).item
        assertEquals(first.id, next.id)
        assertEquals(first.id, cumulative.id)
        assertEquals("First\nSecond\nThird", cumulative.text)
        assertEquals(3, cumulative.messageCount)
        val rearmed = SessionToken.from(store.arm(token.shieldId)!!.current)!!
        val newCapture = capture(rearmed)
        val independent = store.persist(newCapture, payload(newCapture, text = "Another session", conversation = "Comets chat"), policy).item
        assertNotEquals(first.id, independent.id)
        assertEquals("Another session", independent.text)
    }

    @Test fun unknownSessionHasNoInferredPermission() = runBlocking {
        val context = CaptureContext("unknown", 91, "CUSTOM", "Earlier demo", "AGGRESSIVE", now)
        val item = store.persist(context, payload(context), policy).item
        assertNull(item.revealedAtMillis)
        assertEquals("RECOVERED", db.protectionSessionDao().byId("unknown")!!.endReason)
    }

    @Test fun deletedOwnerKeepsAnExplicitConsentTombstone() = runBlocking {
        val token = arm(); val context = capture(token)
        store.reveal(RevealSelection(setOf(token.sessionId)), policy, token.shieldId, token.sessionId)
        assertNull(db.shieldDao().byId(token.shieldId))
        assertNotNull(store.persist(context, payload(context), policy).item.revealedAtMillis)
    }

    @Test fun fantasyEpochExistsBeforeCaptureAndAutomaticExpiryKeepsItSealed() = runBlocking {
        val token = arm(); val fantasy = store.snapshot().fantasy!!
        assertNotNull(db.protectionSessionDao().byId(fantasy.sessionId))
        expire(token)
        val item = store.persist(fantasy, payload(fantasy, source = "example.fantasy"), policy).item
        assertNull(item.revealedAtMillis)
        assertEquals(fantasy.sessionId, item.ownerSessionId)
    }

    @Test fun finalExplicitReleaseIncludesFantasyButAnOverlappingReleaseDoesNot() = runBlocking {
        val a = arm(); val fantasy = store.snapshot().fantasy!!; val b = arm("Second demo")
        store.reveal(store.revealScope(a.shieldId), policy)
        val item = store.persist(fantasy, payload(fantasy, source = "example.fantasy"), policy).item
        assertNull(item.revealedAtMillis)
        assertEquals(fantasy.sessionId, store.snapshot().fantasy!!.sessionId)
        store.reveal(store.revealScope(b.shieldId), policy)
        assertNotNull(db.vaultDao().byId(item.id)!!.revealedAtMillis)
        val late = store.persist(fantasy, payload(fantasy, key = "late", source = "example.fantasy"), policy).item
        assertNotNull(late.revealedAtMillis)
    }

    @Test fun freshFantasyEpochProtectsOldInFlightCapture() = runBlocking {
        val a = arm(); val oldFantasy = store.snapshot().fantasy!!
        store.reveal(RevealSelection(setOf(a.sessionId)), policy)
        store.arm(a.shieldId)
        val newFantasy = store.snapshot().fantasy!!
        assertNotEquals(oldFantasy.sessionId, newFantasy.sessionId)
        val item = store.persist(oldFantasy, payload(oldFantasy, source = "example.fantasy"), policy).item
        assertNull(item.revealedAtMillis)
        assertEquals(newFantasy.sessionId, item.ownerSessionId)
    }

    @Test fun staleRevealSelectionCannotAuthorizeALaterFantasyEpoch() = runBlocking {
        val a = arm(); val oldSelection = store.revealScope(a.shieldId)
        expire(a)
        val b = arm("Later protection"); val laterFantasy = store.snapshot().fantasy!!
        val item = store.persist(laterFantasy, payload(laterFantasy, source = "example.fantasy"), policy).item
        expire(b)
        store.reveal(oldSelection, policy)
        assertNull(db.vaultDao().byId(item.id)!!.revealedAtMillis)
        assertNull(db.protectionSessionDao().byId(laterFantasy.sessionId)!!.revealAuthorizedAtMillis)
    }

    @Test fun historyDeletionCannotRestoreAnOlderOwnersConsent() = runBlocking {
        for (prune in listOf(false, true)) {
            val a = arm("Original protection"); val context = capture(a); val b = arm("Transferred protection")
            val original = store.persist(context, payload(context), policy).item
            store.reveal(RevealSelection(setOf(a.sessionId)), policy)
            db.vaultDao().reveal(original.id, now)
            if (prune) db.vaultDao().pruneRevealedBefore(now + 1) else db.vaultDao().deleteRevealed()
            assertNull(db.vaultDao().byId(original.id))
            expire(b)
            val update = store.persist(context, payload(context, text = "New content after history deletion"), policy).item
            assertNull(update.revealedAtMillis)
            assertEquals(b.sessionId, update.ownerSessionId)
        }
    }

    @Test fun removingFantasyPackageDoesNotBypassAnOrdinaryKeywordProtection() = runBlocking {
        val real = arm(); val fantasy = store.snapshot().fantasy!!
        val item = store.persist(fantasy, payload(fantasy, source = "example.fantasy"), policy).item
        store.reveal(store.revealScope(fantasy.shieldId), policy.copy(fantasyPackages = emptySet()))
        val retained = db.vaultDao().byId(item.id)!!
        assertNull(retained.revealedAtMillis)
        assertEquals(real.sessionId, retained.ownerSessionId)
    }

    @Test fun rotatedKeyTransferOutranksAnOlderRevealedContentRow() = runBlocking {
        val a = arm(); val context = capture(a)
        val first = store.persist(context, payload(context, key = "first", conversation = "Comets chat"), policy).item
        store.reveal(store.revealScope(a.shieldId), policy)
        val b = arm("Later protection")
        store.persist(context, payload(context, key = "rotated", conversation = "Comets chat"), policy)
        expire(b)
        val late = store.persist(context, payload(context, key = "first", conversation = "Comets chat",
            text = "Later invented news"), policy).item
        assertNotEquals(first.id, late.id)
        assertEquals(b.sessionId, late.ownerSessionId)
        assertNull(late.revealedAtMillis)
    }

    @Test fun inactiveInterestsRemainAvailableAndOnlyDeletedGroupsGetArchiveLabels() = runBlocking {
        val saved = db.shieldDao().insert(ShieldEntity(name = "Saved Comets", kind = "CUSTOM",
            aliasesJson = ShieldCodec.encodeAliases(listOf(Alias("comets")))))
        assertEquals("CUSTOM", store.uiSnapshot(now).shields.single { it.id == saved }.kind)
        val token = SessionToken.from(store.arm(saved)!!.current)!!
        val context = capture(token)
        store.persist(context, payload(context), policy)
        expire(token)
        assertEquals("CUSTOM", store.uiSnapshot(now).shields.single { it.id == saved }.kind)
        db.shieldDao().delete(saved)
        val orphan = store.uiSnapshot(now).shields.single { it.id == saved }
        assertEquals("ARCHIVED", orphan.kind)
        assertEquals("Saved Comets", orphan.name)
    }

    @Test fun aNewOwnerCannotAbsorbEarlierUnmatchedSealedConversationContent() = runBlocking {
        val aId = db.shieldDao().insert(ShieldEntity(name = "General sports", kind = "CUSTOM",
            aliasesJson = ShieldCodec.encodeAliases(listOf(Alias("sports")))))
        val a = SessionToken.from(store.arm(aId)!!.current)!!
        val context = capture(a)
        val old = store.persist(context, payload(context, title = "Sports update", conversation = "Invented chat",
            text = "Comets score"), policy).item
        expire(a)
        val bId = db.shieldDao().insert(ShieldEntity(name = "Harbor Owls", kind = "CUSTOM",
            aliasesJson = ShieldCodec.encodeAliases(listOf(Alias("owls")))))
        val b = SessionToken.from(store.arm(bId)!!.current)!!
        val later = store.persist(context, payload(context, title = "Sports update", conversation = "Invented chat",
            text = "Owls score"), policy).item
        assertNotEquals(old.id, later.id)
        assertEquals("Comets score", db.vaultDao().byId(old.id)!!.text)
        store.reveal(store.revealScope(bId), policy)
        assertNull(db.vaultDao().byId(old.id)!!.revealedAtMillis)
        assertNotNull(db.vaultDao().byId(later.id)!!.revealedAtMillis)
        assertEquals(b.sessionId, later.ownerSessionId)
    }

    @Test fun everyEarlierOwnerStillRequiresConsentAfterRotationAndHistoryDeletion() = runBlocking {
        for (originalFirst in listOf(false, true)) {
            for (deleteHistory in listOf(false, true)) {
                val a = arm("First Comets"); val context = capture(a)
                store.persist(context, payload(context, conversation = "Invented chat"), policy)
                expire(a)
                val b = arm("Later Comets")
                val newer = store.persist(context, payload(context, key = "rotated", conversation = "Invented chat",
                    text = "Newer Comets update"), policy).item
                assertEquals(b.sessionId, newer.ownerSessionId)
                expire(b)
                val first = if (originalFirst) a else b
                val second = if (originalFirst) b else a
                store.reveal(store.revealScope(first.shieldId), policy)
                if (deleteHistory) db.vaultDao().deleteRevealed()
                val late = store.persist(context, payload(context, key = "another-key", conversation = "Invented chat",
                    text = "Delayed invented update"), policy).item
                assertNull(late.revealedAtMillis)
                assertEquals(second.sessionId, late.ownerSessionId)
                store.reveal(store.revealScope(second.shieldId), policy)
                val permitted = store.persist(context, payload(context, key = "last-key", conversation = "Invented chat"), policy).item
                assertNotNull(permitted.revealedAtMillis)
            }
        }
    }

    @Test fun missingOrRenamedConversationMetadataCannotEraseSiblingConsent() = runBlocking {
        for (renamed in listOf(false, true)) {
            for (deleteHistory in listOf(false, true)) {
                val a = arm(); val context = capture(a)
                store.persist(context, payload(context, key = "first", conversation = "Original invented chat"), policy)
                store.persist(context, payload(context, key = "sibling", conversation = "Original invented chat"), policy)
                store.reveal(store.revealScope(a.shieldId), policy)
                if (deleteHistory) db.vaultDao().deleteRevealed()
                val b = arm()
                store.persist(context, payload(context, key = "first",
                    conversation = if (renamed) "Renamed invented chat" else null), policy)
                expire(b)
                // An old sibling and a brand-new key carrying the old name have the same authority.
                for (key in listOf("sibling", "new-old-name-key")) {
                    val late = store.persist(context, payload(context, key = key,
                        conversation = "Original invented chat"), policy).item
                    assertNull("renamed=$renamed, cleared=$deleteHistory, key=$key", late.revealedAtMillis)
                    assertEquals(b.sessionId, late.ownerSessionId)
                }
                store.reveal(store.revealScope(b.shieldId), policy)
                assertNotNull(store.persist(context, payload(context, key = "last",
                    conversation = "Original invented chat"), policy).item.revealedAtMillis)
            }
        }
    }

    @Test fun renamingBetweenPopulatedGroupsPreservesBothHistoriesAndAliases() = runBlocking {
        val a = arm(); val context = capture(a)
        store.persist(context, payload(context, key = "x-key", conversation = "Invented X"), policy)
        store.reveal(store.revealScope(a.shieldId), policy)
        val b = arm()
        val oldY = store.persist(context, payload(context, key = "y-key", conversation = "Invented Y"), policy).item
        expire(b)
        val c = arm()
        store.persist(context, payload(context, key = "x-key", conversation = "Invented Y"), policy)
        assertEquals(b.sessionId, db.vaultDao().byId(oldY.id)!!.ownerSessionId)
        expire(c)
        store.reveal(store.revealScope(c.shieldId), policy)
        db.vaultDao().deleteRevealed()
        for ((key, chat) in listOf("x-key" to "Invented X", "new-y" to "Invented Y", "new-x" to "Invented X")) {
            val late = store.persist(context, payload(context, key = key, conversation = chat), policy).item
            assertNull(late.revealedAtMillis)
            assertEquals(b.sessionId, late.ownerSessionId)
        }
        store.reveal(store.revealScope(b.shieldId), policy)
        for (chat in listOf("Invented X", "Invented Y")) {
            assertNotNull(store.persist(context, payload(context, key = "permitted-$chat", conversation = chat), policy)
                .item.revealedAtMillis)
        }
    }

    @Test fun malformedOrMissingConsentMetadataCannotBecomeAnEmptyPermissionCheck() = runBlocking {
        for (malformed in listOf(false, true)) {
            val token = arm(); val context = capture(token)
            store.persist(context, payload(context), policy)
            val binding = db.captureOwnershipDao().byKey(context.sessionId, "example.sports",
                captureIdentityHash(context.sessionId, "key", "demo-key"))!!
            db.captureOwnershipDao().put(binding.copy(consentSessionIdsJson = if (malformed) "broken-json"
                else ConsentGuards.encode(setOf(token.sessionId, "missing-guard-${token.sessionId}"))))
            store.reveal(store.revealScope(token.shieldId), policy)
            db.vaultDao().deleteRevealed()
            assertNull(store.persist(context, payload(context, text = "New guarded update"), policy).item.revealedAtMillis)
        }
    }
}
