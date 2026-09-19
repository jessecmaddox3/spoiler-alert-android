package com.jessemaddox.spoileralert.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShieldDao {
    @Transaction
    @Query("SELECT * FROM shields ORDER BY id")
    suspend fun protectionRecords(): List<ShieldWithSession>

    @Transaction
    @Query("SELECT * FROM shields ORDER BY id")
    fun observeProtectionRecords(): Flow<List<ShieldWithSession>>

    @Query("SELECT * FROM shields ORDER BY name")
    suspend fun all(): List<ShieldEntity>

    @Query("SELECT * FROM shields ORDER BY name")
    fun observeAll(): Flow<List<ShieldEntity>>

    @Query("SELECT * FROM shields WHERE armed = 1")
    suspend fun armed(): List<ShieldEntity>

    @Query("SELECT * FROM shields WHERE armed = 1")
    fun observeArmed(): Flow<List<ShieldEntity>>

    @Query("SELECT * FROM shields WHERE id = :id")
    suspend fun byId(id: Long): ShieldEntity?

    @Query("SELECT * FROM shields WHERE kind = 'TEAM'")
    suspend fun teamShields(): List<ShieldEntity>

    /** Shields that carry cached games (pregame prompts, debug tools). */
    @Query("SELECT * FROM shields WHERE kind IN ('TEAM', 'GAME')")
    suspend fun schedulableShields(): List<ShieldEntity>

    /** Existing GAME shield for a matchup, so protecting the same game twice reuses one shield. */
    @Query("SELECT * FROM shields WHERE gameEventId = :eventId LIMIT 1")
    suspend fun byGameEventId(eventId: String): ShieldEntity?

    /** Existing TEAM shield for a catalog team, so re-onboarding (or adding the same team twice)
     *  reuses one shield instead of forking a duplicate — see [ShieldRepository.addTeamShield]. */
    @Query("SELECT * FROM shields WHERE kind = 'TEAM' AND catalogTeamId = :catalogTeamId LIMIT 1")
    suspend fun byCatalogTeamId(catalogTeamId: String): ShieldEntity?

    /** The single persistent fantasy sentinel shield (v3 amendment item 3), if it's been
     *  created yet — see [ShieldRepository.fantasyShieldId]. */
    @Query("SELECT * FROM shields WHERE kind = 'FANTASY' LIMIT 1")
    suspend fun fantasyShield(): ShieldEntity?

    /** Spent GAME shields with no vault rows left AND no cached game newer than [gameCutoff]:
     *  nothing renders them anywhere, so the orphan prune can delete them. See
     *  [GameShieldLifecycle] — a "Protect later" shield survives via its upcoming games row. */
    @Query(
        "SELECT id FROM shields WHERE kind = 'GAME' AND armed = 0 " +
            "AND id NOT IN (SELECT shieldId FROM vault) " +
            "AND id NOT IN (SELECT shieldId FROM games WHERE startMillis >= :gameCutoff)"
    )
    suspend fun orphanedGameShieldIds(gameCutoff: Long): List<Long>

    @Insert
    suspend fun insert(shield: ShieldEntity): Long

    @Update
    suspend fun update(shield: ShieldEntity)

    @Query("DELETE FROM shields WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE shields SET armed = :armed, armedAtMillis = :armedAt WHERE id = :id")
    suspend fun setArmed(id: Long, armed: Boolean, armedAt: Long?)

    /** Disarm every shield unconditionally — startup reconciliation before onboarding completes
     *  ([ShieldRepository.reconcileUnonboarded]). Unlike the CAS disarms, this has no race to
     *  guard: it only ever runs pre-onboarding, when the sole armed shield is a stranded demo
     *  session and no user session exists to protect. */
    @Query("UPDATE shields SET armed = 0, armedAtMillis = NULL WHERE armed = 1")
    suspend fun disarmAll(): Int

    /** Disarm only if armedAtMillis still matches what the caller observed. Returns rows updated.
     * An Extend advances armedAtMillis, so an old expiry worker's CAS fails and touches nothing. */
    @Query("UPDATE shields SET armed = 0, armedAtMillis = NULL WHERE id = :id AND armed = 1 AND armedAtMillis = :expectedArmedAt")
    suspend fun disarmIfArmedAt(id: Long, expectedArmedAt: Long): Int

    @Query("UPDATE shields SET maskedAlerts = :enabled WHERE id = :id")
    suspend fun setMaskedAlerts(id: Long, enabled: Boolean)

    @Query("UPDATE shields SET autoDisarmHours = :hours WHERE id = :id")
    suspend fun setAutoDisarmHours(id: Long, hours: Int)

    /** Exact one-hour extension. CAS prevents a stale tap/worker race from overwriting a newer
     * session; deadline remains derived from armedAtMillis + autoDisarmHours. */
    @Query("UPDATE shields SET armedAtMillis = :newArmedAt " +
        "WHERE id = :id AND armed = 1 AND armedAtMillis = :expectedArmedAt")
    suspend fun extendIfArmedAt(
        id: Long,
        expectedArmedAt: Long,
        newArmedAt: Long,
    ): Int

    @Query("UPDATE shields SET pregamePrompts = :enabled WHERE id = :id")
    suspend fun setPregamePrompts(id: Long, enabled: Boolean)

    /** GAME shields, for the schedule refresh's re-derive pass ([ShieldRepository.refreshGameShield]). */
    @Query("SELECT * FROM shields WHERE kind = 'GAME'")
    suspend fun gameShields(): List<ShieldEntity>

    /** Re-derived name/aliases only (collated review finding 2): a narrow column update so a
     *  concurrent arm/disarm/extend's armed + armedAtMillis are never touched, regardless of
     *  interleaving — no CAS needed because this simply never reads or writes those columns. */
    @Query("UPDATE shields SET name = :name, aliasesJson = :aliasesJson WHERE id = :id")
    suspend fun updateGameShieldDerived(id: Long, name: String, aliasesJson: String)

    /** Re-derived aliases only (TEAM shields — [ShieldRepository.refreshTeamShield]). Like
     *  [updateGameShieldDerived], never touches armed/armedAtMillis, so no CAS is needed. */
    @Query("UPDATE shields SET aliasesJson = :aliasesJson WHERE id = :id")
    suspend fun updateShieldAliases(id: Long, aliasesJson: String)
}

