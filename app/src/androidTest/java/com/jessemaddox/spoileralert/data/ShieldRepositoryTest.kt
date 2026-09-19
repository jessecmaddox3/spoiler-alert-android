package com.jessemaddox.spoileralert.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repository-level coverage for collated review findings 2 and 3 — both live in
 * [ShieldRepository] and need real DAOs + a real (if isolated) [AppDatabase] to exercise
 * meaningfully, so an in-memory database is injected via [ShieldRepository]'s test
 * constructor instead of touching the on-device singleton.
 */
@RunWith(AndroidJUnit4::class)
class ShieldRepositoryTest {
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

    @Test fun automaticCleanupCannotDeleteARearmedGame() = runBlocking {
        val id = insertShield(ShieldEntity(name = "Cedar at Harbor", kind = "GAME", aliasesJson = "[]"))
        repo.sessions.arm(id)
        val current = db.shieldDao().byId(id)!!
        repo.deleteShield(id)
        assertEquals(current, db.shieldDao().byId(id))
    }

    private suspend fun insertShield(shield: ShieldEntity): Long {
        val id = db.shieldDao().insert(shield.copy(armed = false, armedAtMillis = null))
        if (shield.armed) ProtectionSessions(db, { shield.armedAtMillis!! }).arm(id)
        return id
    }
    private suspend fun capture(id: Long): CaptureContext {
        val shield = db.shieldDao().byId(id)!!
        return CaptureContext(requireNotNull(shield.currentSessionId), id, shield.kind, shield.name,
            "AGGRESSIVE", shield.armedAtMillis ?: 1L)
    }
    private suspend fun insertCaptured(item: VaultEntity): Long {
        val shield = db.shieldDao().byId(item.shieldId)!!
        return db.vaultDao().insert(item.copy(captureSessionId = shield.currentSessionId,
            ownerSessionId = shield.currentSessionId, captureMatchMode = "AGGRESSIVE"))
    }

    /** Neither espn id maps to a catalog team, so aliasing always falls back to splitting
     *  [name] — independent of teams.json contents. */
    private fun matchup(
        eventId: String, start: Long,
        name: String = "Argentina at Spain", shortName: String = "ARG @ ESP",
    ) = LeagueGameEntity(
        eventId = eventId, leagueId = "soccer", name = name, shortName = shortName,
        startMillis = start, completed = false, homeEspnId = "999901", awayEspnId = "999902",
        label = null, fetchedAtMillis = 0L,
    )

    /** Finding 3: reusing an existing GAME shield for a matchup must re-provision its
     *  per-shield games row and pregame-prompt work, not just hand back the id. */
    @Test fun createGameShieldReuseRevivesGamesRowAndPrompt() = runBlocking {
        val leagueGame = matchup("wc-final", start = 1_000_000L)
        db.leagueGameDao().upsertAll(listOf(leagueGame))

        val firstId = repo.createGameShield(leagueGame)
        assertNotNull(firstId)
        assertNotNull(db.gameDao().byKey(leagueGame.eventId, firstId!!))

        // Simulate the "spent, revealed-only" cleanup path (cleanupGameShield's else-branch):
        // the shield row survives but its games row is dropped.
        db.gameDao().deleteForShield(firstId)
        assertEquals(null, db.gameDao().byKey(leagueGame.eventId, firstId))

        val secondId = repo.createGameShield(leagueGame)
        assertEquals(firstId, secondId) // reused, not duplicated

        val revived = db.gameDao().byKey(leagueGame.eventId, firstId)
        assertNotNull(revived)
        assertEquals(leagueGame.startMillis, revived?.startMillis)
    }

    @Test fun concurrentGameShieldCreationReusesOneShield() = runBlocking {
        val leagueGame = matchup("concurrent-final", start = 1_000_000L)
        val ids = coroutineScope {
            List(8) { async { repo.createGameShield(leagueGame) } }.awaitAll()
        }

        assertEquals(1, ids.filterNotNull().distinct().size)
        assertEquals(1, db.shieldDao().gameShields().count { it.gameEventId == leagueGame.eventId })
    }

