package com.jessemaddox.spoileralert.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.jessemaddox.spoileralert.domain.FantasyAppSuggestions
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.TextAction
import com.jessemaddox.spoileralert.ui.theme.WoltBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One launchable app, as offered by the fantasy-app picker. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * Fantasy sports apps picker (v3 amendment item 3): lists every user-facing launchable app
 * (queried via an ACTION_MAIN/CATEGORY_LAUNCHER `<queries>` declaration — see AndroidManifest —
 * so this works on Android 11+ without QUERY_ALL_PACKAGES), alphabetical with known fantasy
 * packages bubbled to the top ([FantasyAppSuggestions]). Multi-select, persists immediately to
 * [AppPrefs.fantasyPackages] on every toggle (same pattern as Settings' excluded-apps list).
 */
@Composable
fun FantasyAppPicker(onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppPrefs.fantasyPackages(context)) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        apps = loaded
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.size(8.dp))
        TextAction("← Back to settings", onClick = onBack)
        Text(
            "Fantasy sports apps",
            style = MaterialTheme.typography.headlineSmall,
            color = Blueberry,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        Text(
            "Every notification from a checked app is hidden while you're hiding spoilers for any event — " +
                "player scores can spoil a game without naming a team.",
            style = MaterialTheme.typography.bodySmall,
            color = JessColors.subtle,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WoltBlue)
            }
        } else {
            LazyColumn {
                items(apps, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { isChecked ->
                                    selected = if (isChecked) selected + app.packageName else selected - app.packageName
                                    AppPrefs.setFantasyPackages(context, selected)
                                },
                            )
                            .sizeIn(minHeight = 48.dp)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.packageName)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            app.label,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Blueberry,
                        )
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
                item { Spacer(Modifier.size(24.dp)) }
            }
        }
    }
}

/** Human-readable app selector for exclusions. Package names remain an implementation detail. */
@Composable
fun ExcludedAppPicker(onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppPrefs.excludedPackages(context)) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        val unavailable = selected
            .filterNot { selectedPackage -> loaded.any { it.packageName == selectedPackage } }
            .map { InstalledApp(it, "Unavailable app") }
        apps = (loaded + unavailable).distinctBy { it.packageName }
            .sortedWith(compareByDescending<InstalledApp> { it.packageName in selected }.thenBy { it.label.lowercase() })
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.size(8.dp))
        TextAction("← Back to settings", onClick = onBack)
        Text(
            "Apps Spoiler Alert ignores",
            style = MaterialTheme.typography.headlineSmall,
            color = Blueberry,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        Text(
            "Notifications from checked apps are never hidden. Choose by app name, not package name.",
            style = MaterialTheme.typography.bodySmall,
            color = JessColors.subtle,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WoltBlue)
            }
        } else {
            LazyColumn {
                items(apps, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { isChecked ->
                                    selected = if (isChecked) selected + app.packageName else selected - app.packageName
                                    AppPrefs.setExcluded(context, selected)
                                },
                            )
                            .sizeIn(minHeight = 48.dp)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.packageName)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            app.label,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Blueberry,
                        )
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
                item { Spacer(Modifier.size(24.dp)) }
            }
        }
    }
}

/** App icon, loaded off the main thread and cached per package name. Falls back to a blank
 *  swatch for apps whose icon can't be resolved (uninstalled mid-session, etc). */
@Composable
internal fun AppIcon(packageName: String) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96, config = Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val current = bitmap
    if (current != null) {
        Image(current, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Box(Modifier.size(32.dp).background(JessColors.hairline, CircleShape))
    }
}

/** Every launchable app (a real device typically has the handful the user actually opens, not
 *  the hundreds of system components QUERY_ALL_PACKAGES would return), deduped by package,
 *  sorted with known fantasy packages bubbled to the top. */
internal fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = if (Build.VERSION.SDK_INT >= 33) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    val apps = resolveInfos
        .mapNotNull { ri -> ri.activityInfo?.packageName?.let { InstalledApp(it, ri.loadLabel(pm).toString()) } }
        .distinctBy { it.packageName }
    return FantasyAppSuggestions.sorted(apps, { it.packageName }, { it.label })
}