@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<GameEntity>)

    @Query("SELECT * FROM games WHERE shieldId = :shieldId AND completed = 0 AND startMillis >= :nowMillis ORDER BY startMillis")
    suspend fun upcomingForShield(shieldId: Long, nowMillis: Long): List<GameEntity>

    @Query("SELECT * FROM games WHERE completed = 0 AND startMillis >= :nowMillis ORDER BY startMillis LIMIT 1")
    suspend fun nextUpcoming(nowMillis: Long): GameEntity?

    @Query("SELECT * FROM games WHERE completed = 0 AND startMillis >= :nowMillis ORDER BY startMillis")
    fun observeUpcoming(nowMillis: Long): Flow<List<GameEntity>>

    /** Like [observeUpcoming] but with a look-back, so an in-progress game can still name
     *  its session (see [com.jessemaddox.spoileralert.ui.MatchupNames]). */
    @Query("SELECT * FROM games WHERE completed = 0 AND startMillis >= :sinceMillis ORDER BY startMillis")
    fun observeSince(sinceMillis: Long): Flow<List<GameEntity>>

    /** Recent protected games for spoiler-free questions. Includes completed rows so "Is it
     *  over?" remains answerable after the schedule refresh observes the final whistle. */
    @Query("SELECT * FROM games WHERE startMillis >= :sinceMillis ORDER BY startMillis")
    fun observeSinceIncludingCompleted(sinceMillis: Long): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :eventId AND shieldId = :shieldId")
    suspend fun byKey(eventId: String, shieldId: Long): GameEntity?

    @Query("DELETE FROM games WHERE shieldId = :shieldId")
    suspend fun deleteForShield(shieldId: Long)

    @Query("DELETE FROM games WHERE completed = 1 AND startMillis < :cutoff")
    suspend fun deleteCompletedBefore(cutoff: Long)
}

