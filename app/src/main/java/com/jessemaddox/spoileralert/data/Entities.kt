package com.jessemaddox.spoileralert.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "shields")
data class ShieldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** JSON-encoded List<Alias> (kotlinx.serialization). */
    val aliasesJson: String,
    val kind: String,                 // "TEAM" | "CUSTOM" | "GAME"
    val catalogTeamId: String? = null,
    /** GAME shields only: the protected matchup's ESPN event id (session-scoped shields,
     *  v3 amendment item 2). See [GameShieldLifecycle] for how these come and go. */
    val gameEventId: String? = null,
    val armed: Boolean = false,
    val armedAtMillis: Long? = null,
    val autoDisarmHours: Int = 4,
    /** When true, hiding a notification also posts a content-free stand-in alert. */
    val maskedAlerts: Boolean = false,
    /** TEAM shields only: ask (never arm silently) ~10 minutes before a scheduled game. */
    val pregamePrompts: Boolean = true,
)

/**
 * A cached game from the public schedule fetch (schedule module). Pure public metadata —
 * never derived from, or joined with, notification content.
 */
@Entity(
    tableName = "games",
    primaryKeys = ["id", "shieldId"],
    indices = [Index("shieldId"), Index("startMillis")],
)
data class GameEntity(
    /** ESPN event id. Not unique alone — the same event can belong to two shields
     *  (e.g. two shielded teams playing each other), so the key is (id, shieldId). */
    val id: String,
    val shieldId: Long,
    val name: String,
    val shortName: String,
    val startMillis: Long,
    val completed: Boolean,
    val fetchedAtMillis: Long,
)

/**
 * A cached game from a league-wide scoreboard fetch — the DISCOVERY pool behind the Games
 * browser. Distinct from [GameEntity] ("games"), which is per-shield (keyed by shieldId, drives
 * team cards / pregame prompts / the widget): league_games holds EVERY game in each supported
 * league's rolling window, whether or not either team is a user shield, so MNF/TNF/playoff
 * matchups are discoverable and armable. Rows where neither competitor maps to a catalog team
 * are kept on purpose. Pure public metadata, never joined with notification content.
 *
 * eventId alone is the key (no shield scoping); [homeEspnId]/[awayEspnId] are ESPN's numeric
 * competitor ids, resolved via TeamCatalog.teamByEspnId at arm time to pull both alias sets.
 */
@Entity(
    tableName = "league_games",
    indices = [Index("leagueId"), Index("startMillis")],
)
data class LeagueGameEntity(
    @PrimaryKey val eventId: String,
    /** Catalog league id (nfl, cfb, nba, mlb, epl, mls, soccer) — NOT ESPN's path form. */
    val leagueId: String,
    val name: String,
    val shortName: String,
    val startMillis: Long,
    val completed: Boolean,
    val homeEspnId: String,
    val awayEspnId: String,
    /** ESPN event-note headline when present ("NFL Melbourne Game", "NBA Play-In - …"). */
    val label: String?,
    val fetchedAtMillis: Long,
    /** Public event end when supplied. Important for multi-day golf and racing events. */
    val endMillis: Long? = null,
    /** ESPN season/round slug, e.g. "semifinals", "3rd-place-match", or "final". */
    val phase: String? = null,
)

@Entity(
    tableName = "vault",
    indices = [Index("shieldId"), Index("revealedAtMillis"), Index("notificationKey")],
)
data class VaultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shieldId: Long,
    val sourcePackage: String,
    val sourceAppLabel: String,
    val title: String,
    val text: String,
    val postedAtMillis: Long,
    val notificationKey: String,
    val revealedAtMillis: Long? = null,
    /** Conversation/channel (group chat name, or sender for 1:1). Metadata — safe pre-reveal. */
    val conversation: String? = null,
    /** MessagingStyle can represent several messages in one updated notification key. */
    val messageCount: Int = 1,
    /** Platform conversation classification and user-selected priority, both metadata only. */
    val isConversation: Boolean = false,
    val isImportantConversation: Boolean = false,
    /** Source category and transient action availability. No PendingIntent is persisted. */
    val sourceCategory: String? = null,
    val hasExactOpen: Boolean = false,
    val hasReplyAction: Boolean = false,
    val hasMarkUnreadAction: Boolean = false,
)
