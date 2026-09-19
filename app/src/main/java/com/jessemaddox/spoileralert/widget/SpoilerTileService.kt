package com.jessemaddox.spoileralert.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arm-only Quick Settings tile (v3 amendment item 9). NEVER disarms or reveals from the tile —
 * same safety rule as the widget: an accidental tap must never expose spoilers.
 *
 * Idle (nothing armed): tap arms the single unambiguous next game ([TileDecision]) or, when
 * ambiguous or nothing is schedulable, collapses the shade and opens the app so the user
 * chooses. Active (anything armed): tap always opens the app to Home (session details) — the
 * tile itself has no "turn off" affordance, ever.
 */
class SpoilerTileService : TileService() {
    private var job = SupervisorJob()
    private val scope get() = CoroutineScope(Dispatchers.Default + job)

    override fun onStartListening() {
        super.onStartListening()
        job.cancel()
        job = SupervisorJob()
        scope.launch { render(TileState.snapshot(applicationContext)) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val state = TileState.snapshot(applicationContext)
            if (state.armed) {
                openApp()
                return@launch
            }
            when (val action = TileDecision.decide(state.idleCandidates)) {
                is TileDecision.Action.Arm -> {
                    runCatching { ShieldRepository(applicationContext).arm(action.shieldId, action.hours) }
                    render(TileState.snapshot(applicationContext))
                }
                TileDecision.Action.OpenApp -> openApp()
            }
        }
    }

    override fun onStopListening() {
        job.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun render(state: TileState) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield)
        if (state.armed) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Hiding notifications"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = activeSubtitle(state)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Start hiding"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = TileDecision.subtitle(state.idleCandidates)
            }
        }
        tile.updateTile()
    }

    private fun activeSubtitle(state: TileState): String {
        val name = state.activeName ?: return "Spoiler Alert"
        val until = state.activeExpiresAtMillis?.let { " · until ${formatEndTime(it)}" }.orEmpty()
        return "$name$until"
    }

    /** Collapses the shade and opens the app to Home — never the vault. MainActivity defaults
     *  to Home unless launched with the widget's `openVault` extra, which this never sets. */
    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // startActivityAndCollapse(Intent) throws UnsupportedOperationException from API 34.
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Ask the system to re-invoke [onStartListening] on the next opportunity, so a
         *  placed tile stays in sync with arm/disarm/expiry happening elsewhere. Call
         *  alongside [WidgetRefresher] refresh calls. Safe to call when no tile is placed. */
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SpoilerTileService::class.java),
                )
            }
        }
    }
}

/** "11:30 PM" — absolute session end for the active tile subtitle. */
private fun formatEndTime(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
