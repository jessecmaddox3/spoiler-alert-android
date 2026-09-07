package com.jessemaddox.spoileralert.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jessemaddox.spoileralert.data.AppDatabase
import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldRepository
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TileState.snapshot] coverage against an isolated in-memory database (same pattern as
 * [com.jessemaddox.spoileralert.data.ShieldRepositoryTest]) — the Quick Settings tile's DB
 * read must never touch vault content and must resolve the idle-vs-active/arm-vs-open-app
 * inputs correctly.
 */
@RunWith(AndroidJUnit4::class)
class TileStateTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ShieldRepository

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ShieldRepository(context, db)
    }

    @After fun teardown() { db.close() }

    @Test fun noShieldsIsIdleWithNoCandidates() = runBlocking {
        val state = TileState.snapshot(context, repo)
        assertEquals(false, state.armed)
        assertTrue(state.idleCandidates.isEmpty())
    }

    @Test fun oneTeamShieldWithAGameIsTheSoleCandidate() = runBlocking {
        val shieldId = db.shieldDao().insert(
            ShieldEntity(name = "Bills", aliasesJson = "[]", kind = "TEAM", catalogTeamId = "buf-bills")
        )
        db.gameDao().upsertAll(listOf(
            GameEntity(
                id = "evt-1", shieldId = shieldId, name = "Buffalo Bills at Kansas City Chiefs",
                shortName = "BUF @ KC", startMillis = System.currentTimeMillis() + 3_600_000L,
                completed = false, fetchedAtMillis = 0L,
            ),
        ))

        val state = TileState.snapshot(context, repo)
        assertEquals(false, state.armed)
        assertEquals(1, state.idleCandidates.size)
        val candidate = state.idleCandidates.single()
        assertEquals(shieldId, candidate.shieldId)
        assertEquals("BUF @ KC", candidate.name)
        assertEquals("nfl", candidate.leagueId)
    }

    @Test fun twoTeamShieldsWithGamesAreAmbiguous() = runBlocking {
        val now = System.currentTimeMillis()
        val id1 = db.shieldDao().insert(ShieldEntity(name = "Bills", aliasesJson = "[]", kind = "TEAM", catalogTeamId = "buf-bills"))
        val id2 = db.shieldDao().insert(ShieldEntity(name = "Dolphins", aliasesJson = "[]", kind = "TEAM", catalogTeamId = "mia-dolphins"))
        db.gameDao().upsertAll(listOf(
            GameEntity("evt-1", id1, "n1", "BUF @ KC", now + 3_600_000L, false, 0L),
            GameEntity("evt-2", id2, "n2", "MIA @ NE", now + 7_200_000L, false, 0L),
        ))

        val state = TileState.snapshot(context, repo)
        assertEquals(2, state.idleCandidates.size)
    }

    @Test fun teamShieldWithNoUpcomingGameIsNotACandidate() = runBlocking {
        db.shieldDao().insert(ShieldEntity(name = "Bills", aliasesJson = "[]", kind = "TEAM", catalogTeamId = "buf-bills"))
        val state = TileState.snapshot(context, repo)
        assertTrue(state.idleCandidates.isEmpty())
    }

    @Test fun customShieldsNeverBecomeCandidatesEvenWhenUnarmed() = runBlocking {
        db.shieldDao().insert(ShieldEntity(name = "Spoilers", aliasesJson = "[]", kind = "CUSTOM"))
        val state = TileState.snapshot(context, repo)
        assertTrue(state.idleCandidates.isEmpty())
    }

    @Test fun anyArmedShieldMakesTheTileActiveAndSkipsIdleCandidates() = runBlocking {
        val teamId = db.shieldDao().insert(
            ShieldEntity(name = "Bills", aliasesJson = "[]", kind = "TEAM", catalogTeamId = "buf-bills")
        )
        db.gameDao().upsertAll(listOf(
            GameEntity("evt-1", teamId, "n1", "BUF @ KC", System.currentTimeMillis() + 3_600_000L, false, 0L),
        ))
        val customId = db.shieldDao().insert(ShieldEntity(name = "Custom", aliasesJson = "[]", kind = "CUSTOM"))
        repo.arm(customId, 4)

        val state = TileState.snapshot(context, repo)
        assertEquals(true, state.armed)
        assertEquals("Custom", state.activeName)
        assertEquals(
            ShieldCodec.sessionDeadlineMillis(db.shieldDao().byId(customId)!!),
            state.activeExpiresAtMillis,
        )
        assertTrue(state.idleCandidates.isEmpty())
    }

    @Test fun activeStateNamesTheSoonestExpiringShieldWhenSeveralAreArmed() = runBlocking {
        val longId = db.shieldDao().insert(ShieldEntity(name = "Long", aliasesJson = "[]", kind = "CUSTOM"))
        val shortId = db.shieldDao().insert(ShieldEntity(name = "Short", aliasesJson = "[]", kind = "CUSTOM"))
        repo.arm(longId, 10)
        repo.arm(shortId, 1)

        val state = TileState.snapshot(context, repo)
        assertEquals(true, state.armed)
        assertEquals("Short", state.activeName)
    }
}