    /** Finding 2: a schedule correction (kickoff moved, name changed) must reach an
     *  already-created GAME shield, and must never disturb its armed/armedAtMillis CAS
     *  fields — even though this refresh deliberately runs while armed. */
    @Test fun refreshGameShieldUpdatesDerivedFieldsWithoutTouchingArmedState() = runBlocking {
        val eventId = "wc-final"
        val stale = matchup(eventId, start = 1_000_000L, name = "Argentina at Spain", shortName = "ARG @ ESP")
        db.leagueGameDao().upsertAll(listOf(stale))
        val shieldId = repo.createGameShield(stale)!!

        // Arm directly at the DAO level (bypassing repo.arm, which now itself calls
        // refreshGameShield) so this test isolates refreshGameShield's own CAS-field safety.
        val armedAt = 555_000L
        db.shieldDao().setArmed(shieldId, armed = true, armedAt = armedAt)

        val corrected = stale.copy(
            startMillis = 2_000_000L, name = "Argentina vs Spain", shortName = "Argentina @ Spain",
        )
        db.leagueGameDao().upsertAll(listOf(corrected))

        repo.refreshGameShield(shieldId)

        val shield = db.shieldDao().byId(shieldId)!!
        assertEquals("Argentina @ Spain", shield.name)
        assertEquals(true, shield.armed)
        assertEquals(armedAt, shield.armedAtMillis) // untouched by the refresh

        val gameRow = db.gameDao().byKey(eventId, shieldId)!!
        assertEquals(2_000_000L, gameRow.startMillis)
    }

    @Test fun refreshGameShieldIsNoopWhenNothingChanged() = runBlocking {
        val eventId = "wc-final"
        val game = matchup(eventId, start = 1_000_000L)
        db.leagueGameDao().upsertAll(listOf(game))
        val shieldId = repo.createGameShield(game)!!
        val before = db.shieldDao().byId(shieldId)!!

        repo.refreshGameShield(shieldId)

        assertEquals(before, db.shieldDao().byId(shieldId)!!)
    }

    @Test fun refreshGameShieldIsNoopForTeamShields() = runBlocking {
        val shieldId = insertShield(
            ShieldEntity(name = "Falcons", aliasesJson = "[]", kind = "TEAM")
        )
        val before = db.shieldDao().byId(shieldId)!!

        repo.refreshGameShield(shieldId) // no gameEventId, no league_games row — must no-op

        assertEquals(before, db.shieldDao().byId(shieldId)!!)
    }

    /** E6: arming a TEAM shield re-derives its aliases from the bundled catalogs, unioning in the
     *  team's star players/coaches ("bijan") while keeping team + player short flags intact. */
    @Test fun armTeamShieldBroadensAliasesWithBundledPlayers() = runBlocking {
        val falcons = com.jessemaddox.spoileralert.domain.Team(
            id = "atl-falcons", name = "Atlanta Falcons",
            aliases = listOf("falcons"), short = listOf("atl"),
        )
        val id = repo.addTeamShield(falcons)

        repo.arm(id) // base overload, no hours

        val stored = ShieldCodec.decodeAliases(db.shieldDao().byId(id)!!.aliasesJson)
        assertTrue(stored.any { it.text == "bijan" && !it.short })
        assertTrue(stored.any { it.text == "atl" && it.short })
        assertTrue(stored.any { it.text == "london" && it.short })
        assertEquals(true, db.shieldDao().byId(id)!!.armed)
    }

    @Test fun refreshTeamShieldIsNoopForNonTeamKinds() = runBlocking {
        val gameId = insertShield(ShieldEntity(name = "BUF @ KC", aliasesJson = "[]", kind = "GAME"))
        val customId = insertShield(ShieldEntity(name = "custom", aliasesJson = "[]", kind = "CUSTOM"))
        val gBefore = db.shieldDao().byId(gameId)!!
        val cBefore = db.shieldDao().byId(customId)!!

        repo.refreshTeamShield(gameId)
        repo.refreshTeamShield(customId)

        assertEquals(gBefore, db.shieldDao().byId(gameId)!!)
        assertEquals(cBefore, db.shieldDao().byId(customId)!!)
    }

    /** Mirrors [refreshGameShieldUpdatesDerivedFieldsWithoutTouchingArmedState]: the alias refresh
     *  runs even while armed, and must never disturb the armed/armedAtMillis CAS fields. */
    @Test fun refreshTeamShieldUpdatesAliasesWithoutTouchingArmedState() = runBlocking {
        val falcons = com.jessemaddox.spoileralert.domain.Team(
            id = "atl-falcons", name = "Atlanta Falcons",
            aliases = listOf("falcons"), short = listOf("atl"),
        )
        val id = repo.addTeamShield(falcons)
        val armedAt = 555_000L
        db.shieldDao().setArmed(id, armed = true, armedAt = armedAt)

        repo.refreshTeamShield(id)

        val shield = db.shieldDao().byId(id)!!
        assertTrue(ShieldCodec.decodeAliases(shield.aliasesJson).any { it.text == "bijan" })
        assertEquals(true, shield.armed)
        assertEquals(armedAt, shield.armedAtMillis)
    }

