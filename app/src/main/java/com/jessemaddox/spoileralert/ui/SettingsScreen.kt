package com.jessemaddox.spoileralert.ui

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.Eyebrow
import com.jessemaddox.spoileralert.ui.theme.JessCard
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.TextAction
import com.jessemaddox.spoileralert.ui.theme.WoltBlue

@Composable
fun SettingsScreen(
    onManageInterests: () -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    val context = LocalContext.current
    var excludedCount by remember { mutableStateOf(AppPrefs.excludedPackages(context).size) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var showFantasyPicker by rememberSaveable { mutableStateOf(false) }
    var showExcludedPicker by rememberSaveable { mutableStateOf(false) }
    var fantasyCount by remember { mutableStateOf(AppPrefs.fantasyPackages(context).size) }
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }
    fun checkBattery() = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)
    var ignoringBattery by remember { mutableStateOf(checkBattery()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ignoringBattery = checkBattery()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showDebug) {
        DebugScreen(onBack = { showDebug = false })
        return
    }

    if (showFantasyPicker) {
        FantasyAppPicker(onBack = {
            showFantasyPicker = false
            fantasyCount = AppPrefs.fantasyPackages(context).size
        })
        return
    }

    if (showExcludedPicker) {
        ExcludedAppPicker(onBack = {
            showExcludedPicker = false
            excludedCount = AppPrefs.excludedPackages(context).size
        })
        return
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = WoltBlue,
        unfocusedBorderColor = JessColors.ghostStroke,
        focusedLabelColor = JessColors.accentInk,
        unfocusedLabelColor = JessColors.subtle,
        cursorColor = Blueberry,
    )

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        JessCard {
            Eyebrow("My teams & events", small = true)
            Text(
                "Follow teams and recurring events so their games appear on Home. Following alone does not hide notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = JessColors.subtle,
            )
            TextAction("Manage who you follow", onClick = onManageInterests)
        }

        JessCard {
            Eyebrow("Reliability", small = true)
            Text(
                if (ignoringBattery) "Battery optimization exemption granted."
                else "Your phone may pause Spoiler Alert to save battery, letting spoilers through.",
                style = MaterialTheme.typography.bodySmall,
                color = if (ignoringBattery) JessColors.subtle else Blueberry,
            )
            if (!ignoringBattery) {
                TextAction("Open battery settings", onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                })
            }
            TextAction("Notification access settings", onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        }

        JessCard {
            Eyebrow("Auto-stop", small = true)
            Text(
                "Hiding sessions turn off after this many hours. We check in 15 minutes before they end so a lock is not accidentally left on.",
                style = MaterialTheme.typography.bodySmall,
                color = JessColors.subtle,
            )
            var hoursText by rememberSaveable { mutableStateOf(AppPrefs.autoDisarmHours(context).toString()) }
            OutlinedTextField(
                value = hoursText,
                onValueChange = { input ->
                    val digits = input.filter { it.isDigit() }.take(2)
                    hoursText = digits
                    digits.toIntOrNull()?.let {
                        val clamped = it.coerceIn(1, 72)
                        AppPrefs.setAutoDisarmHours(context, clamped)
                        hoursText = clamped.toString()
                    }
                },
                label = { Text("Hours (1-72)") },
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        JessCard {
            Eyebrow("Pregame reminders", small = true)
            var pregameEnabled by remember { mutableStateOf(AppPrefs.pregamePromptsEnabled(context)) }
            // Toggleable ROW: one focus stop reading label + switch state, 48dp target.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = pregameEnabled, role = Role.Switch, onValueChange = {
                        pregameEnabled = it
                        AppPrefs.setPregamePromptsEnabled(context, it)
                    })
                    .sizeIn(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = pregameEnabled, onCheckedChange = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Ask 10 minutes before a followed event starts",
                    style = MaterialTheme.typography.bodySmall,
                    color = Blueberry,
                )
            }
        }

        JessCard {
            Eyebrow("Excluded apps", small = true)
            Text(
                "Notifications from selected apps are never hidden.",
                style = MaterialTheme.typography.bodySmall,
                color = JessColors.subtle,
            )
            if (excludedCount > 0) {
                Text(
                    "$excludedCount app${if (excludedCount == 1) "" else "s"} excluded",
                    style = MaterialTheme.typography.bodySmall,
                    color = Blueberry,
                )
            }
            TextAction("Choose excluded apps", onClick = { showExcludedPicker = true })
        }

        JessCard {
            Eyebrow("Fantasy sports apps", small = true)
            Text(
                "Hide everything from your fantasy apps while any session is active. Player scores can spoil a game without naming a team.",
                style = MaterialTheme.typography.bodySmall,
                color = JessColors.subtle,
            )
            if (fantasyCount > 0) {
                Text(
                    "$fantasyCount app${if (fantasyCount == 1) "" else "s"} designated",
                    style = MaterialTheme.typography.bodySmall,
                    color = Blueberry,
                )
            }
            TextAction("Choose fantasy apps", onClick = { showFantasyPicker = true })
        }

        JessCard {
            Eyebrow("About", small = true)
            Text(
                "Spoiler Alert v3.9.1. Notification matching and hidden content stay on-device. Internet is used only for GET-only public schedules, event discovery, active-event completion checks, and spoiler-free game status.",
                style = MaterialTheme.typography.bodySmall,
                color = JessColors.subtle,
            )
            var showHowItWorks by rememberSaveable { mutableStateOf(false) }
            TextAction("How it works", onClick = { showHowItWorks = true })
            TextAction("Debug tools", onClick = { showDebug = true })
            TextAction("Clear revealed history", onClick = { confirmClearHistory = true })
            if (showHowItWorks) {
                AlertDialog(
                    onDismissRequest = { showHowItWorks = false },
                    title = { Text("How it works") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("01 · Follow your teams and events")
                            Text("02 · Start hiding notifications on game day")
                            Text("03 · Watch, then stop hiding and catch up")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showHowItWorks = false }) { Text("Got it") }
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear revealed history?") },
            text = { Text("This permanently deletes revealed notifications. Anything still hidden stays that way.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    confirmClearHistory = false
                }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            },
        )
    }
}
