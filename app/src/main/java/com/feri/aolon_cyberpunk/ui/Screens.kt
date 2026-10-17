package com.feri.aolon_cyberpunk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feri.aolon_cyberpunk.ACTIVITIES
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.Session
import com.feri.aolon_cyberpunk.cardio.CardioRecorder
import com.feri.aolon_cyberpunk.DAYS
import com.feri.aolon_cyberpunk.ble.LinkState
import com.feri.aolon_cyberpunk.ble.Metric
import com.feri.aolon_cyberpunk.ble.rememberBleGate
import com.feri.aolon_cyberpunk.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun HealthScreen(vm: AolonViewModel, goDevice: () -> Unit) {
    val state by vm.watch.state.collectAsState()
    val online = state == LinkState.Ready
    val today = LocalDate.now()
    // Epoch days survive rotation and process death without a custom saver.
    var dayEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var weeksBack by rememberSaveable { mutableIntStateOf(0) }
    val day = LocalDate.ofEpochDay(dayEpoch)
    val first = (vm.steps.keys + vm.sessions.map { it.date }).minOrNull() ?: today

    TabScaffold(
        "Health",
        actions = { WatchStatusChip(vm, state, goDevice) },
        refreshing = if (online) vm.syncing else null,
        onRefresh = vm::syncSteps,
    ) {
        item {
            SectionCard("Today", trailing = if (vm.syncing) "Syncing…" else null) {
                when {
                    vm.savedWatch == null && vm.steps.isEmpty() -> StateMessage(
                        "No watch paired yet. Pair it to see steps and take readings.", "Pair watch", goDevice,
                    )
                    else -> {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            StepRing(vm.stepsToday, vm.settings.stepGoal)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Figure("Distance", distance(vm.stepsToday, vm.settings.distanceUnit), Modifier.weight(1f))
                            Figure("Goal", vm.settings.stepGoal?.let(::grouped) ?: "Not set", Modifier.weight(1f))
                        }
                        Text(
                            when {
                                state == LinkState.Connecting -> "Connecting to the watch…"
                                !online -> "Watch offline. Showing the last synced data."
                                else -> "Pull down to sync steps from the watch."
                            },
                            color = Neon.Soft,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item { VitalsCard(vm, online) }
        item {
            DayHistory(vm, day, first, today) { dayEpoch = it.toEpochDay() }
        }
        item {
            val end = today.minusWeeks(weeksBack.toLong())
            val week = (6 downTo 0).map { end.minusDays(it.toLong()) }.associateWith(vm::stepsOn)
            SectionCard(if (weeksBack == 0) "Last 7 days" else "Week", trailing = "${grouped(week.values.sum())} steps") {
                PeriodNavigator(
                    "${shortDate(week.keys.first())} to ${shortDate(end)}",
                    canBack = week.keys.first() > first,
                    canForward = weeksBack > 0,
                    unit = "week",
                    onBack = { weeksBack++ },
                    onForward = { weeksBack-- },
                )
                WeekBars(week, vm.settings.stepGoal, selected = day) { dayEpoch = it.toEpochDay() }
                Text(
                    "Tap a bar to open that day above." + (vm.settings.stepGoal?.let { " Dashed line marks your ${grouped(it)} step goal." } ?: ""),
                    color = Neon.Soft,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** One past day: steps, distance, hourly pattern and the workouts recorded on it. */
@Composable
private fun DayHistory(vm: AolonViewModel, day: LocalDate, first: LocalDate, today: LocalDate, onDay: (LocalDate) -> Unit) {
    val hours = vm.steps[day].orEmpty()
    val workouts = vm.sessions.filter { it.date == day }
    SectionCard("History") {
        PeriodNavigator(
            when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US))
            },
            canBack = day > first,
            canForward = day < today,
            unit = "day",
            onBack = { onDay(day.minusDays(1)) },
            onForward = { onDay(day.plusDays(1)) },
        )
        Row(Modifier.fillMaxWidth()) {
            Figure("Steps", grouped(vm.stepsOn(day)), Modifier.weight(1f))
            Figure("Distance", distance(vm.stepsOn(day), vm.settings.distanceUnit), Modifier.weight(1f))
            Figure("Workouts", workouts.size.toString(), Modifier.weight(1f))
        }
        if (hours.isEmpty()) {
            Text(
                if (vm.steps.isEmpty()) "Hourly steps show up after the first sync with your watch."
                else "The watch recorded no steps on this day.",
                color = Neon.Soft,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            HourlyBars(hours)
        }
        val time = SimpleDateFormat("HH:mm", Locale.US)
        workouts.forEach { session ->
            RowDivider()
            SettingRow(session.activity, time.format(Date(session.startedAt))) {
                Text(formatDuration(session.duration), style = Telemetry, color = Neon.Blue)
            }
        }
    }
}

private fun shortDate(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))

@Composable
private fun WatchStatusChip(vm: AolonViewModel, state: LinkState, onClick: () -> Unit) {
    val (dot, label) = when {
        state == LinkState.Ready -> Neon.Blue to (vm.battery?.let { "${it.percent}%" } ?: "Online")
        state == LinkState.Connecting -> Neon.Purple to "Connecting"
        vm.savedWatch != null -> Neon.Red to "Offline"
        else -> Neon.Red to "No watch"
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Box(Modifier.size(8.dp).background(dot, CircleShape)) },
        colors = AssistChipDefaults.assistChipColors(labelColor = Neon.White),
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Neon.Soft, style = MaterialTheme.typography.labelMedium)
        Text(value, style = Telemetry.copy(fontSize = 20.sp))
    }
}

// The watch reports steps only; distance uses an average 0.75 m stride.
private fun distance(steps: Int, unit: String): String {
    if (steps == 0) return "--"
    val meters = steps * 0.75
    return if (unit == "mi") "%.2f mi".format(meters / 1609.34) else "%.2f km".format(meters / 1000.0)
}

@Composable
private fun VitalsCard(vm: AolonViewModel, online: Boolean) {
    SectionCard("Readings") {
        VitalRow(vm, Metric.HeartRate, "Heart rate", vm.heartRate?.let { "$it bpm" }, online)
        RowDivider()
        VitalRow(vm, Metric.Oxygen, "Blood oxygen", vm.oxygen?.let { "$it%" }, online)
        // Hidden once the watch's feature bitmap says it has no blood pressure sensor.
        if (vm.hasBloodPressure == false) return@SectionCard
        RowDivider()
        VitalRow(vm, Metric.BloodPressure, "Blood pressure", vm.pressure?.let { "${it.systolic}/${it.diastolic} mmHg" }, online)
    }
}

@Composable
private fun VitalRow(vm: AolonViewModel, metric: Metric, name: String, value: String?, online: Boolean) {
    val active = vm.measuring[metric] == true
    val noSensor = metric == Metric.BloodPressure && vm.hasBloodPressure == false
    val unsupported = noSensor || vm.unsupported[metric] == true && value == null
    SettingRow(
        name,
        when {
            active -> "Measuring, keep your wrist still…"
            value != null -> value
            noSensor -> "This watch reports no blood pressure sensor."
            unsupported -> "This watch did not respond. It may lack the sensor."
            online -> "Not measured yet"
            else -> "Connect the watch to measure"
        },
    ) {
        if (online && !noSensor) {
            FilledTonalButton(onClick = { vm.measure(metric, !active) }, shape = ControlShape) {
                Text(if (active) "Stop" else if (unsupported) "Retry" else "Measure")
            }
        }
    }
}

@Composable
fun SportScreen(vm: AolonViewModel, notify: (String) -> Unit) {
    var view by rememberSaveable { mutableStateOf(0) }
    var cardio by rememberSaveable { mutableStateOf(false) }
    var logging by rememberSaveable { mutableStateOf(vm.gym.active != null) }
    var tracking by rememberSaveable { mutableStateOf(CardioRecorder.recording) }
    var detail by remember { mutableStateOf<Session?>(null) }
    val active = vm.gym.active
    if (!cardio && logging && active != null) {
        BackHandler { logging = false }
        GymWorkoutScreen(vm, active, notify) { logging = false }
        return
    }
    // Leaving the page keeps recording; the start card offers the way back.
    if (cardio && tracking && CardioRecorder.recording) {
        BackHandler { tracking = false }
        CardioRecording(vm, notify) { tracking = false }
        return
    }
    detail?.let { s -> CardioDetail(vm, s) { detail = null } }
    val starter = rememberCardioStarter(vm) { tracking = true }
    val todayDate = LocalDate.now()
    val today = todayDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
    TabScaffold("Sport") {
        item {
            TabRow(if (cardio) 1 else 0, containerColor = Neon.Void, contentColor = Neon.White) {
                listOf("Gym", "Cardio").forEachIndexed { i, label ->
                    Tab((i == 1) == cardio, { cardio = i == 1 }, text = { Text(label) }, unselectedContentColor = Neon.Muted)
                }
            }
        }
        if (!cardio) {
            item { GymHome(vm, notify) { logging = true } }
            return@TabScaffold
        }
        item { CardioStart(vm, starter) { tracking = true } }
        item {
            TabRow(view, containerColor = Neon.Void, contentColor = Neon.White) {
                listOf("History", "Plans").forEachIndexed { i, label ->
                    Tab(view == i, { view = i }, text = { Text(label) }, unselectedContentColor = Neon.Muted)
                }
            }
        }
        if (view == 0) {
            if (vm.sessions.isEmpty()) {
                item { StateMessage("No activities yet. Pick one above and tap Start.") }
            } else {
                item {
                    SectionCard("Activities", trailing = "${vm.sessions.size}") {
                        vm.sessions.asReversed().forEachIndexed { i, session ->
                            if (i > 0) RowDivider()
                            SettingRow(session.activity, sessionSummary(session), onClick = { detail = session })
                        }
                    }
                }
            }
        } else {
            item { PlanEditor(vm, notify) }
            if (vm.plans.isEmpty()) {
                item { StateMessage("No plans yet. Choose an activity and days, then save.") }
            } else {
                item {
                    SectionCard("Your plans") {
                        // Today's plans first. "Done" is derived from History, so finishing the activity ticks the plan.
                        vm.plans.sortedByDescending { today in it.days }.forEachIndexed { i, plan ->
                            if (i > 0) RowDivider()
                            val due = today in plan.days
                            val done = due && vm.sessions.any { it.activity == plan.activity && it.date == todayDate }
                            val status = when {
                                done -> " · done today"
                                due -> " · today"
                                else -> ""
                            }
                            SettingRow(plan.activity, plan.days.joinToString(", ") + status) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({ starter.start(plan.activity) }) {
                                        Text("Start", color = if (due && !done) Neon.Purple else Neon.Blue, fontWeight = FontWeight.SemiBold)
                                    }
                                    IconButton(onClick = { vm.removePlan(plan) }) {
                                        Icon(Icons.Outlined.Delete, "Delete ${plan.activity} plan", tint = Neon.Red)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanEditor(vm: AolonViewModel, notify: (String) -> Unit) {
    var activity by rememberSaveable { mutableStateOf(ACTIVITIES.first()) }
    val days = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard("New plan") {
        ChoiceChips(ACTIVITIES, { it == activity }, { activity = it })
        ChoiceChips(DAYS, { it in days }, { if (it in days) days.remove(it) else days.add(it) })
        error?.let { Text(it, color = Neon.Red, style = MaterialTheme.typography.bodySmall) }
        PrimaryButton("Save plan", {
            if (days.isEmpty()) {
                error = "Pick at least one day."
            } else {
                vm.addPlan(activity, days.toList())
                days.clear()
                error = null
                notify("Plan saved.")
            }
        }, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<String>, selected: (String) -> Boolean, onClick: (String) -> Unit, enabled: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = selected(option),
                onClick = { onClick(option) },
                label = { Text(option) },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Neon.Purple,
                    selectedLabelColor = Neon.Void,
                    labelColor = Neon.Muted,
                ),
            )
        }
    }
}

@Composable
fun DeviceScreen(vm: AolonViewModel, open: (AppDialog) -> Unit, notify: (String) -> Unit) {
    val state by vm.watch.state.collectAsState()
    val found by vm.watch.found.collectAsState()
    val log by vm.watch.log.collectAsState()
    val gate = rememberBleGate()
    val online = state == LinkState.Ready

    TabScaffold("Device") {
        item {
            SectionCard(vm.savedWatch?.name ?: "No watch paired") {
                Text(
                    when (state) {
                        LinkState.Ready -> "Connected"
                        LinkState.Connecting -> "Connecting…"
                        LinkState.Scanning -> "Looking for watches nearby…"
                        LinkState.Idle -> if (vm.savedWatch != null) "Offline. Keep the watch close and awake, then reconnect."
                        else "Turn on Bluetooth and keep the watch close, then scan."
                    },
                    color = if (online) Neon.Blue else Neon.Muted,
                )
                if (state == LinkState.Scanning || state == LinkState.Connecting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Neon.Blue, trackColor = Neon.PanelLight)
                }
                if (online) {
                    SettingRow("Battery", vm.battery?.let { "${it.percent}%" + if (it.charging == true) ", charging" else "" } ?: "Reading…")
                    RowDivider()
                    SettingRow("Firmware", vm.firmware ?: "Reading…")
                    RowDivider()
                    SettingRow("Address", vm.savedWatch?.address)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        online -> {
                            PrimaryButton("Sync now", {
                                vm.syncTime()
                                vm.refreshBattery()
                                vm.syncSteps()
                                notify("Syncing with the watch.")
                            }, Modifier.weight(1f))
                            SecondaryButton("Forget", vm::forgetWatch, Modifier.weight(1f), Neon.Red)
                        }
                        state == LinkState.Scanning -> SecondaryButton("Stop scanning", vm.watch::stopScan, Modifier.weight(1f))
                        state == LinkState.Connecting -> SecondaryButton("Cancel", vm.watch::disconnect, Modifier.weight(1f), Neon.Red)
                        else -> {
                            PrimaryButton("Scan", { gate.request { vm.watch.scan() } }, Modifier.weight(1f))
                            if (vm.savedWatch != null) SecondaryButton("Reconnect", { gate.request { vm.reconnect() } }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (!online && (state == LinkState.Scanning || found.isNotEmpty())) {
            item {
                SectionCard("Nearby", trailing = "Tap to pair") {
                    if (found.isEmpty()) StateMessage("Nothing found yet. Wake the watch screen to make it visible.")
                    found.take(12).forEachIndexed { i, watch ->
                        if (i > 0) RowDivider()
                        SettingRow(
                            watch.name,
                            "${watch.rssi} dBm" + if (watch.ute) ", compatible" else "",
                            onClick = { gate.request { vm.pair(watch) } },
                        )
                    }
                }
            }
        }
        item { WatchNotificationsCard(vm, online, notify) }
        item {
            SectionCard("Watch extras") {
                SettingRow("Watch face", "Preview styles", onClick = { open(AppDialog.Watchface) })
                RowDivider()
                SettingRow("Alarms", if (vm.alarms.isEmpty()) "None saved" else "${vm.alarms.size} saved", onClick = { open(AppDialog.Alarms) })
            }
        }
        item { PacketLog(log) }
    }
}

/** Raw link traffic, kept for decoding the features the watch still hides. */
@Composable
private fun PacketLog(log: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard("Bluetooth log", trailing = "${log.size} lines") {
        TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide log" else "Show log") }
        if (expanded) {
            Column(Modifier.fillMaxWidth().background(Neon.Void).padding(8.dp)) {
                if (log.isEmpty()) Text("No traffic yet. Connect the watch first.", color = Neon.Soft)
                log.takeLast(60).asReversed().forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = when {
                            "RX" in line -> Neon.Blue
                            "TX" in line -> Neon.Purple
                            else -> Neon.Soft
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun MineScreen(vm: AolonViewModel, open: (AppDialog) -> Unit, notify: (String) -> Unit) {
    var goalInput by rememberSaveable { mutableStateOf(vm.settings.stepGoal?.toString() ?: "") }
    var goalError by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keep the field in sync after a data wipe resets settings.
    LaunchedEffect(vm.settings.stepGoal) { goalInput = vm.settings.stepGoal?.toString() ?: "" }

    fun saveGoal() {
        val goal = goalInput.toIntOrNull()
        if (goal == null || goal !in 1..100_000) {
            goalError = "Enter a number from 1 to 100,000."
        } else {
            vm.updateSettings { it.copy(stepGoal = goal) }
            goalError = null
            focus.clearFocus()
            notify("Step goal saved.")
        }
    }

    TabScaffold("Me") {
        item {
            val total = vm.sessions.sumOf { it.duration }
            SectionCard("Workouts") {
                if (vm.sessions.isEmpty()) {
                    StateMessage("No workouts recorded yet. Your totals appear here after the first one.")
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Figure("Sessions", vm.sessions.size.toString(), Modifier.weight(1f))
                        Figure("Total time", formatDuration(total), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            SectionCard("Goals and units") {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = goalInput,
                        onValueChange = { goalInput = it.filter(Char::isDigit).take(6); goalError = null },
                        label = { Text("Daily step goal") },
                        isError = goalError != null,
                        supportingText = goalError?.let { { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveGoal() }),
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton("Save", ::saveGoal, Modifier.padding(top = 8.dp))
                }
                Text("Distance unit", style = MaterialTheme.typography.bodyLarge)
                ChoiceChips(listOf("km", "mi"), { it == vm.settings.distanceUnit }, { unit -> vm.updateSettings { it.copy(distanceUnit = unit) } })
            }
        }
        item {
            SectionCard("Account") {
                SettingRow("Signed in", vm.account ?: "Google account") {
                    TextButton({
                        scope.launch { clearGoogleCredential(context) }
                        vm.signOut()
                    }) { Text("Sign out") }
                }
                Text(
                    "Your records are stored in Firebase under this account, so they come back after a reinstall. Deleted items are hidden, not erased.",
                    color = Neon.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