    @Test fun deliberateStopDisarmsAndRevealsEverythingForTheEvent() = runBlocking {
        val shieldId = insertShield(
            ShieldEntity(name = "FIFA World Cup", aliasesJson = "[]", kind = "TEAM")
        )
        ProtectionSessions(db, { 1_000L }).arm(shieldId)
        val rowId = insertCaptured(
            VaultEntity(
                shieldId = shieldId, sourcePackage = "com.google.android.apps.messaging",
                sourceAppLabel = "Messages", title = "Group chat", text = "spoiler",
                postedAtMillis = 1_001L, notificationKey = "sealed-key",
            )
        )

        val outcome = repo.stopAndReveal(shieldId, repo.revealScope(shieldId))

        val shield = db.shieldDao().byId(shieldId)!!
        assertEquals(false, shield.armed)
        assertEquals(null, shield.armedAtMillis)
        assertEquals("FIFA World Cup", outcome?.name)
        assertEquals(1, outcome?.revealedCount)
        assertNotNull(db.vaultDao().byId(rowId)?.revealedAtMillis)
        assertEquals(emptyList<Long>(), db.vaultDao().allHidden().map { it.id })
    }

    @Test fun finalSessionAlsoReleasesFantasyItemsButOverlappingSessionDoesNot() = runBlocking {
        suspend fun armed(name: String) = insertShield(
            ShieldEntity(name = name, aliasesJson = "[]", kind = "TEAM", armed = true,
                armedAtMillis = System.currentTimeMillis())
        )
        val first = armed("Falcons")
        val second = armed("Atlanta United")
        val fantasy = repo.sessions.snapshot().fantasy!!.shieldId
        val fantasyRow = insertCaptured(VaultEntity(
            shieldId = fantasy, sourcePackage = "com.fantasy", sourceAppLabel = "Fantasy",
            title = "Update", text = "spoiler", postedAtMillis = 1_001L,
            notificationKey = "fantasy-key",
        ))

        repo.stopAndReveal(first, repo.revealScope(first))
        assertEquals(null, db.vaultDao().byId(fantasyRow)?.revealedAtMillis)

        val outcome = repo.stopAndReveal(second, repo.revealScope(second))
        assertEquals(1, outcome?.revealedCount)
        assertNotNull(db.vaultDao().byId(fantasyRow)?.revealedAtMillis)
    }

    @Test fun stopDoesNotRevealContentCoveredByAnotherActiveShield() = runBlocking {
        val aliases = ShieldCodec.encodeAliases(
            listOf(com.jessemaddox.spoileralert.domain.Alias("chiefs"))
        )
        val teamId = insertShield(ShieldEntity(
            name = "Chiefs", aliasesJson = aliases, kind = "TEAM",
            armed = true, armedAtMillis = System.currentTimeMillis(),
        ))
        val gameId = insertShield(ShieldEntity(
            name = "BUF @ KC", aliasesJson = aliases, kind = "GAME",
            armed = true, armedAtMillis = System.currentTimeMillis(),
        ))
        val rowId = insertCaptured(VaultEntity(
            shieldId = teamId, sourcePackage = "com.espn.score_center",
            sourceAppLabel = "ESPN", title = "Chiefs update", text = "What a finish",
            postedAtMillis = System.currentTimeMillis(), notificationKey = "overlap-key",
        ))

        val outcome = repo.stopAndReveal(teamId, repo.revealScope(teamId))

        assertEquals(0, outcome?.revealedCount)
        assertEquals(1, outcome?.stillHiddenCount)
        assertEquals(gameId, db.vaultDao().byId(rowId)?.shieldId)
        assertEquals(null, db.vaultDao().byId(rowId)?.revealedAtMillis)
    }

