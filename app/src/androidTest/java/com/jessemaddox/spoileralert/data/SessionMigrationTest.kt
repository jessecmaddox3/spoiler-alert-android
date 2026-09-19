package com.jessemaddox.spoileralert.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun versionTenPreservesContentAndNeverInfersRevealConsent() {
        val name = "synthetic-session-migration"
        helper.createDatabase(name, 10).use { db ->
            db.execSQL("INSERT INTO shields(id,name,aliasesJson,kind,armed,armedAtMillis,autoDisarmHours,maskedAlerts,pregamePrompts) " +
                "VALUES(1,'Cedar Comets','[]','CUSTOM',1,100,4,0,1)," +
                "(2,'Harbor Kites','[]','CUSTOM',0,NULL,4,0,1),(3,'Fantasy','[]','FANTASY',0,NULL,4,0,0)")
            for ((id, owner, revealed) in listOf(Triple(11,1,"NULL"), Triple(12,2,"321"), Triple(13,99,"NULL"), Triple(14,3,"NULL"),
                Triple(15,1,"NULL"), Triple(16,1,"NULL"))) {
                db.execSQL("INSERT INTO vault(id,shieldId,sourcePackage,sourceAppLabel,title,text,postedAtMillis,notificationKey," +
                    "revealedAtMillis,messageCount,isConversation,isImportantConversation,hasExactOpen,hasReplyAction,hasMarkUnreadAction) " +
                    "VALUES($id,$owner,'example.demo','Demo','Invented title','Invented body',123,'key-$id',$revealed,1,0,0,0,0,0)")
            }
            db.execSQL("UPDATE vault SET conversation='Invented old chat', isConversation=1 WHERE id IN (11,16)")
            db.execSQL("UPDATE vault SET notificationKey='key-11', conversation='Invented renamed chat', isConversation=1 WHERE id=15")
        }
        helper.runMigrationsAndValidate(name, 11, true, AppDatabase.MIGRATION_10_11).use { db ->
            db.query("SELECT COUNT(*), SUM(revealAuthorizedAtMillis IS NOT NULL) FROM protection_sessions").use {
                assertTrue(it.moveToFirst()); assertEquals(4,it.getInt(0)); assertEquals(0,it.getInt(1))
            }
            db.query("SELECT id,shieldId,text,revealedAtMillis,captureSessionId,ownerSessionId,captureMatchMode FROM vault ORDER BY id").use {
                for ((id, owner, revealed) in listOf(Triple(11,1,null),Triple(12,2,321L),Triple(13,99,null),Triple(14,3,null),
                    Triple(15,1,null),Triple(16,1,null))) {
                    assertTrue(it.moveToNext()); assertEquals(id,it.getInt(0)); assertEquals(owner,it.getInt(1))
                    assertEquals("Invented body",it.getString(2))
                    if (revealed == null) assertTrue(it.isNull(3)) else assertEquals(revealed.toLong(),it.getLong(3))
                    assertEquals("legacy:$owner",it.getString(4)); assertEquals("legacy:$owner",it.getString(5))
                    assertEquals("LEGACY",it.getString(6))
                }
                assertFalse(it.moveToNext())
            }
            db.query("SELECT armed,armedAtMillis,currentSessionId,sessionRevision FROM shields WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)); assertEquals(100L,it.getLong(1))
                assertEquals("legacy:1",it.getString(2));assertEquals(0L,it.getLong(3))
            }
            db.query("SELECT displayName, endedAtMillis FROM protection_sessions WHERE sessionId='legacy:99'").use {
                assertTrue(it.moveToFirst());assertEquals("Earlier protection",it.getString(0));assertEquals(0L,it.getLong(1))
            }
            val group = captureConversationHash("legacy:1", "Invented old chat")
            db.query("SELECT conversationHash,consentSessionIdsJson FROM capture_ownership WHERE captureSessionId='legacy:1'").use {
                var count = 0
                while (it.moveToNext()) {
                    assertEquals(group,it.getString(0)); assertEquals(ConsentGuards.encode(setOf("legacy:1")),it.getString(1)); count++
                }
                assertEquals(2,count)
            }
            db.query("SELECT conversationHash FROM capture_conversation_aliases WHERE captureSessionId='legacy:1'").use {
                var count = 0
                while (it.moveToNext()) { assertEquals(group,it.getString(0)); count++ }
                assertEquals(2,count)
            }
        }
    }

    @Test fun completeMigrationChainFromVersionOneRemainsUsable() {
        val name = "synthetic-original-migration"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO shields(id,name,aliasesJson,kind,armed,armedAtMillis,autoDisarmHours) " +
                "VALUES(1,'Cedar Comets','[]','CUSTOM',1,100,4)")
        }
        helper.runMigrationsAndValidate(name,11,true,*AppDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT name,currentSessionId FROM shields").use {
                assertTrue(it.moveToFirst());assertEquals("Cedar Comets",it.getString(0));assertEquals("legacy:1",it.getString(1))
            }
            db.query("SELECT revealAuthorizedAtMillis FROM protection_sessions").use {
                assertTrue(it.moveToFirst());assertTrue(it.isNull(0))
            }
        }
    }
}
