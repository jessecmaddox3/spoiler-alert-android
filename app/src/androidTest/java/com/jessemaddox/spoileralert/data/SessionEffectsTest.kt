package com.jessemaddox.spoileralert.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.service.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic local notifications and a fresh database on the task-owned emulator. */
@RunWith(AndroidJUnit4::class)
class SessionEffectsTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ShieldRepository
    private lateinit var nm: NotificationManager

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ShieldRepository(context, db)
        nm = context.getSystemService(NotificationManager::class.java)
    }
    @After fun close() { nm.cancelAll(); db.close() }

    private suspend fun arm(at: Long = System.currentTimeMillis()): SessionToken {
        val id = db.shieldDao().insert(ShieldEntity(name = "Invented Comets", kind = "GAME", aliasesJson = "[]"))
        return SessionToken.from(ProtectionSessions(db, { at }).arm(id)!!.current)!!
    }
    private fun pending(token: SessionToken, selection: RevealSelection) = PendingIntent.getBroadcast(
        context, 701, SessionActions.bind(Intent(context, RevealShieldReceiver::class.java)
            .setAction(RevealShieldReceiver.ACTION_REVEAL_SHIELD), token, "synthetic", selection),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    private fun notice() = Notification.Builder(context, SpoilerAlertApp.CHANNEL_CHECKIN)
        .setSmallIcon(R.drawable.ic_shield).setContentTitle("Synthetic session check").build()
    private suspend fun awaitNotices(predicate: () -> Boolean) = withTimeout(3_000) {
        while (!predicate()) delay(20)
    }

    @Test fun newScopeCannotMutateAnEarlierPendingIntent() {
        val token = SessionToken(21, "invented-session", 0)
        val old = pending(token, RevealSelection(setOf(token.sessionId), setOf("old-fantasy")))
        val later = pending(token, RevealSelection(setOf(token.sessionId), setOf("old-fantasy", "later-fantasy")))
        assertNotEquals(old, later)
        old.cancel(); later.cancel()
    }

    @Test fun changedRevisionAndNewSessionGetIndependentActions() {
        val old = SessionToken(21, "invented-session", 0)
        val first = pending(old, RevealSelection(setOf(old.sessionId)))
        val extended = pending(old.copy(revision = 1), RevealSelection(setOf(old.sessionId)))
        val rearmed = pending(old.copy(sessionId = "later-session"), RevealSelection(setOf("later-session")))
        assertNotEquals(first, extended)
        assertNotEquals(first, rearmed)
        first.cancel(); extended.cancel(); rearmed.cancel()
    }

    @Test fun startupRemovesEffectsLeftAfterCommittedReveal() = runBlocking {
        val token = arm()
        nm.notify(SessionActions.tag(token), SessionActions.CHECKIN_ID, notice())
        awaitNotices { nm.activeNotifications.any { it.tag == SessionActions.tag(token) } }
        repo.sessions.reveal(repo.revealScope(token.shieldId), repo.policy()) // simulated process death before effects
        repo.reconcileSessions()
        awaitNotices { nm.activeNotifications.none { it.tag == SessionActions.tag(token) } }
    }

    @Test fun emptyGameExpiryDoesNotCancelItsOwnWork() = runBlocking {
        val token = arm(1L)
        AutoDisarmWorker.schedule(context, token, 60_000)
        var posted = false
        assertTrue(repo.expire(token) { _, _ -> posted = true })
        assertTrue(posted)
        assertNull(db.shieldDao().byId(token.shieldId))
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SessionActions.workName("auto-disarm", token)).get()
        assertTrue(work.isNotEmpty())
        assertFalse(work.any { it.state == androidx.work.WorkInfo.State.CANCELLED })
    }

    @Test fun staleCompletionCannotPostForAnExtendedSession() = runBlocking {
        val token = arm()
        val current = SessionToken.from(repo.sessions.extend(token)!!.current)!!
        var posted = false
        assertFalse(repo.withCurrentSession(token) { posted = true })
        assertFalse(posted)
        assertTrue(repo.withCurrentSession(current) { posted = true })
        assertTrue(posted)
    }

    @Test fun startupKeepsCurrentWorkIdentityAndAnAppropriateSealedEndNotice() = runBlocking {
        val current = arm()
        AutoDisarmWorker.schedule(context, current, 60_000)
        val wm = WorkManager.getInstance(context)
        val name = SessionActions.workName("auto-disarm", current)
        val before = wm.getWorkInfosForUniqueWork(name).get().map { it.id }
        repo.reconcileSessions()
        assertEquals(before, wm.getWorkInfosForUniqueWork(name).get().map { it.id })
        val expired = arm(1L)
        db.vaultDao().insert(VaultEntity(shieldId = expired.shieldId, sourcePackage = "demo.app",
            sourceAppLabel = "Demo", title = "Invented", text = "Invented", postedAtMillis = 1,
            notificationKey = "sealed", captureSessionId = expired.sessionId, ownerSessionId = expired.sessionId))
        repo.sessions.expire(expired)
        nm.notify(SessionActions.tag(expired), SessionActions.END_ID, notice())
        nm.notify(SessionActions.tag(expired), SessionActions.CHECKIN_ID, notice())
        awaitNotices { nm.activeNotifications.count { it.tag == SessionActions.tag(expired) } == 2 }
        repo.reconcileSessions()
        awaitNotices { nm.activeNotifications.filter { it.tag == SessionActions.tag(expired) }
            .map { it.id } == listOf(SessionActions.END_ID) }
    }

    @Test fun maskedRowsNeverCollideAndStartupClearsOnlyObsoleteRows() = runBlocking {
        val token = arm()
        fun row(id: Long) = VaultEntity(id = id, shieldId = token.shieldId, sourcePackage = "demo.app",
            sourceAppLabel = "Demo", title = "Invented", text = "Invented", postedAtMillis = 1,
            notificationKey = "row-$id", captureSessionId = token.sessionId, ownerSessionId = token.sessionId)
        for (id in listOf(1L, 20_001L)) {
            db.vaultDao().insert(row(id))
            MaskedNotifier.post(context, id, token.shieldId, "Demo")
        }
        awaitNotices { nm.activeNotifications.count { it.tag?.startsWith("vault:") == true } == 2 }
        val notices = nm.activeNotifications.filter { it.tag?.startsWith("vault:") == true }
        assertNotEquals(notices[0].notification.contentIntent, notices[1].notification.contentIntent)
        db.vaultDao().reveal(1L, System.currentTimeMillis())
        repo.reconcileSessions()
        awaitNotices { nm.activeNotifications.filter { it.tag?.startsWith("vault:") == true }
            .map { it.tag } == listOf(MaskedNotifier.tagFor(20_001L)) }
    }
}
