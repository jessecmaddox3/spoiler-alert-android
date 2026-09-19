package com.jessemaddox.spoileralert.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Migration 6→7 (league_games discovery pool) over a database with constructed existing rows. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrate6To7_preservesDataAndCreatesLeagueGames() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO shields (name, aliasesJson, kind, catalogTeamId, armed, autoDisarmHours, " +
                    "maskedAlerts, pregamePrompts) VALUES ('Falcons', '[]', 'TEAM', 'atl-falcons', 1, 4, 0, 1)"
            )
            db.execSQL(
                "INSERT INTO games (id, shieldId, name, shortName, startMillis, completed, fetchedAtMillis) " +
                    "VALUES ('fiction-migration-football', 1, 'Cedar Comets at Harbor Kites', 'CED @ HAR', " +
                    "1901901600000, 0, 1901232000000)"
            )
            db.execSQL(
                "INSERT INTO vault (shieldId, sourcePackage, sourceAppLabel, title, text, postedAtMillis, " +
                    "notificationKey) VALUES (1, 'com.espn', 'ESPN', 'Final', 'score', 1901232000000, 'k1')"
            )
        }

        helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7).use { db ->
            // Existing data survives.
            db.query("SELECT name, armed FROM shields").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Falcons", c.getString(0))
                assertEquals(1, c.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM games").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM vault").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }

            // New table exists, empty, and accepts a row with a null label.
            db.execSQL(
                "INSERT INTO league_games (eventId, leagueId, name, shortName, startMillis, completed, " +
                    "homeEspnId, awayEspnId, label, fetchedAtMillis) VALUES " +
                    "('fiction-migration-soccer', 'soccer', 'Amber Owls at Violet Finches', 'AMB @ VIO', 1901728800000, 0, 'fiction-owls', 'fiction-finches', " +
                    "NULL, 1901232000000)"
            )
            db.query("SELECT leagueId, label FROM league_games").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("soccer", c.getString(0))
                assertTrue(c.isNull(1))
            }
        }
    }

    /** Migration 7→8 (shields.gameEventId for session-scoped GAME shields) over constructed rows. */
    @Test fun migrate7To8_preservesDataAndAddsGameEventId() {
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO shields (name, aliasesJson, kind, catalogTeamId, armed, autoDisarmHours, " +
                    "maskedAlerts, pregamePrompts) VALUES ('Falcons', '[]', 'TEAM', 'atl-falcons', 1, 4, 0, 1)"
            )
            db.execSQL(
                "INSERT INTO vault (shieldId, sourcePackage, sourceAppLabel, title, text, postedAtMillis, " +
                    "notificationKey) VALUES (1, 'com.espn', 'ESPN', 'Final', 'score', 1901232000000, 'k1')"
            )
            db.execSQL(
                "INSERT INTO league_games (eventId, leagueId, name, shortName, startMillis, completed, " +
                    "homeEspnId, awayEspnId, label, fetchedAtMillis) VALUES " +
                    "('fiction-migration-soccer', 'soccer', 'Amber Owls at Violet Finches', 'AMB @ VIO', 1901728800000, 0, 'fiction-owls', 'fiction-finches', " +
                    "NULL, 1901232000000)"
            )
        }

        helper.runMigrationsAndValidate(DB, 8, true, AppDatabase.MIGRATION_7_8).use { db ->
            // Existing rows survive; the new column defaults to NULL.
            db.query("SELECT name, armed, gameEventId FROM shields").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Falcons", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertTrue(c.isNull(2))
            }
            db.query("SELECT COUNT(*) FROM vault").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM league_games").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }

            // A GAME shield with an event id round-trips.
            db.execSQL(
                "INSERT INTO shields (name, aliasesJson, kind, gameEventId, armed, autoDisarmHours, " +
                    "maskedAlerts, pregamePrompts) VALUES ('AMB @ VIO', '[]', 'GAME', 'fiction-migration-soccer', 0, 3, 0, 1)"
            )
            db.query("SELECT gameEventId FROM shields WHERE kind = 'GAME'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("fiction-migration-soccer", c.getString(0))
            }
        }
    }

    /** Migration 8 to 9 adds multi-day event windows and tournament phase metadata. */
    @Test fun migrate8To9_preservesLeagueGamesAndAddsEventMetadata() {
        helper.createDatabase(DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO league_games (eventId, leagueId, name, shortName, startMillis, completed, " +
                    "homeEspnId, awayEspnId, label, fetchedAtMillis) VALUES " +
                    "('fiction-meadow-open', 'golf', 'Meadow Open', 'Meadow Open', 1901505600000, 0, '', '', " +
                    "NULL, 1901505600000)"
            )
        }

        helper.runMigrationsAndValidate(DB, 9, true, AppDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT name, endMillis, phase FROM league_games").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Meadow Open", c.getString(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
            }
            db.execSQL(
                "UPDATE league_games SET endMillis = 1901851200000, phase = 'regular-season' " +
                    "WHERE eventId = 'fiction-meadow-open'"
            )
            db.query("SELECT endMillis, phase FROM league_games").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1901851200000L, c.getLong(0))
                assertEquals("regular-season", c.getString(1))
            }
        }
    }

    /** Migration 9 to 10 adds content-free conversation and source-action metadata. */
    @Test fun migrate9To10_preservesVaultAndAddsConversationMetadata() {
        helper.createDatabase(DB, 9).use { db ->
            db.execSQL(
                "INSERT INTO shields (name, aliasesJson, kind, armed, autoDisarmHours, " +
                    "maskedAlerts, pregamePrompts) VALUES ('Falcons', '[]', 'TEAM', 1, 4, 0, 1)"
            )
            db.execSQL(
                "INSERT INTO vault (shieldId, sourcePackage, sourceAppLabel, title, text, " +
                    "postedAtMillis, notificationKey, conversation) VALUES " +
                    "(1, 'com.whatsapp', 'WhatsApp', 'Family', 'message', 1, 'key', 'Family')"
            )
        }

        helper.runMigrationsAndValidate(DB, 10, true, AppDatabase.MIGRATION_9_10).use { db ->
            db.query(
                "SELECT conversation, messageCount, isConversation, isImportantConversation, " +
                    "sourceCategory, hasExactOpen, hasReplyAction, hasMarkUnreadAction FROM vault"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Family", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(3))
                assertTrue(c.isNull(4))
                assertEquals(0, c.getInt(5))
                assertEquals(0, c.getInt(6))
                assertEquals(0, c.getInt(7))
            }
        }
    }

    private companion object { const val DB = "migration-test.db" }
}
