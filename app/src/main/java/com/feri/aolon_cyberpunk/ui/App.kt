package com.feri.aolon_cyberpunk.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.Cloud
import com.feri.aolon_cyberpunk.ble.bluetoothOn
import com.feri.aolon_cyberpunk.ble.hasBlePermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Tab(val label: String) { Health("Health"), Sport("Sport"), Body("Body"), Device("Device"), Mine("Me") }

enum class AppDialog { Alarms, Watchface }

@Composable
fun AolonApp(vm: AolonViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.Health) }
    var dialog by remember { mutableStateOf<AppDialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
    val open: (AppDialog) -> Unit = { dialog = it }

    // Silent reconnect only; the permission prompt waits until the user taps Scan.
    LaunchedEffect(vm.cloud) {
        if (vm.cloud == Cloud.Ready && hasBlePermissions(context) && bluetoothOn(context)) vm.reconnect()
    }
    LaunchedEffect(vm.undo) {
        val pending = vm.undo ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(pending.message, actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) pending.restore()
        if (vm.undo === pending) vm.undo = null
    }
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            notify(it)
            vm.notice = null
        }
    }
    // Back from another tab returns to Health before leaving the app, like system apps do.
    BackHandler(enabled = tab != Tab.Health) { tab = Tab.Health }

    Scaffold(
        containerColor = Neon.Void,
        contentWindowInsets = WindowInsets(0),
        bottomBar = { BottomBar(tab) { tab = it } },
        snackbarHost = {
            SnackbarHost(snackbar) { Snackbar(it, containerColor = Neon.PanelLight, contentColor = Neon.White, actionColor = Neon.Purple) }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val cloud = vm.cloud) {
                Cloud.Loading -> CloudGate {
                    CircularProgressIndicator(color = Neon.Purple)
                    Text("Loading your data from Firebase…", color = Neon.Muted)
                }
                Cloud.SignedOut -> SignInScreen(vm::signIn)
                is Cloud.Failed -> CloudGate {
                    Text("Couldn't load your data", style = MaterialTheme.typography.titleMedium)
                    Text(cloud.reason, color = Neon.Muted, textAlign = TextAlign.Center)
                    Text(
                        if ("Permission denied" in cloud.reason) "Your Firebase rules block this account. Allow it to read and write aolon/users/{uid}."
                        else "Check the internet connection, then try again.",
                        color = Neon.Soft,
                        textAlign = TextAlign.Center,
                    )
                    PrimaryButton("Try again", vm::connectCloud)
                    SecondaryButton("Use another account", vm::signOut)
                }
                Cloud.Ready -> when (tab) {
                Tab.Health -> HealthScreen(vm) { tab = Tab.Device }
                Tab.Sport -> SportScreen(vm, notify)
                Tab.Body -> BodyScreen(vm, notify)
                Tab.Device -> DeviceScreen(vm, open, notify)
                Tab.Mine -> MineScreen(vm, open, notify)
                }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        AppDialog.Alarms -> AlarmDialog(vm, notify, close)
        AppDialog.Watchface -> WatchfaceDialog(vm, close)
        null -> Unit
    }
}

@Composable
private fun CloudGate(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Neon.Panel) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { NavGlyph(tab, if (tab == selected) Neon.Void else Neon.Muted) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = Neon.White,
                    unselectedTextColor = Neon.Muted,
                    indicatorColor = Neon.Purple,
                ),
            )
        }
    }
}