@Dao
interface LeagueGameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<LeagueGameEntity>)

    /** Upcoming games, optionally filtered to one league (null = all leagues). */
    @Query(
        "SELECT * FROM league_games WHERE (:leagueId IS NULL OR leagueId = :leagueId) " +
            "AND completed = 0 AND (startMillis >= :nowMillis OR endMillis >= :nowMillis) " +
            "ORDER BY startMillis LIMIT :limit"
    )
    suspend fun upcoming(leagueId: String?, nowMillis: Long, limit: Int): List<LeagueGameEntity>

    @Query(
        "SELECT * FROM league_games WHERE (:leagueId IS NULL OR leagueId = :leagueId) " +
            "AND completed = 0 AND (startMillis >= :nowMillis OR endMillis >= :nowMillis) " +
            "ORDER BY startMillis LIMIT :limit"
    )
    fun observeUpcoming(leagueId: String?, nowMillis: Long, limit: Int): Flow<List<LeagueGameEntity>>

    /** Public metadata for live viewing and recordings, including completed events. */
    @Query(
        "SELECT * FROM league_games WHERE startMillis >= :sinceMillis OR endMillis >= :sinceMillis " +
            "ORDER BY startMillis LIMIT :limit"
    )
    fun observeRecent(sinceMillis: Long, limit: Int): Flow<List<LeagueGameEntity>>

    @Query("SELECT * FROM league_games WHERE eventId = :eventId")
    suspend fun byId(eventId: String): LeagueGameEntity?

    /** Keep completed metadata until the recording window ends; no scores are stored here. */
    @Query(
        "DELETE FROM league_games WHERE " +
            "(endMillis IS NULL AND startMillis < :cutoff) OR endMillis < :cutoff"
    )
    suspend fun pruneBefore(cutoff: Long)

    @Query("DELETE FROM league_games")
    suspend fun deleteAll()
}

@Dao
interface VaultDao {
    @Update
    suspend fun update(item: VaultEntity)

    @Query("SELECT * FROM vault WHERE captureSessionId = :sessionId AND sourcePackage = :sourcePackage " +
        "AND notificationKey = :key ORDER BY id DESC LIMIT 1")
    suspend fun latestForCapture(sessionId: String, sourcePackage: String, key: String): VaultEntity?

    @Query("SELECT * FROM vault WHERE captureSessionId = :sessionId AND sourcePackage = :sourcePackage " +
        "AND conversation = :conversation ORDER BY postedAtMillis DESC, id DESC LIMIT 1")
    suspend fun latestConversationForCapture(sessionId: String, sourcePackage: String, conversation: String): VaultEntity?

    @Query("SELECT * FROM vault WHERE ownerSessionId IN (:sessionIds) AND revealedAtMillis IS NULL")
    suspend fun hiddenOwnedBy(sessionIds: List<String>): List<VaultEntity>

    @Query("SELECT DISTINCT ownerSessionId FROM vault WHERE shieldId = :shieldId AND ownerSessionId IS NOT NULL " +
        "AND revealedAtMillis IS NULL")
    suspend fun hiddenOwnerSessions(shieldId: Long): List<String>

    @Query("SELECT * FROM vault WHERE revealedAtMillis IS NULL ORDER BY postedAtMillis DESC")
    fun observeHidden(): Flow<List<VaultEntity>>

    @Query("SELECT * FROM vault ORDER BY postedAtMillis DESC")
    fun observeAll(): Flow<List<VaultEntity>>

    @Query("SELECT COUNT(*) FROM vault WHERE revealedAtMillis IS NULL")
    suspend fun hiddenCount(): Int

    /** Metadata-only observer for surfaces (widget) that must never see vault content. */
    @Query("SELECT COUNT(*) FROM vault WHERE revealedAtMillis IS NULL")
    fun observeHiddenCount(): Flow<Int>

    @Query("SELECT * FROM vault WHERE shieldId = :shieldId AND revealedAtMillis IS NULL")
    suspend fun hiddenForShield(shieldId: Long): List<VaultEntity>

    /** Every still-hidden row, regardless of shield. Startup reconciliation inspects these to
     *  remove only the app's stranded self-posted fake while preserving genuine alerts. */
    @Query("SELECT * FROM vault WHERE revealedAtMillis IS NULL")
    suspend fun allHidden(): List<VaultEntity>