    /** Startup reconciliation must delete the self-posted fake without deleting a genuine team
     *  alert that happened to arrive while the demo temporarily armed the real team shield. */
    @Test fun reconcileUnonboardedDisarmsAndClearsStrandedDemoState() = runBlocking {
        // Stranded demo: a TEAM shield armed with the demo's shrunk 1h window, plus a hidden fake.
        val shieldId = insertShield(
            ShieldEntity(name = "Falcons", aliasesJson = "[]", kind = "TEAM", autoDisarmHours = 1)
        )
        ProtectionSessions(db, { 1_000L }).arm(shieldId)
        val rowId = insertCaptured(
            VaultEntity(
                shieldId = shieldId, sourcePackage = context.packageName,
                sourceAppLabel = "ESPN", title = "ESPN", text = "Falcons win a thriller",
                postedAtMillis = 1_000L, notificationKey = "demo-key",
            )
        )
        val realRowId = insertCaptured(
            VaultEntity(
                shieldId = shieldId, sourcePackage = "com.espn.score_center",
                sourceAppLabel = "ESPN", title = "Falcons", text = "Real team alert",
                postedAtMillis = 1_001L, notificationKey = "real-key",
            )
        )
        assertNotNull(db.vaultDao().byId(rowId))

        repo.reconcileUnonboarded()

        val shield = db.shieldDao().byId(shieldId)!!
        assertEquals(false, shield.armed)
        assertEquals(null, shield.armedAtMillis)
        assertEquals(null, db.vaultDao().byId(rowId))
        assertNotNull(db.vaultDao().byId(realRowId)) // genuine hide remains sealed
        assertEquals(listOf(realRowId), db.vaultDao().allHidden().map { it.id })
    }

    /** Ship-blocker fix: re-completing onboarding for a team that already has a TEAM shield must
     *  REUSE it, not fork a duplicate (the pre-fix insert had no dedupe, so a brand-new user
     *  could end up with two shields for one team after an abandoned first run). */
    @Test fun addTeamShieldDedupesByCatalogTeamId() = runBlocking {
        val team = com.jessemaddox.spoileralert.domain.Team(
            id = "nfl:atl", name = "Falcons", aliases = listOf("falcons"),
        )
        val firstId = repo.addTeamShield(team)
        val secondId = repo.addTeamShield(team)

        assertEquals(firstId, secondId) // reused, not duplicated
        assertEquals(1, db.shieldDao().teamShields().size)
    }

    /** v3 extras: the lifetime spoilers-held tally increments ONCE per genuinely new hidden
     *  row, and NOT when the same notification key is re-posted (an update, not a new hide). */
    @Test fun upsertVaultHiddenIncrementsLifetimeOnlyForNewKeys() = runBlocking {
        val shieldId = insertShield(
            ShieldEntity(name = "Falcons", aliasesJson = "[]", kind = "TEAM")
        )
        repo.sessions.arm(shieldId)
        val before = com.jessemaddox.spoileralert.ui.AppPrefs.lifetimeHidden(context)

        // Two distinct keys → two new hides → +2.
        repo.upsertVaultHidden("key-a", shieldId, "com.x", "X", "t", "b", 1L, null, capture = capture(shieldId),
        )
        repo.upsertVaultHidden("key-b", shieldId, "com.x", "X", "t", "b", 2L, null, capture = capture(shieldId),
        )
        // Re-post of key-a while still hidden → update path → no increment.
        repo.upsertVaultHidden("key-a", shieldId, "com.x", "X", "t2", "b2", 3L, null, capture = capture(shieldId),
        )

        assertEquals(before + 2, com.jessemaddox.spoileralert.ui.AppPrefs.lifetimeHidden(context))
    }

    @Test fun conversationUpdatesWithDifferentKeysConsolidateWithoutRepeatedMessages() = runBlocking {
        val shieldId = insertShield(
            ShieldEntity(name = "Falcons", aliasesJson = "[]", kind = "TEAM")
        )
        repo.sessions.arm(shieldId)

        val firstId = repo.upsertVaultHidden(
            "whatsapp-1", shieldId, "com.whatsapp", "WhatsApp", "Paper Kite Workshop",
            "Mira: first", 1L, "Paper Kite Workshop", messageCount = 1, isConversation = true, capture = capture(shieldId),
        )
        val secondId = repo.upsertVaultHidden(
            "whatsapp-2", shieldId, "com.whatsapp", "WhatsApp", "Paper Kite Workshop (2 messages)",
            "Mira: first\nTheo: reply", 2L, "Paper Kite Workshop", messageCount = 2,
            isConversation = true, capture = capture(shieldId),
        )

        assertEquals(firstId, secondId)
        val hidden = db.vaultDao().hiddenForShield(shieldId).single()
        assertEquals("Mira: first\nTheo: reply", hidden.text)
        assertEquals(2, hidden.messageCount)
        assertEquals("whatsapp-2", hidden.notificationKey)
    }

