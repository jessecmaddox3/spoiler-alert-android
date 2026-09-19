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
 * Explicit-reveal action for a shield, used by the check-in's "Stop blocking and reveal" and the
 * session-ended notification's "Reveal notifications": disarms (if armed), reveals everything the
 * shield hid — including sealed items after an unanswered expiry — updates the summary
 * notification, clears the check-in prompt. Only ever runs from an explicit user tap;
 * this IS the consent.
 */
class RevealShieldReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REVEAL_SHIELD) return
        val token = SessionActions.token(intent) ?: return
        val selection = SessionActions.selection(intent) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                (app as com.jessemaddox.spoileralert.SpoilerAlertApp).startupReady.await()
                val repo = ShieldRepository(app)
                repo.stopAndReveal(token.shieldId, selection)
                SummaryNotifier.refresh(app)
                WidgetRefresher.refreshNow(app)
                SpoilerTileService.requestUpdate(app)
            }.onFailure { Log.w(TAG, "Reveal from check-in failed: $it") }
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "RevealShieldReceiver"
        const val ACTION_REVEAL_SHIELD = "com.jessemaddox.spoileralert.REVEAL_SHIELD"
        const val EXTRA_SHIELD_ID = "shieldId"
    }
}
