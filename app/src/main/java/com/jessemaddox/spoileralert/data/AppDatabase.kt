package com.jessemaddox.spoileralert.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ShieldEntity::class, VaultEntity::class, GameEntity::class, LeagueGameEntity::class],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shieldDao(): ShieldDao
    abstract fun vaultDao(): VaultDao
    abstract fun gameDao(): GameDao
    abstract fun leagueGameDao(): LeagueGameDao

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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "spoiler-alert.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                ).build().also { instance = it }
            }
    }
}
