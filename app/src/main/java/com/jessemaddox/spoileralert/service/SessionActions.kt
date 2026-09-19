package com.jessemaddox.spoileralert.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jessemaddox.spoileralert.data.SessionToken
import com.jessemaddox.spoileralert.data.RevealSelection
import com.jessemaddox.spoileralert.data.captureIdentityHash
import kotlinx.coroutines.sync.Mutex

/** Serializes committed lifecycle changes and bounded local effects across repository instances.
 * Never hold this lock while fetching provider data or waiting for a scheduled worker. */
object SessionEffects {
    val mutex = Mutex()
}

/** Identity is part of PendingIntent.data, not merely mutable extras or an integer hash. */
object SessionActions {
    private const val SESSION = "protectionSession"
    private const val REVISION = "protectionRevision"
    private const val SHIELD = "shieldId"
    private const val SELECTED = "selectedSessions"
    private const val FANTASY = "selectedFantasySessions"

    fun data(token: SessionToken): Data = workDataOf(
        SHIELD to token.shieldId, SESSION to token.sessionId, REVISION to token.revision,
    )

    fun token(data: Data): SessionToken? {
        val id = data.getLong(SHIELD, -1)
        val session = data.getString(SESSION)?.takeIf { it.isNotBlank() } ?: return null
        val revision = data.getLong(REVISION, -1)
        return if (id >= 0 && revision >= 0) SessionToken(id, session, revision) else null
    }

    fun token(intent: Intent): SessionToken? {
        val id = intent.getLongExtra(SHIELD, -1)
        val session = intent.getStringExtra(SESSION)?.takeIf { it.isNotBlank() } ?: return null
        val revision = intent.getLongExtra(REVISION, -1)
        return if (id >= 0 && revision >= 0) SessionToken(id, session, revision) else null
    }

    fun bind(intent: Intent, token: SessionToken, action: String, selection: RevealSelection? = null): Intent =
        intent.setData(Uri.Builder().scheme("spoiler-alert").authority("session")
            .appendPath(token.sessionId).appendPath(token.revision.toString()).appendPath(action)
            .appendPath(selection?.let {
                captureIdentityHash(token.sessionId, "selection", it.sessionIds.sorted().joinToString("\u0000") +
                    "\u0001" + it.fantasySessionIds.sorted().joinToString("\u0000"))
            } ?: "view").build())
            .putExtra(SHIELD, token.shieldId).putExtra(SESSION, token.sessionId)
            .putExtra(REVISION, token.revision).apply {
                selection?.let {
                    putExtra(SELECTED, it.sessionIds.toTypedArray())
                    putExtra(FANTASY, it.fantasySessionIds.toTypedArray())
                }
            }

    fun selection(intent: Intent): RevealSelection? {
        val token = token(intent) ?: return null
        val ids = intent.getStringArrayExtra(SELECTED)?.toSet() ?: return null
        if (token.sessionId !in ids || ids.any { it.isBlank() }) return null
        val fantasy = intent.getStringArrayExtra(FANTASY)?.toSet() ?: return null
        return RevealSelection(ids, fantasy)
    }

    fun workName(kind: String, token: SessionToken) = "$kind:${token.scope}"
    fun tag(token: SessionToken) = "protection:${token.scope}"

    /** A worker never cancels itself after committing expiry. Other generations are untouched. */
    fun cancel(context: Context, token: SessionToken, exceptWork: String? = null) {
        val wm = WorkManager.getInstance(context)
        for (kind in listOf("auto-disarm", "checkin", "event-completion")) {
            if (kind != exceptWork) wm.cancelUniqueWork(workName(kind, token))
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        for (id in listOf(END_ID, CHECKIN_ID, COMPLETION_ID, SUMMARY_ID)) nm.cancel(tag(token), id)
    }

    const val END_ID = 1
    const val CHECKIN_ID = 2
    const val COMPLETION_ID = 3
    const val SUMMARY_ID = 4
}
