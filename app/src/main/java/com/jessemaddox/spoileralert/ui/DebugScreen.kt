package com.jessemaddox.spoileralert.ui

import android.app.NotificationManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.data.AppDatabase
import com.jessemaddox.spoileralert.schedule.PregamePromptWorker
import com.jessemaddox.spoileralert.service.CheckInWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val nm = remember { context.getSystemService(NotificationManager::class.java) }
    val counter = remember { AtomicInteger(50_000 + (System.currentTimeMillis() % 10_000).toInt()) }

    fun post(title: String, text: String) {
        nm.notify(counter.incrementAndGet(), NotificationCompat.Builder(context, SpoilerAlertApp.CHANNEL_DEBUG_FAKE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .build())
    }

    // Posts a MessagingStyle notification carrying a conversation title, so intercepts land in a
    // NAMED conversation group (exercises the Hidden tab's collapsed conversation row).
    fun postGroupChat(conversation: String, sender: String, text: String) {
        val you = androidx.core.app.Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(you)
            .setConversationTitle(conversation)
            .setGroupConversation(true)
            .addMessage(text, System.currentTimeMillis(),
                androidx.core.app.Person.Builder().setName(sender).build())
        nm.notify(counter.incrementAndGet(), NotificationCompat.Builder(context, SpoilerAlertApp.CHANNEL_DEBUG_FAKE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .build())
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back to settings") }
        Text("Debug: post fake spoilers", style = MaterialTheme.typography.headlineSmall)
        Text("Arm a matching shield first, then tap. The notification should vanish instantly and appear in the vault.")
        Button(onClick = { post("ESPN", "FINAL: Falcons 24, Saints 17 — Atlanta wins on a last-second FG") }) {
            Text("Fake ESPN score alert (Falcons)")
        }
        Button(onClick = { post("Kait", "DID YOU SEE THE FALCONS GAME?! Unreal ending") }) {
            Text("Fake group text (Falcons)")
        }
        Button(onClick = {
            postGroupChat("Family chat", "Kait", "DID YOU SEE THE FALCONS GAME?! Unreal ending")
        }) {
            Text("Fake family chat burst (Falcons, tap several times)")
        }
        Button(onClick = { post("PGA Tour", "Scheffler wins the Masters by 3") }) {
            Text("Fake golf alert (Masters)")
        }
        Button(onClick = { post("FOX Sports", "GOAL! England strike late — England 2, France 1 in the semifinal") }) {
            Text("Fake World Cup alert (England)")
        }
        Button(onClick = { post("Weather", "Rain expected in Atlanta tomorrow") }) {
            Text("Control: non-spoiler (should NOT be hidden)")
        }

        Text("Pregame prompt test", style = MaterialTheme.typography.titleMedium)
        Text(
            "Schedules the real PregamePromptWorker ~15s out against the next upcoming game " +
                "of your first team or game shield. The shield must be disarmed with pregame " +
                "reminders on.",
            style = MaterialTheme.typography.bodySmall,
        )
        var pregameTestStatus by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        Button(onClick = {
            scope.launch {
                pregameTestStatus = runCatching {
                    val db = AppDatabase.get(context)
                    val candidates = db.shieldDao().schedulableShields()
                    if (candidates.isEmpty()) return@runCatching "No team or game shield — add one first"
                    val (shield, game) = candidates.firstNotNullOfOrNull { s ->
                        db.gameDao().upcomingForShield(s.id, System.currentTimeMillis())
                            .firstOrNull()?.let { s to it }
                    } ?: return@runCatching "No upcoming game cached for any shield"
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "pregame-debug",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<PregamePromptWorker>()
                            .setInitialDelay(15, TimeUnit.SECONDS)
                            .setInputData(workDataOf(
                                PregamePromptWorker.KEY_EVENT_ID to game.id,
                                PregamePromptWorker.KEY_SHIELD_ID to shield.id,
                            ))
                            .build(),
                    )
                    "Scheduled for ${shield.name} (${game.shortName}) — watch for the prompt in ~15s"
                }.getOrElse { "Failed: $it" }
            }
        }) { Text("Test pregame prompt (fires in ~15s)") }
        pregameTestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Session check-in test", style = MaterialTheme.typography.titleMedium)
        Text(
            "Rewinds your first ARMED shield's session so it expires ~10 minutes from now " +
                "(inside the check-in window), then schedules the real CheckInWorker ~15s out.",
            style = MaterialTheme.typography.bodySmall,
        )
        var checkInTestStatus by remember { mutableStateOf<String?>(null) }
        Button(onClick = {
            scope.launch {
                checkInTestStatus = runCatching {
                    val db = AppDatabase.get(context)
                    val shield = db.shieldDao().armed().firstOrNull()
                        ?: return@runCatching "No armed shield — arm one first"
                    // Shift armedAt back so expiry lands 10 minutes out; the worker's
                    // fire-time window check then passes for real.
                    val newArmedAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10) -
                        TimeUnit.HOURS.toMillis(shield.autoDisarmHours.toLong())
                    db.shieldDao().setArmed(shield.id, armed = true, armedAt = newArmedAt)
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "checkin-debug",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<CheckInWorker>()
                            .setInitialDelay(15, TimeUnit.SECONDS)
                            .setInputData(workDataOf(CheckInWorker.KEY_SHIELD_ID to shield.id))
                            .build(),
                    )
                    "Scheduled for ${shield.name} — watch for the check-in in ~15s"
                }.getOrElse { "Failed: $it" }
            }
        }) { Text("Test session check-in") }
        checkInTestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Session expiry test — grace window", style = MaterialTheme.typography.titleMedium)
        Text(
            "Rewinds the selected session window to one minute ago while leaving 59 minutes of " +
                "grace. The real AutoDisarmWorker runs in ~5s and must NO-OP: the shield stays " +
                "armed and interception keeps working until the absolute grace deadline.",
            style = MaterialTheme.typography.bodySmall,
        )
        var expiryTestStatus by remember { mutableStateOf<String?>(null) }
        Button(onClick = {
            scope.launch {
                expiryTestStatus = runCatching {
                    val db = AppDatabase.get(context)
                    val shield = db.shieldDao().armed().firstOrNull()
                        ?: return@runCatching "No armed shield — arm one first"
                    val newArmedAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1) -
                        TimeUnit.HOURS.toMillis(shield.autoDisarmHours.toLong())
                    db.shieldDao().setArmed(shield.id, armed = true, armedAt = newArmedAt)
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "autodisarm-debug",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<com.jessemaddox.spoileralert.service.AutoDisarmWorker>()
                            .setInitialDelay(5, TimeUnit.SECONDS)
                            .setInputData(workDataOf(
                                com.jessemaddox.spoileralert.service.AutoDisarmWorker.KEY_SHIELD_ID to shield.id,
                            ))
                            .build(),
                    )
                    "Scheduled for ${shield.name} — grace-window no-op check fires in ~5s"
                }.getOrElse { "Failed: $it" }
            }
        }) { Text("Test grace-window protection") }
        expiryTestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Session expiry test — grace seal", style = MaterialTheme.typography.titleMedium)
        Text(
            "Rewinds your first ARMED shield past its absolute grace deadline, then runs the " +
                "real AutoDisarmWorker ~5s out. Expect: " +
                "protection off, items SEALED (not revealed), 'Protection ended' notification " +
                "with a Reveal all action.",
            style = MaterialTheme.typography.bodySmall,
        )
        var graceSealTestStatus by remember { mutableStateOf<String?>(null) }
        Button(onClick = {
            scope.launch {
                graceSealTestStatus = runCatching {
                    val db = AppDatabase.get(context)
                    val shield = db.shieldDao().armed().firstOrNull()
                        ?: return@runCatching "No armed shield — arm one first"
                    // Past the absolute protection deadline, including the fixed grace hour.
                    val newArmedAt = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1) -
                        TimeUnit.MINUTES.toMillis(1) - TimeUnit.HOURS.toMillis(shield.autoDisarmHours.toLong())
                    db.shieldDao().setArmed(shield.id, armed = true, armedAt = newArmedAt)
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "autodisarm-debug",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<com.jessemaddox.spoileralert.service.AutoDisarmWorker>()
                            .setInitialDelay(5, TimeUnit.SECONDS)
                            .setInputData(workDataOf(
                                com.jessemaddox.spoileralert.service.AutoDisarmWorker.KEY_SHIELD_ID to shield.id,
                            ))
                            .build(),
                    )
                    "Scheduled for ${shield.name} — grace seal fires in ~5s"
                }.getOrElse { "Failed: $it" }
            }
        }) { Text("Test grace seal") }
        graceSealTestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val misses = AppPrefs.cancelMisses(context)
        Text("Cancel misses (${misses.size})", style = MaterialTheme.typography.titleMedium)
        if (misses.isEmpty()) Text("None recorded — every matched notification was successfully hidden.")
        misses.forEach { m ->
            val pkg = m.substringBefore('|')
            val ts = m.substringAfter('|').toLongOrNull() ?: 0L
            Text("$pkg · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(ts))}", style = MaterialTheme.typography.bodySmall)
        }

        var traceTick by remember { mutableStateOf(0) }
        val trace = remember(traceTick) { AppPrefs.matchTrace(context) }
        Text("Match trace (${trace.size}, newest first)", style = MaterialTheme.typography.titleMedium)
        Text("Every decision about messaging/sports notifications is recorded here. If a spoiler slips through, screenshot this.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { traceTick++ }) { Text("Refresh") }
            TextButton(onClick = { AppPrefs.clearTrace(context); traceTick++ }) { Text("Clear") }
        }
        if (trace.isEmpty()) Text("No decisions recorded yet.")
        trace.forEach { line ->
            val ts = line.substringBefore('|').toLongOrNull() ?: 0L
            Text(
                "${java.text.DateFormat.getTimeInstance().format(java.util.Date(ts))}  ${line.substringAfter('|')}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
