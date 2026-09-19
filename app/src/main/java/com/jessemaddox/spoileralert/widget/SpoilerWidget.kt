package com.jessemaddox.spoileralert.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpoilerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpoilerWidget()
}

// Jess tokens for the widget (Glance can't read the Compose theme).
private val WBlue = ColorProvider(Color(0xFF00C2E8))
private val WBlueberry = ColorProvider(Color(0xFF021738))
private val WBlue50 = ColorProvider(Color(0xFFE0F7FC))
private val WAccentInk = ColorProvider(Color(0xFF007A94))
private val WWhite = ColorProvider(Color.White)
private val WSubtle = ColorProvider(Color(0x9E021738))
private val WSubtleOnBlue = ColorProvider(Color(0xB3021738))

/**
 * Home-screen widget mirroring the hero (frame 6): protected = Wolt Blue card with
 * Blueberry ink; idle = white card with the next game and a "Hide spoilers" action.
 * Renders public metadata only — never hidden notification content.
 */
class SpoilerWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Observe (don't snapshot) so a live Glance session recomposes on DB changes —
        // e.g. right after the widget's own Arm button fires.
        val states = WidgetState.observe(context)
        val initial = states.first()
        provideContent {
            val state by states.collectAsState(initial)
            WidgetContent(context, state)
        }
    }

    companion object {
        const val ACTION_OPEN_APP = "com.jessemaddox.spoileralert.OPEN_APP_WIDGET"
        const val ACTION_OPEN_VAULT = "com.jessemaddox.spoileralert.OPEN_VAULT_WIDGET"
    }
}

@Composable
private fun WidgetContent(context: Context, state: WidgetState) {
    // Distinct action strings so the two PendingIntents (open app vs open vault)
    // don't collapse into one.
    val openApp = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(SpoilerWidget.ACTION_OPEN_APP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    val openVault = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(SpoilerWidget.ACTION_OPEN_VAULT)
            .putExtra("openVault", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    val protecting = state.armedCount > 0
    var modifier = GlanceModifier.fillMaxSize()
        .background(if (protecting) WBlue else WWhite)
        .padding(14.dp)
        .clickable(if (protecting) openVault else openApp)
    if (Build.VERSION.SDK_INT >= 31) modifier = modifier.cornerRadius(24.dp)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            iconRes = if (protecting) R.drawable.ic_shield else R.drawable.ic_calendar,
            background = if (protecting) WBlueberry else WBlue50,
            tint = if (protecting) WWhite else WAccentInk,
        )
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            when {
                protecting -> ProtectedLines(state)
                !state.hasShields -> {
                    TitleLine("Spoiler Alert", WBlueberry)
                    ActionLine("Add a team or topic", openApp)
                }
                state.nextGame == null -> {
                    TitleLine("Spoiler Alert", WBlueberry)
                    Text("No upcoming games", style = TextStyle(color = WSubtle, fontSize = 13.sp))
                }
                else -> IdleGameLines(state)
            }
        }
    }
}

@Composable
private fun AppIcon(iconRes: Int, background: ColorProvider, tint: ColorProvider) {
    var mod = GlanceModifier.size(36.dp).background(background)
    if (Build.VERSION.SDK_INT >= 31) mod = mod.cornerRadius(18.dp)
    Box(modifier = mod, contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(18.dp),
        )
    }
}

@Composable
private fun ProtectedLines(state: WidgetState) {
    // Absolute end time, not a ticking countdown — a live timer invites checking, and a
    // changing number is itself information about the game.
    val until = state.armedExpiresAtMillis?.let { " · until ${formatEndTime(it)}" }.orEmpty()
    TitleLine("Hiding notifications · ${state.armedName.orEmpty()}$until", WBlueberry)
    // No hidden COUNT on the home screen (meta-spoiler) — just whether the vault has items.
    val hiddenLabel = if (state.hasHidden) "Spoilers hidden — tap to review" else "Nothing hidden yet"
    Text(hiddenLabel, style = TextStyle(color = WSubtleOnBlue, fontSize = 13.sp), maxLines = 1)
}

@Composable
private fun IdleGameLines(state: WidgetState) {
    val game = state.nextGame ?: return
    val now = System.currentTimeMillis()
    val whenText = if (game.startMillis <= now) "Live now" else formatKickoff(game.startMillis)
    TitleLine("${game.shortName} · $whenText", WBlueberry)
    Text(
        "Start hiding notifications",
        style = TextStyle(color = WAccentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
        modifier = GlanceModifier.clickable(
            actionRunCallback<ArmShieldAction>(
                actionParametersOf(ArmShieldAction.KEY_SHIELD_ID to game.shieldId),
            ),
        ),
    )
}

@Composable
private fun TitleLine(text: String, color: ColorProvider) {
    Text(
        text,
        style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

@Composable
private fun ActionLine(text: String, action: Action) {
    Text(
        text,
        style = TextStyle(color = WAccentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
        modifier = GlanceModifier.clickable(action),
    )
}

/** "Sun 1:00 PM" in the device's local timezone. */
private fun formatKickoff(millis: Long): String =
    SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(millis))

/** "11:30 PM" — absolute session end for the protected card. */
private fun formatEndTime(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

/** One-tap arm from the widget — arms the shield without opening the app. */
class ArmShieldAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val shieldId = parameters[KEY_SHIELD_ID] ?: return
        runCatching { ShieldRepository(context.applicationContext).arm(shieldId) }
        runCatching { SpoilerWidget().updateAll(context) }
    }

    companion object {
        val KEY_SHIELD_ID = ActionParameters.Key<Long>("shieldId")
    }
}

/** Push a fresh snapshot to any placed widgets. Safe to call when none are placed. */
object WidgetRefresher {
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Fire-and-forget, for non-suspend call sites (ViewModel actions). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { SpoilerWidget().updateAll(app) } }
    }

    /** For suspend call sites (workers) — completes before returning. */
    suspend fun refreshNow(context: Context) {
        runCatching { SpoilerWidget().updateAll(context.applicationContext) }
    }
}