    @Test fun reusedNotificationKeyPreservesEarlierSessionContent() = runBlocking {
        val openId = insertShield(
            ShieldEntity(name = "The Open", aliasesJson = "[]", kind = "GAME",
                armed = true, armedAtMillis = System.currentTimeMillis())
        )
        val worldCupId = insertShield(
            ShieldEntity(name = "FIFA World Cup", aliasesJson = "[]", kind = "GAME",
                armed = true, armedAtMillis = System.currentTimeMillis())
        )
        val rowId = repo.upsertVaultHidden(
            "espn-live", openId, "com.espn", "ESPN", "Round 3", "The Open", 1L, null, capture = capture(openId),
        )!!

        val newerId = repo.upsertVaultHidden(
            "espn-live", worldCupId, "com.espn", "ESPN", "Final", "World Cup", 2L, null, capture = capture(worldCupId),
        )

        assertEquals("Round 3", db.vaultDao().byId(rowId)!!.title)
        val updated = db.vaultDao().byId(newerId)!!
        assertEquals(worldCupId, updated.shieldId)
        assertEquals("Final", updated.title)
        assertEquals(2L, updated.postedAtMillis)
    }

    @Test fun vaultWriteFinishingAfterStopIsSavedAlreadyRevealed() = runBlocking {
        val shieldId = insertShield(
            ShieldEntity(name = "The Open", aliasesJson = "[]", kind = "GAME")
        )

        repo.sessions.arm(shieldId)
        val originalCapture = capture(shieldId)
        repo.stopAndReveal(shieldId, repo.revealScope(shieldId))
        val rowId = repo.upsertVaultHidden(
            "late-key", shieldId, "com.messages", "Messages", "Paper Kite Workshop",
            "What a finish", System.currentTimeMillis(), "Paper Kite Workshop", capture = originalCapture,
        )!!

        assertNotNull(db.vaultDao().byId(rowId)?.revealedAtMillis)
        assertEquals(emptyList<Long>(), db.vaultDao().allHidden().map { it.id })
    }

    @Test fun lateVaultWriteMovesToAnotherMatchingActiveShieldInsteadOfRevealing() = runBlocking {
        val aliases = ShieldCodec.encodeAliases(
            listOf(com.jessemaddox.spoileralert.domain.Alias("chiefs"))
        )
        val stoppedId = insertShield(ShieldEntity(
            name = "Chiefs", aliasesJson = aliases, kind = "TEAM",
        ))
        val activeId = insertShield(ShieldEntity(
            name = "BUF @ KC", aliasesJson = aliases, kind = "GAME",
            armed = true, armedAtMillis = System.currentTimeMillis(),
        ))

        repo.sessions.arm(stoppedId)
        repo.stopAndReveal(stoppedId, repo.revealScope(stoppedId))
        val rowId = repo.upsertVaultHidden(
            "late-overlap", stoppedId, "com.espn.score_center", "ESPN",
            "Chiefs update", "What a finish", System.currentTimeMillis(), null, capture = capture(stoppedId),
        )!!

        assertEquals(activeId, db.vaultDao().byId(rowId)?.shieldId)
        assertEquals(null, db.vaultDao().byId(rowId)?.revealedAtMillis)
        assertEquals(listOf(rowId), db.vaultDao().allHidden().map { it.id })
    }

    @Test fun equivalentHiddenOpenRowMovesToArmedGameShield() = runBlocking {
        val interestId = insertShield(
            ShieldEntity(
                name = "The Open Championship", aliasesJson = "[]", kind = "TEAM",
                armed = true, armedAtMillis = System.currentTimeMillis(),
            )
        )
        val gameId = insertShield(
            ShieldEntity(
                name = "The Open", aliasesJson = "[]", kind = "GAME",
                armed = true, armedAtMillis = System.currentTimeMillis(),
            )
        )
        val rowId = insertCaptured(VaultEntity(
            shieldId = interestId, sourcePackage = "com.google", sourceAppLabel = "Google",
            title = "The Open", text = "Update", postedAtMillis = 1L, notificationKey = "g",
        ))

        repo.reconcileEquivalentHiddenAttribution()

        assertEquals(gameId, db.vaultDao().byId(rowId)?.shieldId)
    }
}
