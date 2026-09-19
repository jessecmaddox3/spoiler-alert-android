package com.jessemaddox.spoileralert.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.jessemaddox.spoileralert.domain.Alias
import com.jessemaddox.spoileralert.domain.MatchMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Invented notifications in a new isolated Room database. No device notification capture. */
@RunWith(AndroidJUnit4::class)
class CaptureConsentTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ShieldRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ShieldRepository(context, db)
    }

    @After fun teardown() { db.close() }

    private suspend fun shield(name: String, armedAt: Long): Long {
        val id = db.shieldDao().insert(ShieldEntity(name = name, kind = "CUSTOM",
            aliasesJson = ShieldCodec.encodeAliases(listOf(Alias("comets")))))
        ProtectionSessions(db, { armedAt }).arm(id)
        return id
    }
    private suspend fun capture(id: Long): CaptureContext {
        val shield = db.shieldDao().byId(id)!!
        return CaptureContext(shield.currentSessionId!!, id, shield.kind, shield.name,
            MatchMode.AGGRESSIVE.name, shield.armedAtMillis!!)
    }

    @Test fun lateWriteAfterAutomaticExpiryStaysSealed() = runBlocking {
        val id = shield("Cedar Comets", 1L)
        val capture = capture(id)
        assertTrue(repo.expire(SessionToken.from(db.shieldDao().byId(id)!!)!!))
        val row = repo.upsertVaultHidden("invented-late", id, "example.sports", "Demo Sports",
            "Comets update", "A fictional update", 10L, null, capture = capture)
        assertNull("Expiry is not reveal consent", db.vaultDao().byId(row)!!.revealedAtMillis)
    }

    @Test fun conversationNameStillMatchesAnOverlappingProtectionOnRelease() = runBlocking {
        val now = System.currentTimeMillis()
        val first = shield("First demo protection", now)
        val second = shield("Second demo protection", now)
        val row = repo.upsertVaultHidden("invented-overlap", first, "example.sports", "Demo Sports",
            "Update", "Ready to watch", now, "Comets discussion", capture = capture(first))
        repo.stopAndReveal(first, repo.revealScope(first))
        val saved = db.vaultDao().byId(row)!!
        assertNull(saved.revealedAtMillis)
        assertEquals(second, saved.shieldId)
    }
}
