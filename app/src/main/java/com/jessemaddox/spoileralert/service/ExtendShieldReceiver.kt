package com.jessemaddox.spoileralert.service

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
 * Explicit extension action: adds one hour to the current deadline, replaces the auto-stop and
 * check-in work, and advances the CAS token without opening the app. It then
 * clears the check-in prompt. Only ever runs from an explicit user tap.
 */
class ExtendShieldReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXTEND_SHIELD) return
        val shieldId = intent.getLongExtra(EXTRA_SHIELD_ID, -1L)
        if (shieldId == -1L) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                ShieldRepository(app).extend(shieldId)
                CheckInWorker.cancelNotification(app, shieldId)
                WidgetRefresher.refreshNow(app)
                SpoilerTileService.requestUpdate(app)
            }.onFailure { Log.w(TAG, "One-hour extension failed: $it") }
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "ExtendShieldReceiver"
        const val ACTION_EXTEND_SHIELD = "com.jessemaddox.spoileralert.EXTEND_SHIELD"
        const val EXTRA_SHIELD_ID = "shieldId"
    }
}
