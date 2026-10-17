package com.feri.aolon_cyberpunk.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.notify.TYPE_OTHERS
import com.feri.aolon_cyberpunk.notify.WatchNotificationListener
import com.feri.aolon_cyberpunk.notify.WatchNotifyPrefs
import com.feri.aolon_cyberpunk.notify.hasWatchIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class InstalledApp(val pkg: String, val label: String)

@Composable
fun WatchNotificationsCard(vm: AolonViewModel, online: Boolean, notify: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { WatchNotifyPrefs(context) }
    var hr by remember { mutableStateOf(prefs.heartRateAlerts) }
    var phone by remember { mutableStateOf(prefs.phoneNotifications) }
    var apps by remember { mutableStateOf(prefs.apps) }
    var access by remember { mutableStateOf(WatchNotificationListener.hasAccess(context)) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val openAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    // Access is granted in system Settings, so re-check whenever the user comes back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = WatchNotificationListener.hasAccess(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SectionCard("Notifications on watch") {
        SettingRow("Heart rate zone alerts", "Cardio alerts when you leave your target zone") {
            NeonSwitch(hr) {
                hr = it
                prefs.heartRateAlerts = it
            }
        }
        RowDivider()
        SettingRow(
            "Phone notifications",
            when {
                !access -> "Needs notification access"
                apps.isEmpty() -> "No apps chosen yet"
                else -> "${apps.size} app${if (apps.size == 1) "" else "s"}"
            },
        ) {
            NeonSwitch(phone && access) {
                if (it && !access) openAccess()
                phone = it
                prefs.phoneNotifications = it
                if (it && apps.isEmpty()) picking = true
            }
        }
        if (phone && access) {
            RowDivider()
            SettingRow("Choose apps", "Only these apps reach the watch", onClick = { picking = true })
        }
        if (!access) {
            Text(
                "Android only lets an app read other apps' notifications after you allow it. Find Aolon Cyberpunk in the list and turn it on.",
                color = Neon.Soft,
                style = MaterialTheme.typography.bodySmall,
            )
            SecondaryButton("Allow notification access", openAccess, Modifier.fillMaxWidth())
        }
        if (!online) Text("The watch is offline, so nothing is sent until it reconnects.", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
        SecondaryButton("Send test to watch", {
            vm.sendToWatch(TYPE_OTHERS, "Aolon Cyberpunk · Test OK.")
            notify("Test sent. Check the watch screen.")
        }, Modifier.fillMaxWidth(), color = if (online) Neon.Blue else Neon.Soft)
    }

    if (picking) {
        AppPicker(apps, { picking = false }) {
            apps = it
            prefs.apps = it
        }
    }
}

@Composable
private fun NeonSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked,
    onChange,
    colors = SwitchDefaults.colors(checkedTrackColor = Neon.Purple, checkedThumbColor = Neon.Void),
)

/** Full-screen list of launchable apps; chosen ones and apps with a watch icon come first. */
@Composable
private fun AppPicker(chosen: Set<String>, close: () -> Unit, change: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var installed by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // Order is fixed when the picker opens so rows don't jump while the user ticks them.
    val start = remember { chosen }
    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .sortedWith(compareBy({ it.pkg !in start }, { !hasWatchIcon(it.pkg) }, { it.label.lowercase() }))
        }
    }

    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Neon.Void)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(close) { Icon(Icons.Outlined.Close, "Close", tint = Neon.White) }
                Text("Apps on the watch", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("${chosen.size} chosen", color = Neon.Soft, modifier = Modifier.padding(end = 8.dp))
            }
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("Search apps") },
                singleLine = true,
            )
            val list = installed
            if (list == null) {
                StateMessage("Loading apps…")
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { app ->
                        val on = app.pkg in chosen
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable { change(if (on) chosen - app.pkg else chosen + app.pkg) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                if (!hasWatchIcon(app.pkg)) Text("Shows as Others on the watch", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
                            }
                            Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = Neon.Purple, checkmarkColor = Neon.Void))
                        }
                    }
                }
            }
        }
    }
}
