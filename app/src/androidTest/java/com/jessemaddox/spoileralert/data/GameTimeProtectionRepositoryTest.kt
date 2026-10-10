package com.jessemaddox.spoileralert.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Game-time protection against a real (isolated) Room database: hiding follows each game's
 *  own window, starts once per window, and an explicit stop is never undone by the window. */
@RunWith(AndroidJUnit4::class)
class GameTimeProtectionRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ShieldRepository

    private val minute = 60_000L
    private val hour = 60 * minute

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ShieldRepository(context, db)
    }

    @After fun teardown() { db.close() }

    private suspend fun teamWithGame(start: Long, autoProtect: Boolean = true): Long {
        val id = db.shieldDao().insert(ShieldEntity(name = "Cedar Comets", aliasesJson = "[]",
            kind = "TEAM", autoProtect = autoProtect))
        db.gameDao().upsertAll(listOf(GameEntity("fiction-game", id, "Cedar Comets at Harbor Kites",
            "CED @ HAR", start, completed = false, fetchedAtMillis = 0L)))
        return id
    }

    /** Neither espn id maps to a catalog team, so aliasing falls back to the event name. */
    private fun matchup(start: Long) = LeagueGameEntity(
        eventId = "fiction-match", leagueId = "soccer", name = "Amber Owls at Violet Finches",
        shortName = "AMB @ VIO", startMillis = start, completed = false,
        homeEspnId = "999901", awayEspnId = "999902", label = null, fetchedAtMillis = 0L,
    )

    private suspend fun deadline(id: Long) = ShieldCodec.sessionDeadlineMillis(db.shieldDao().byId(id)!!)!!

    @Test fun openWindowStartsOnceAndReachesTheExpectedFinish() = runBlocking {
        val kickoff = System.currentTimeMillis() + 5 * minute
        val id = teamWithGame(kickoff)

        assertTrue(repo.startGameWindow(id, "fiction-game"))
        val first = db.shieldDao().byId(id)!!
        assertTrue(first.armed)
        // Unknown league: 3h game + 45m buffer after kickoff.
        assertTrue(deadline(id) >= kickoff + 3 * hour + 45 * minute)

        // A second start (worker plus listener racing) keeps the same session.
        assertTrue(repo.startGameWindow(id, "fiction-game"))
        assertEquals(first.currentSessionId, db.shieldDao().byId(id)!!.currentSessionId)
    }

    @Test fun optingInHoursEarlyHidesNothingUntilTheWindowOpens() = runBlocking {
        val id = teamWithGame(System.currentTimeMillis() + 8 * hour)
        assertFalse(repo.startGameWindow(id, "fiction-game"))
        assertFalse(db.shieldDao().byId(id)!!.armed)
    }

    @Test fun aStopDuringTheGameIsNotUndoneByTheWindow() = runBlocking {
        val id = teamWithGame(System.currentTimeMillis() - 30 * minute)
        assertTrue(repo.startGameWindow(id, "fiction-game"))

        repo.stopAndReveal(id, repo.revealScope(id))
        assertFalse(db.shieldDao().byId(id)!!.armed)
        assertFalse(repo.startGameWindow(id, "fiction-game"))
        assertFalse(db.shieldDao().byId(id)!!.armed)

        // Asking again explicitly still works.
        assertTrue(repo.startGameWindow(id, "fiction-game", explicit = true))
        assertTrue(db.shieldDao().byId(id)!!.armed)
    }

    @Test fun stoppingAnEarlyStartKeepsTheGamesOwnStart() = runBlocking {
        val kickoff = System.currentTimeMillis() + 3 * hour
        db.leagueGameDao().upsertAll(listOf(matchup(kickoff)))
        repo.protectAtGameTime(matchup(kickoff))
        val id = db.shieldDao().byGameEventId("fiction-match")!!.id

        assertTrue(repo.startGameWindow(id, "fiction-match", explicit = true)) // "Start hiding now"
        repo.stopAndReveal(id, repo.revealScope(id))

        // The scheduled game shield survives the stop and its window still opens on its own.
        val shield = db.shieldDao().byId(id)!!
        assertTrue(shield.autoProtect)
        assertFalse(shield.armed)
        val window = repo.observeGameWindows().first().single { it.shieldId == id }
        assertFalse(GameTimeProtection.stoppedDuring(window, shield.gameTimeStoppedAtMillis))
    }

    @Test fun cancellingAScheduledGameRemovesItsUnusedShield() = runBlocking {
        val kickoff = System.currentTimeMillis() + 3 * hour
        db.leagueGameDao().upsertAll(listOf(matchup(kickoff)))
        repo.protectAtGameTime(matchup(kickoff))
        val id = db.shieldDao().byGameEventId("fiction-match")!!.id
        repo.cancelGameTime(id)
        assertEquals(null, db.shieldDao().byId(id))
    }

    @Test fun protectingAGameDaysAwaySchedulesItWithoutHidingNow() = runBlocking {
        val kickoff = System.currentTimeMillis() + 4 * 24 * hour
        db.leagueGameDao().upsertAll(listOf(matchup(kickoff)))

        val result = repo.protectAtGameTime(matchup(kickoff))

        assertEquals(ShieldRepository.GameProtection.Scheduled(kickoff - GameTimeProtection.LEAD_MS), result)
        val shield = db.shieldDao().byGameEventId("fiction-match")!!
        assertTrue(shield.autoProtect)
        assertFalse(shield.armed)
    }

    @Test fun protectingAGameAlreadyOnStartsNowThroughItsFinish() = runBlocking {
        val kickoff = System.currentTimeMillis() - 30 * minute
        db.leagueGameDao().upsertAll(listOf(matchup(kickoff)))

        assertEquals(ShieldRepository.GameProtection.HidingNow, repo.protectAtGameTime(matchup(kickoff)))
        val id = db.shieldDao().byGameEventId("fiction-match")!!.id
        assertTrue(db.shieldDao().byId(id)!!.armed)
        // Knockout soccer: 2.5h game + 45m buffer after kickoff.
        assertTrue(deadline(id) >= kickoff + 3 * hour + 15 * minute)
    }

    @Test fun turningOnEveryGameMidGameStartsHidingAtOnce() = runBlocking {
        val id = teamWithGame(System.currentTimeMillis() - hour, autoProtect = false)
        repo.setAutoProtect(id, true)
        val shield = db.shieldDao().byId(id)!!
        assertTrue(shield.autoProtect)
        assertTrue(shield.armed)
    }

    @Test fun listenerWindowsCoverOnlyOptedInShields() = runBlocking {
        val kickoff = System.currentTimeMillis() + hour
        val optedIn = teamWithGame(kickoff)
        val other = db.shieldDao().insert(ShieldEntity(name = "Harbor Kites", aliasesJson = "[]", kind = "TEAM"))
        db.gameDao().upsertAll(listOf(GameEntity("fiction-other", other, "Other", "OTH", kickoff,
            completed = false, fetchedAtMillis = 0L)))

        val windows = repo.observeGameWindows().first()
        assertEquals(listOf(optedIn to "fiction-game"), windows.map { it.shieldId to it.eventId })
        val snapshot = repo.sessions.snapshot()
        assertEquals(setOf(optedIn), snapshot.gameTimeShields.keys)
    }
}