    /** All rows (hidden + revealed) for a shield — GAME-shield lifecycle decisions. */
    @Query("SELECT COUNT(*) FROM vault WHERE shieldId = :shieldId")
    suspend fun countForShield(shieldId: Long): Int

    @Query("SELECT * FROM vault WHERE id = :id")
    suspend fun byId(id: Long): VaultEntity?

    @Insert
    suspend fun insert(item: VaultEntity): Long

    @Query("UPDATE vault SET revealedAtMillis = :now WHERE id = :id")
    suspend fun reveal(id: Long, now: Long)

    /** Hard-delete a single row — used to tear down the onboarding live-demo item so it never
     *  lingers in the user's history. */
    @Query("DELETE FROM vault WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE vault SET revealedAtMillis = :now WHERE shieldId = :shieldId AND revealedAtMillis IS NULL")
    suspend fun revealAllForShield(shieldId: Long, now: Long)

    @Query("DELETE FROM vault WHERE revealedAtMillis IS NOT NULL AND revealedAtMillis < :cutoff")
    suspend fun pruneRevealedBefore(cutoff: Long)

    @Query("DELETE FROM vault WHERE revealedAtMillis IS NOT NULL")
    suspend fun deleteRevealed(): Int

    /** Refresh a still-hidden row for a re-posted notification (same key). Returns rows updated (0 = insert instead). */
    @Query("UPDATE vault SET shieldId = :shieldId, sourcePackage = :sourcePackage, " +
        "sourceAppLabel = :sourceAppLabel, title = :title, text = :text, " +
        "postedAtMillis = :postedAt, conversation = :conversation, messageCount = :messageCount, " +
        "isConversation = :isConversation, isImportantConversation = :isImportantConversation, " +
        "sourceCategory = :sourceCategory, hasExactOpen = :hasExactOpen, " +
        "hasReplyAction = :hasReplyAction, hasMarkUnreadAction = :hasMarkUnreadAction " +
        "WHERE notificationKey = :key AND revealedAtMillis IS NULL")
    suspend fun updateHiddenByKey(
        key: String,
        shieldId: Long,
        sourcePackage: String,
        sourceAppLabel: String,
        title: String,
        text: String,
        postedAt: Long,
        conversation: String?,
        messageCount: Int,
        isConversation: Boolean,
        isImportantConversation: Boolean,
        sourceCategory: String?,
        hasExactOpen: Boolean,
        hasReplyAction: Boolean,
        hasMarkUnreadAction: Boolean,
    ): Int

    @Query("UPDATE vault SET shieldId = :shieldId WHERE id = :itemId AND revealedAtMillis IS NULL")
    suspend fun reattributeHidden(itemId: Long, shieldId: Long)

    @Query("SELECT id FROM vault WHERE notificationKey = :key AND revealedAtMillis IS NULL LIMIT 1")
    suspend fun hiddenIdByKey(key: String): Long?

    @Query("SELECT * FROM vault WHERE notificationKey = :key ORDER BY id DESC LIMIT 1")
    suspend fun latestByNotificationKey(key: String): VaultEntity?

    /** Stable chat row when Android changes the notification key as a conversation grows. */
    @Query("SELECT * FROM vault WHERE shieldId = :shieldId AND sourcePackage = :sourcePackage " +
        "AND conversation = :conversation AND revealedAtMillis IS NULL ORDER BY postedAtMillis DESC LIMIT 1")
    suspend fun hiddenConversation(
        shieldId: Long,
        sourcePackage: String,
        conversation: String,
    ): VaultEntity?