/** Drawn glyphs matched to each tab's job: pulse, stopwatch, scale, watch, person. */
@Composable
private fun NavGlyph(tab: Tab, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val stroke = Stroke(2.dp.toPx())
        when (tab) {
            Tab.Health -> drawPath(Path().apply {
                moveTo(0f, w * 0.55f); lineTo(w * 0.3f, w * 0.55f); lineTo(w * 0.4f, w * 0.22f)
                lineTo(w * 0.56f, w * 0.82f); lineTo(w * 0.66f, w * 0.45f); lineTo(w, w * 0.45f)
            }, tint, style = stroke)
            Tab.Sport -> {
                drawCircle(tint, w * 0.36f, Offset(w * 0.5f, w * 0.58f), style = stroke)
                drawLine(tint, Offset(w * 0.4f, w * 0.06f), Offset(w * 0.6f, w * 0.06f), stroke.width)
                drawLine(tint, Offset(w * 0.5f, w * 0.58f), Offset(w * 0.5f, w * 0.36f), stroke.width)
            }
            Tab.Body -> {
                drawRoundRect(tint, Offset(w * 0.1f, w * 0.1f), Size(w * 0.8f, w * 0.8f), CornerRadius(w * 0.18f), style = stroke)
                drawArc(tint, 200f, 140f, false, Offset(w * 0.28f, w * 0.26f), Size(w * 0.44f, w * 0.44f), style = stroke)
                drawLine(tint, Offset(w * 0.5f, w * 0.48f), Offset(w * 0.6f, w * 0.32f), stroke.width)
            }
            Tab.Device -> {
                drawRoundRect(tint, Offset(w * 0.22f, w * 0.2f), Size(w * 0.56f, w * 0.6f),
                    CornerRadius(w * 0.1f), style = stroke)
                drawLine(tint, Offset(w * 0.35f, 0f), Offset(w * 0.65f, 0f), stroke.width * 2)
                drawLine(tint, Offset(w * 0.35f, w), Offset(w * 0.65f, w), stroke.width * 2)
            }
            Tab.Mine -> {
                drawCircle(tint, w * 0.18f, Offset(w * 0.5f, w * 0.32f), style = stroke)
                drawArc(tint, 180f, 180f, false, Offset(w * 0.16f, w * 0.6f), Size(w * 0.68f, w * 0.6f), style = stroke)
            }
        }
    }
}

@Composable
private fun AlarmDialog(vm: AolonViewModel, notify: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var label by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alarms") },
        containerColor = Neon.PanelLight,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved to your account. Sending alarms to the watch is not built yet.", color = Neon.Muted)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40); error = null },
                    label = { Text("Alarm name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(if (time.isEmpty()) "Pick time" else "Time $time", {
                    val now = Calendar.getInstance()
                    TimePickerDialog(context, { _, h, m -> time = "%02d:%02d".format(h, m); error = null },
                        now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                }, Modifier.fillMaxWidth())
                error?.let { Text(it, color = Neon.Red, style = MaterialTheme.typography.bodySmall) }
                PrimaryButton("Add alarm", {
                    when {
                        label.isBlank() -> error = "Enter an alarm name."
                        time.isEmpty() -> error = "Pick a time."
                        else -> {
                            vm.addAlarm(label.trim(), time)
                            label = ""
                            time = ""
                            notify("Alarm saved.")
                        }
                    }
                }, Modifier.fillMaxWidth())
                if (vm.alarms.isEmpty()) {
                    Text("No alarms yet.", color = Neon.Soft)
                } else {
                    vm.alarms.forEachIndexed { i, alarm ->
                        if (i > 0) RowDivider()
                        SettingRow(alarm.time, alarm.label) {
                            IconButton({ vm.removeAlarm(alarm) }) {
                                Icon(Icons.Outlined.Delete, "Delete ${alarm.label} alarm", tint = Neon.Red)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}

@Composable
private fun WatchfaceDialog(vm: AolonViewModel, onDismiss: () -> Unit) {
    var clock by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = Date()
            delay(15_000)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch face") },
        containerColor = Neon.PanelLight,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WatchfacePreview(vm.settings.watchface, SimpleDateFormat("HH:mm", Locale.getDefault()).format(clock))
                }
                ChoiceChips(WATCHFACES.map { it.second }, { name -> WATCHFACES.first { it.second == name }.first == vm.settings.watchface }, { name ->
                    vm.updateSettings { it.copy(watchface = WATCHFACES.first { f -> f.second == name }.first) }
                })
                Text("Your pick is saved to your account. Uploading faces to the watch is not built yet.", color = Neon.Muted)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}
