package com.jessemaddox.spoileralert.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).build()
    }

    @After fun teardown() { db.close() }

    @Test fun armDisarmRoundTrip() = runBlocking {
        val id = db.shieldDao().insert(
            ShieldEntity(name = "Falcons", aliasesJson = "[]", kind = "TEAM")
        )
        assertEquals(0, db.shieldDao().armed().size)
        db.shieldDao().update(db.shieldDao().byId(id)!!.copy(armed = true, armedAtMillis = 111L))
        assertEquals(1, db.shieldDao().armed().size)
    }

    /** Regression for the auto-disarm/extend race: the worker observes armedAtMillis, then a
     *  concurrent "Keep hiding" extend re-arms with a fresh armedAtMillis before the worker's
     *  CAS runs. The CAS must fail (0 rows) and leave the re-armed row untouched — the extend
     *  wins instead of being silently clobbered by the worker's disarm. */
    @Test fun disarmIfArmedAtFailsWhenExtendedConcurrently() = runBlocking {
        val dao = db.shieldDao()
        val id = dao.insert(ShieldEntity(name = "Falcons", aliasesJson = "[]", kind = "TEAM"))
        val observedArmedAt = 111L
        dao.setArmed(id, armed = true, armedAt = observedArmedAt)

        // Worker observed armedAt = 111L, but before it can CAS, a "Keep hiding" extend re-arms
        // the shield with a fresh armedAtMillis (simulating ShieldRepository.arm()'s re-arm).
        val extendedArmedAt = 222L
        dao.setArmed(id, armed = true, armedAt = extendedArmedAt)

        val rowsUpdated = dao.disarmIfArmedAt(id, expectedArmedAt = observedArmedAt)
        assertEquals(0, rowsUpdated)

        val shield = dao.byId(id)!!
        assertEquals(true, shield.armed)
        assertEquals(extendedArmedAt, shield.armedAtMillis)
    }

    /** Sanity check on the happy path: when nothing extended the session, the CAS succeeds and
     *  disarms using the armedAt the caller observed. */
    @Test fun disarmIfArmedAtSucceedsWhenUnchanged() = runBlocking {
        val dao = db.shieldDao()
        val id = dao.insert(ShieldEntity(name = "Falcons", aliasesJson = "[]", kind = "TEAM"))
        val observedArmedAt = 111L
        dao.setArmed(id, armed = true, armedAt = observedArmedAt)

        val rowsUpdated = dao.disarmIfArmedAt(id, expectedArmedAt = observedArmedAt)
        assertEquals(1, rowsUpdated)

        val shield = dao.byId(id)!!
        assertEquals(false, shield.armed)
        assertEquals(null, shield.armedAtMillis)
    }

    @Test fun extendIfArmedAtUpdatesDeadlineFieldsOnlyForCurrentSession() = runBlocking {
        val dao = db.shieldDao()
        val id = dao.insert(
            ShieldEntity(
                name = "Falcons", aliasesJson = "[]", kind = "TEAM",
                armed = true, armedAtMillis = 111L, autoDisarmHours = 4,
            )
        )

        assertEquals(0, dao.extendIfArmedAt(id, 999L, 222L))
        assertEquals(1, dao.extendIfArmedAt(id, 111L, 222L))
        val extended = dao.byId(id)!!
        assertEquals(true, extended.armed)
        assertEquals(222L, extended.armedAtMillis)
        assertEquals(4, extended.autoDisarmHours)
    }

    @Test fun vaultRevealFlow() = runBlocking {
        val dao = db.vaultDao()
        dao.insert(VaultEntity(shieldId = 1, sourcePackage = "com.espn.score_center",
            sourceAppLabel = "ESPN", title = "Final", text = "spoiler",
            postedAtMillis = 1L, notificationKey = "k1"))
        dao.insert(VaultEntity(shieldId = 1, sourcePackage = "com.whatsapp",
            sourceAppLabel = "WhatsApp", title = "Rowan", text = "the paper kite is ready",
            postedAtMillis = 2L, notificationKey = "k2"))
        assertEquals(2, dao.hiddenCount())
        dao.revealAllForShield(1, now = 100L)
        assertEquals(0, dao.hiddenCount())
        assertEquals(2, dao.observeAll().first().size)
        dao.pruneRevealedBefore(cutoff = 200L)
        assertEquals(0, dao.observeAll().first().size)
    }

    /** Regression: two shields covering teams that play each other must both keep their row
     *  for the shared ESPN event id — the primary key is (id, shieldId), not bare id. */
    @Test fun sameGameCachedForTwoShieldsDoesNotCollide() = runBlocking {
        val dao = db.gameDao()
        val falconsShieldId = 1L
        val saintsShieldId = 2L
        val sharedEventId = "fiction-shared-game" // e.g. CED @ HAR
        dao.upsertAll(listOf(
            GameEntity(id = sharedEventId, shieldId = falconsShieldId, name = "Harbor Kites at Cedar Comets",
                shortName = "HAR @ CED", startMillis = 1_000L, completed = false, fetchedAtMillis = 0L),
            GameEntity(id = sharedEventId, shieldId = saintsShieldId, name = "Harbor Kites at Cedar Comets",
                shortName = "HAR @ CED", startMillis = 1_000L, completed = false, fetchedAtMillis = 0L),
        ))
        assertEquals(1, dao.upcomingForShield(falconsShieldId, nowMillis = 0L).size)
        assertEquals(1, dao.upcomingForShield(saintsShieldId, nowMillis = 0L).size)
        assertEquals(2, dao.observeUpcoming(nowMillis = 0L).first().size)
    }

    @Test fun gameShieldLookupByEventIdAndVaultCount() = runBlocking {
        val id = db.shieldDao().insert(ShieldEntity(
            name = "AMB @ VIO", aliasesJson = "[]", kind = "GAME", gameEventId = "fiction-migration-soccer",
        ))
        assertEquals(id, db.shieldDao().byGameEventId("fiction-migration-soccer")?.id)
        assertEquals(null, db.shieldDao().byGameEventId("999999"))

        db.vaultDao().insert(VaultEntity(shieldId = id, sourcePackage = "p", sourceAppLabel = "a",
            title = "t", text = "x", postedAtMillis = 1L, notificationKey = "k1",
            revealedAtMillis = 50L))
        db.vaultDao().insert(VaultEntity(shieldId = id, sourcePackage = "p", sourceAppLabel = "a",
            title = "t", text = "x", postedAtMillis = 2L, notificationKey = "k2"))
        // countForShield sees hidden AND revealed rows; hiddenForShield only the sealed one.
        assertEquals(2, db.vaultDao().countForShield(id))
        assertEquals(1, db.vaultDao().hiddenForShield(id).size)
    }

    /** Orphan prune shape: a spent GAME shield (no vault rows, game in the past) is orphaned;
     *  protect-later shields (upcoming game), shields with vault rows, armed shields, and
     *  TEAM shields never are. */
    @Test fun orphanedGameShieldIds() = runBlocking {
        val cutoff = 1_000L
        suspend fun shield(kind: String, armed: Boolean = false) = db.shieldDao().insert(
            ShieldEntity(name = "s", aliasesJson = "[]", kind = kind, armed = armed,
                armedAtMillis = if (armed) 1L else null)
        )
        suspend fun gameRow(shieldId: Long, start: Long) = db.gameDao().upsertAll(listOf(
            GameEntity(id = "e$shieldId", shieldId = shieldId, name = "n", shortName = "s",
                startMillis = start, completed = false, fetchedAtMillis = 0L)
        ))

        val spent = shield("GAME"); gameRow(spent, start = 500L)          // old game, no vault
        val pending = shield("GAME"); gameRow(pending, start = 2_000L)    // upcoming game
        val withVault = shield("GAME"); gameRow(withVault, start = 500L)
        db.vaultDao().insert(VaultEntity(shieldId = withVault, sourcePackage = "p",
            sourceAppLabel = "a", title = "t", text = "x", postedAtMillis = 1L,
            notificationKey = "k", revealedAtMillis = 5L))
        val armed = shield("GAME", armed = true); gameRow(armed, start = 500L)
        val team = shield("TEAM")

        assertEquals(listOf(spent), db.shieldDao().orphanedGameShieldIds(cutoff))
        // Sanity: the others stay.
        assertEquals(true, db.shieldDao().byId(pending) != null)
        assertEquals(true, db.shieldDao().byId(withVault) != null)
        assertEquals(true, db.shieldDao().byId(armed) != null)
        assertEquals(true, db.shieldDao().byId(team) != null)
    }

    @Test fun leagueGamesRoundTripUpcomingFilterAndPrune() = runBlocking {
        val dao = db.leagueGameDao()
        fun game(id: String, league: String, start: Long, completed: Boolean = false, label: String? = null) =
            LeagueGameEntity(eventId = id, leagueId = league, name = "n$id", shortName = "s$id",
                startMillis = start, completed = completed, homeEspnId = "1", awayEspnId = "2",
                label = label, fetchedAtMillis = 0L)

        dao.upsertAll(listOf(
            game("e1", "nfl", start = 100L, label = "NFL Melbourne Game"),
            game("e2", "nfl", start = 200L),
            game("e3", "mlb", start = 150L),
            game("e4", "mlb", start = 50L, completed = true), // finished -> pruned
            game("e5", "nba", start = 5L),                    // long past -> pruned
        ))

        // byId + label round-trip (nullable label survives both ways).
        assertEquals("NFL Melbourne Game", dao.byId("e1")?.label)
        assertEquals(null, dao.byId("e2")?.label)

        // League filter; null = all leagues; both respect completed/now/limit.
        assertEquals(listOf("e1", "e2"), dao.upcoming("nfl", nowMillis = 60L, limit = 10).map { it.eventId })
        assertEquals(listOf("e1", "e3", "e2"), dao.upcoming(null, nowMillis = 60L, limit = 10).map { it.eventId })
        assertEquals(1, dao.upcoming(null, nowMillis = 60L, limit = 1).size)

        // REPLACE upsert: a re-fetched eventId with a new start wins, no duplicate row.
        dao.upsertAll(listOf(game("e2", "nfl", start = 300L)))
        assertEquals(300L, dao.byId("e2")?.startMillis)

        dao.pruneBefore(cutoff = 60L)
        assertEquals(listOf("e1", "e3", "e2"), dao.upcoming(null, nowMillis = 0L, limit = 10).map { it.eventId })
        assertEquals(null, dao.byId("e4"))
        assertEquals(null, dao.byId("e5"))

        dao.deleteAll()
        assertEquals(0, dao.upcoming(null, nowMillis = 0L, limit = 10).size)
    }

    /** Ship-blocker regression (collated review finding 1): a game that kicked off recently
     *  must still be reachable through the look-back-aware call pattern every "still
     *  protectable" surface uses ([GameWindows.sinceMillis] as the DAO's cutoff) — Big games,
     *  the browser, and (via [GameDao.observeSince]) a shield's pending-game card. A game that
     *  started long enough ago to fall outside the window must not. */
    @Test fun leagueGamesUpcomingIncludesRecentlyStartedButNotLongStarted() = runBlocking {
        val dao = db.leagueGameDao()
        val now = 10_000_000_000L
        fun game(id: String, start: Long) = LeagueGameEntity(
            eventId = id, leagueId = "soccer", name = "Argentina vs Spain", shortName = "ARG @ ESP",
            startMillis = start, completed = false, homeEspnId = "1", awayEspnId = "2",
            label = null, fetchedAtMillis = 0L,
        )
        dao.upsertAll(listOf(
            game("startedRecently", now - 30 * 60 * 1000L),       // kicked off 30 min ago
            game("startedLongAgo", now - 5 * 60 * 60 * 1000L),    // kicked off 5h ago
            game("notYetStarted", now + 30 * 60 * 1000L),         // kicks off in 30 min
        ))

        val results = dao.upcoming(null, GameWindows.sinceMillis(now), limit = 100).map { it.eventId }.toSet()

        assertEquals(true, "startedRecently" in results)
        assertEquals(true, "notYetStarted" in results)
        assertEquals(false, "startedLongAgo" in results)
    }

    @Test fun multiDayEventRemainsUpcomingUntilItsEnd() = runBlocking {
        val dao = db.leagueGameDao()
        dao.upsertAll(listOf(
            LeagueGameEntity(
                eventId = "open", leagueId = "golf", name = "Meadow Open", shortName = "Meadow Open",
                startMillis = 100L, completed = false, homeEspnId = "", awayEspnId = "",
                label = null, fetchedAtMillis = 100L, endMillis = 500L, phase = "regular-season",
            )
        ))

        assertEquals("regular-season", dao.upcoming("golf", nowMillis = 300L, limit = 10).single().phase)
        dao.pruneBefore(cutoff = 400L)
        assertEquals("open", dao.byId("open")?.eventId)
        dao.pruneBefore(cutoff = 600L)
        assertEquals(null, dao.byId("open"))
    }

    /** Same look-back behavior for the per-shield `games` table ([GameDao.observeSince]),
     *  which backs [com.jessemaddox.spoileralert.ui.MainViewModel]'s nextGames/hasPendingGame
     *  — a disarmed "Protect later" GAME shield's pending card must not vanish at kickoff. */
    @Test fun gameDaoObserveSinceIncludesRecentlyStartedButNotLongStarted() = runBlocking {
        val dao = db.gameDao()
        val now = 10_000_000_000L
        val shieldId = 1L
        fun row(id: String, start: Long) = GameEntity(
            id = id, shieldId = shieldId, name = "n", shortName = "s",
            startMillis = start, completed = false, fetchedAtMillis = 0L,
        )
        dao.upsertAll(listOf(
            row("startedRecently", now - 30 * 60 * 1000L),
            row("startedLongAgo", now - 5 * 60 * 60 * 1000L),
        ))

        val results = dao.observeSince(GameWindows.sinceMillis(now)).first().map { it.id }.toSet()

        assertEquals(true, "startedRecently" in results)
        assertEquals(false, "startedLongAgo" in results)
    }
}
