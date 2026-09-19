package com.jessemaddox.spoileralert.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ShieldEntity::class, VaultEntity::class, GameEntity::class, LeagueGameEntity::class,
        ProtectionSessionEntity::class, CaptureOwnership::class, CaptureConversationAlias::class],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shieldDao(): ShieldDao
    abstract fun vaultDao(): VaultDao
    abstract fun gameDao(): GameDao
    abstract fun leagueGameDao(): LeagueGameDao
    abstract fun protectionSessionDao(): ProtectionSessionDao
    abstract fun captureOwnershipDao(): CaptureOwnershipDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shields ADD COLUMN maskedAlerts INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault ADD COLUMN conversation TEXT")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `games` (" +
                        "`id` TEXT NOT NULL, `shieldId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                        "`shortName` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, " +
                        "`completed` INTEGER NOT NULL, `fetchedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`, `shieldId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_shieldId` ON `games` (`shieldId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_startMillis` ON `games` (`startMillis`)")
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shields ADD COLUMN pregamePrompts INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Protection sessions default to 4h now (24h let a forgotten shield hide messages
         *  half a day). Shields still sitting on the old default inherit the new one;
         *  anyone who deliberately chose another value keeps it. */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE shields SET autoDisarmHours = 4 WHERE autoDisarmHours = 24")
            }
        }

        /** v3 amendment item 2: league-wide discovery pool behind the Games browser
         *  (see [LeagueGameEntity] for how it differs from the per-shield `games` table). */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `league_games` (" +
                        "`eventId` TEXT NOT NULL, `leagueId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`shortName` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, " +
                        "`completed` INTEGER NOT NULL, `homeEspnId` TEXT NOT NULL, " +
                        "`awayEspnId` TEXT NOT NULL, `label` TEXT, `fetchedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`eventId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_league_games_leagueId` ON `league_games` (`leagueId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_league_games_startMillis` ON `league_games` (`startMillis`)")
            }
        }

        /** v3 amendment item 2: session-scoped GAME shields remember their matchup's event id. */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shields ADD COLUMN gameEventId TEXT")
            }
        }

        /** v3.5 daily relevance: retain multi-day event windows and tournament phases. */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE league_games ADD COLUMN endMillis INTEGER")
                db.execSQL("ALTER TABLE league_games ADD COLUMN phase TEXT")
            }
        }

        /** v3.7 conversation rescue: retain only safe platform metadata needed for accurate
         * message counts, priority ordering, and capability diagnostics. Action tokens remain
         * transient and are never serialized into Room. */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE vault ADD COLUMN isConversation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vault ADD COLUMN isImportantConversation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vault ADD COLUMN sourceCategory TEXT")
                db.execSQL("ALTER TABLE vault ADD COLUMN hasExactOpen INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vault ADD COLUMN hasReplyAction INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vault ADD COLUMN hasMarkUnreadAction INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Backfill identities without inferring consent from old revealed rows or armed flags. */
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shields ADD COLUMN currentSessionId TEXT")
                db.execSQL("ALTER TABLE shields ADD COLUMN sessionRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vault ADD COLUMN captureSessionId TEXT")
                db.execSQL("ALTER TABLE vault ADD COLUMN ownerSessionId TEXT")
                db.execSQL("ALTER TABLE vault ADD COLUMN captureMatchMode TEXT NOT NULL DEFAULT 'LEGACY'")
                db.execSQL("CREATE TABLE IF NOT EXISTS protection_sessions (" +
                    "sessionId TEXT NOT NULL PRIMARY KEY, shieldId INTEGER NOT NULL, shieldKind TEXT NOT NULL, " +
                    "displayName TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, endedAtMillis INTEGER, " +
                    "endReason TEXT, revealAuthorizedAtMillis INTEGER)")
                db.execSQL("CREATE INDEX index_protection_sessions_shieldId ON protection_sessions(shieldId)")
                db.execSQL("CREATE INDEX index_vault_captureSessionId ON vault(captureSessionId)")
                db.execSQL("CREATE INDEX index_vault_ownerSessionId ON vault(ownerSessionId)")
                db.execSQL("CREATE INDEX index_vault_captureSessionId_sourcePackage_notificationKey " +
                    "ON vault(captureSessionId, sourcePackage, notificationKey)")
                db.execSQL("INSERT INTO protection_sessions " +
                    "SELECT 'legacy:' || id, id, kind, name, COALESCE(armedAtMillis, 0), " +
                    "CASE WHEN armed = 1 AND armedAtMillis IS NOT NULL THEN NULL ELSE 0 END, " +
                    "CASE WHEN armed = 1 AND armedAtMillis IS NOT NULL THEN NULL ELSE 'LEGACY' END, NULL FROM shields")
                db.execSQL("INSERT OR IGNORE INTO protection_sessions " +
                    "SELECT 'legacy:' || shieldId, shieldId, 'LEGACY', 'Earlier protection', 0, 0, 'LEGACY', NULL " +
                    "FROM vault GROUP BY shieldId")
                db.execSQL("UPDATE shields SET currentSessionId = 'legacy:' || id")
                db.execSQL("UPDATE vault SET captureSessionId = 'legacy:' || shieldId, ownerSessionId = 'legacy:' || shieldId")
                db.execSQL("CREATE TABLE capture_ownership (captureSessionId TEXT NOT NULL, sourcePackage TEXT NOT NULL, " +
                    "notificationKeyHash TEXT NOT NULL, conversationHash TEXT, ownerSessionId TEXT NOT NULL, " +
                    "consentSessionIdsJson TEXT NOT NULL DEFAULT '[]', " +
                    "PRIMARY KEY(captureSessionId, sourcePackage, notificationKeyHash))")
                db.execSQL("CREATE INDEX index_capture_ownership_ownerSessionId ON capture_ownership(ownerSessionId)")
                db.execSQL("CREATE INDEX index_capture_ownership_captureSessionId_sourcePackage_conversationHash " +
                    "ON capture_ownership(captureSessionId, sourcePackage, conversationHash)")
                db.execSQL("CREATE TABLE capture_conversation_aliases (captureSessionId TEXT NOT NULL, " +
                    "sourcePackage TEXT NOT NULL, aliasHash TEXT NOT NULL, conversationHash TEXT NOT NULL, " +
                    "PRIMARY KEY(captureSessionId, sourcePackage, aliasHash))")
                db.execSQL("CREATE INDEX index_capture_conversation_aliases_captureSessionId_sourcePackage_conversationHash " +
                    "ON capture_conversation_aliases(captureSessionId, sourcePackage, conversationHash)")
                fun existingGroup(table: String, keyColumn: String, session: String, source: String, key: String): String? =
                    db.query("SELECT conversationHash FROM $table WHERE captureSessionId = ? AND sourcePackage = ? AND $keyColumn = ?",
                        arrayOf(session, source, key)).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
                db.query("SELECT captureSessionId, sourcePackage, notificationKey, conversation, ownerSessionId, isConversation FROM vault ORDER BY id").use { cursor ->
                    while (cursor.moveToNext()) {
                        val session = cursor.getString(0)
                        val source = cursor.getString(1)
                        val key = captureIdentityHash(session, "key", cursor.getString(2))
                        val incoming = if (!cursor.isNull(3) && cursor.getInt(5) != 0 && cursor.getString(3).isNotBlank())
                            captureConversationHash(session, cursor.getString(3)) else null
                        val keyedGroup = existingGroup("capture_ownership", "notificationKeyHash", session, source, key)
                        val incomingGroup = incoming?.let { existingGroup("capture_conversation_aliases", "aliasHash", session, source, it) ?: it }
                        val conversation = keyedGroup ?: incomingGroup
                        if (conversation != null) {
                            if (incomingGroup != null && incomingGroup != conversation) {
                                for (table in listOf("capture_ownership", "capture_conversation_aliases")) {
                                    db.execSQL("UPDATE $table SET conversationHash = ? WHERE captureSessionId = ? AND sourcePackage = ? AND conversationHash = ?",
                                        arrayOf(conversation, session, source, incomingGroup))
                                }
                            }
                            for (alias in listOfNotNull(incoming, keyedGroup).distinct()) {
                                db.execSQL("INSERT OR IGNORE INTO capture_conversation_aliases VALUES(?,?,?,?)",
                                    arrayOf(session, source, alias, conversation))
                            }
                        }
                        db.execSQL("INSERT OR REPLACE INTO capture_ownership VALUES(?,?,?,?,?,?)", arrayOf(
                            session, source, key,
                            conversation, cursor.getString(4), ConsentGuards.encode(setOf(session, cursor.getString(4)))))
                    }
                }
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        )

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "spoiler-alert.db"
                ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
            }
    }
}