    @Query("UPDATE vault SET shieldId = :shieldId, sourcePackage = :sourcePackage, " +
        "sourceAppLabel = :sourceAppLabel, title = :title, text = :text, " +
        "postedAtMillis = :postedAt, notificationKey = :notificationKey, " +
        "conversation = :conversation, messageCount = :messageCount, " +
        "isConversation = :isConversation, isImportantConversation = :isImportantConversation, " +
        "sourceCategory = :sourceCategory, hasExactOpen = :hasExactOpen, " +
        "hasReplyAction = :hasReplyAction, hasMarkUnreadAction = :hasMarkUnreadAction WHERE id = :id")
    suspend fun updateContentById(
        id: Long,
        shieldId: Long,
        sourcePackage: String,
        sourceAppLabel: String,
        title: String,
        text: String,
        postedAt: Long,
        notificationKey: String,
        conversation: String?,
        messageCount: Int,
        isConversation: Boolean,
        isImportantConversation: Boolean,
        sourceCategory: String?,
        hasExactOpen: Boolean,
        hasReplyAction: Boolean,
        hasMarkUnreadAction: Boolean,
    )
}

@Dao
interface ProtectionSessionDao {
    @Query("SELECT * FROM protection_sessions")
    suspend fun all(): List<ProtectionSessionEntity>

    @Query("SELECT * FROM protection_sessions")
    fun observeAll(): Flow<List<ProtectionSessionEntity>>

    @Query("SELECT * FROM protection_sessions WHERE sessionId = :id")
    suspend fun byId(id: String): ProtectionSessionEntity?

    @Query("SELECT * FROM protection_sessions WHERE shieldId = :shieldId ORDER BY createdAtMillis, sessionId")
    suspend fun forShield(shieldId: Long): List<ProtectionSessionEntity>

    @Query("SELECT * FROM protection_sessions WHERE shieldKind = 'FANTASY'")
    suspend fun fantasySessions(): List<ProtectionSessionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: ProtectionSessionEntity): Long

    @Query("UPDATE protection_sessions SET endedAtMillis = COALESCE(endedAtMillis, :now), " +
        "endReason = CASE WHEN endedAtMillis IS NULL THEN :reason ELSE endReason END WHERE sessionId = :id")
    suspend fun end(id: String, now: Long, reason: String)

    @Query("UPDATE protection_sessions SET revealAuthorizedAtMillis = COALESCE(revealAuthorizedAtMillis, :now) " +
        "WHERE sessionId IN (:ids)")
    suspend fun authorizeReveal(ids: List<String>, now: Long)
}

@Dao
interface CaptureOwnershipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(binding: CaptureOwnership)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun rememberAlias(alias: CaptureConversationAlias)

    @Query("SELECT * FROM capture_conversation_aliases WHERE captureSessionId = :sessionId " +
        "AND sourcePackage = :source AND aliasHash = :hash")
    suspend fun alias(sessionId: String, source: String, hash: String): CaptureConversationAlias?

    @Query("UPDATE capture_conversation_aliases SET conversationHash = :target WHERE captureSessionId = :sessionId " +
        "AND sourcePackage = :source AND conversationHash = :old")
    suspend fun mergeAliases(sessionId: String, source: String, old: String, target: String)

    @Query("UPDATE capture_ownership SET conversationHash = :target, consentSessionIdsJson = :guards " +
        "WHERE captureSessionId = :sessionId AND sourcePackage = :source AND conversationHash = :old")
    suspend fun mergeConversation(sessionId: String, source: String, old: String, target: String, guards: String)

    @Query("SELECT * FROM capture_ownership WHERE captureSessionId = :sessionId AND sourcePackage = :source " +
        "AND notificationKeyHash = :keyHash")
    suspend fun byKey(sessionId: String, source: String, keyHash: String): CaptureOwnership?

    @Query("SELECT * FROM capture_ownership WHERE captureSessionId = :sessionId AND sourcePackage = :source " +
        "AND conversationHash = :conversationHash LIMIT 1")
    suspend fun forConversation(sessionId: String, source: String, conversationHash: String): CaptureOwnership?

    @Query("SELECT * FROM capture_ownership WHERE captureSessionId = :sessionId AND sourcePackage = :source " +
        "AND conversationHash = :conversationHash")
    suspend fun allForConversation(sessionId: String, source: String, conversationHash: String): List<CaptureOwnership>

    @Query("UPDATE capture_ownership SET ownerSessionId = :owner, consentSessionIdsJson = :guards WHERE captureSessionId = :sessionId " +
        "AND sourcePackage = :source AND conversationHash = :conversationHash")
    suspend fun reassignConversation(sessionId: String, source: String, conversationHash: String, owner: String, guards: String)
}
