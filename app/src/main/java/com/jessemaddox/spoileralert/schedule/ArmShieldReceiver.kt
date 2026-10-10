package com.jessemaddox.spoileralert.schedule

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.widget.SpoilerTileService
import com.jessemaddox.spoileralert.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Hide until it's over" action on a pregame prompt: starts hiding for that game
 * through its expected finish WITHOUT opening the app, cancels the prompt, refreshes the widget. Posts nothing else — the widget and
 * app already reflect armed state. Only ever runs from an explicit user tap (consent).
 */
class ArmShieldReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ARM_SHIELD) return
        val shieldId = intent.getLongExtra(EXTRA_SHIELD_ID, -1L)
        if (shieldId == -1L) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val repo = ShieldRepository(app)
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
                // An older prompt without its game falls back to the shield's stored length.
                if (eventId != null) repo.startGameWindow(shieldId, eventId, explicit = true) else repo.arm(shieldId)
                app.getSystemService(NotificationManager::class.java)
                    .cancel(PregamePromptWorker.notificationId(shieldId))
                WidgetRefresher.refreshNow(app)
                SpoilerTileService.requestUpdate(app)
            }.onFailure { Log.w(TAG, "Arm from pregame prompt failed: $it") }
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "ArmShieldReceiver"
        const val ACTION_ARM_SHIELD = "com.jessemaddox.spoileralert.ARM_SHIELD"
        const val EXTRA_SHIELD_ID = "shieldId"
        const val EXTRA_EVENT_ID = "eventId"
    }
}
