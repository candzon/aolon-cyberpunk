package com.feri.aolon_cyberpunk.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.formatDuration
import com.feri.aolon_cyberpunk.gym.GymWorkout
import com.feri.aolon_cyberpunk.gym.Routine
import com.feri.aolon_cyberpunk.gym.kgText
import com.feri.aolon_cyberpunk.gym.records
import com.feri.aolon_cyberpunk.gym.volume
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val REST_CHOICES = listOf(60, 90, 120, 180)
// Lists stay short so the start button remains the one thing the screen asks for.
private const val PREVIEW = 5

/**
 * Gym home inside the Sport tab. One focal action (start or resume), then routines, history,
 * records and settings as plain lists; destructive actions live in the detail dialogs, not on rows.
 */
@Composable
fun GymHome(vm: AolonViewModel, notify: (String) -> Unit, openWorkout: () -> Unit) {
    val gym = vm.gym
    var editing by remember { mutableStateOf<Routine?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<GymWorkout?>(null) }
    var progress by rememberSaveable { mutableStateOf<String?>(null) }
    var allHistory by rememberSaveable { mutableStateOf(false) }
    var allRecords by rememberSaveable { mutableStateOf(false) }
    val begin: (() -> Unit) -> Unit = { start ->
        if (gym.active != null) notify("Finish or discard the current workout first.") else { start(); openWorkout() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val active = gym.active
        if (active != null) {
            SectionCard("Workout in progress", trailing = active.name) {
                Text("${active.entries.size} exercises, ${active.doneSets} sets done", color = Neon.Muted)
                PrimaryButton("Resume workout", openWorkout, Modifier.fillMaxWidth())
            }
        } else {
            PrimaryButton("Start empty workout", { begin(gym::startEmpty) }, Modifier.fillMaxWidth())
        }

        SectionCard("Routines") {
            if (gym.routines.isEmpty()) Text("Save exercises you repeat, then start them with one tap.", color = Neon.Muted)
            gym.routines.forEachIndexed { i, r ->
                if (i > 0) RowDivider()
                SettingRow(r.name, r.exercises.joinToString(", ") { gym.exercise(it).name }, onClick = { begin { gym.start(r) } }) {
                    IconButton({ editing = r }) { Icon(Icons.Outlined.Edit, "Edit ${r.name}", tint = Neon.Muted) }
                }
            }
            TextButton({ creating = true }) { Text("New routine", color = Neon.Blue) }
        }

        SectionCard("History") {
            if (gym.history.isEmpty()) Text("No workouts yet. Finished workouts appear here.", color = Neon.Muted)
            val recent = gym.history.asReversed()
            recent.take(if (allHistory) recent.size else PREVIEW).forEachIndexed { i, w ->
                if (i > 0) RowDivider()
                SettingRow(w.name, summary(w), onClick = { detail = w })
            }
            if (recent.size > PREVIEW) ShowAll(allHistory, recent.size) { allHistory = !allHistory }
        }

        // Most-trained lifts first: those are the records a lifter checks.
        val lifts = gym.history.flatMap { w -> w.entries.map { it.exerciseId } }.distinct()
            .mapNotNull { id -> records(gym.history, id)?.let { id to it } }
            .sortedByDescending { it.second.series.size }
        SectionCard("Records") {
            if (lifts.isEmpty()) Text("Your best lifts show here after the first finished workout.", color = Neon.Muted)
            lifts.take(if (allRecords) lifts.size else PREVIEW).forEachIndexed { i, (id, r) ->
                if (i > 0) RowDivider()
                SettingRow(gym.exercise(id).name, "Heaviest ${kg(r.heaviestKg)}", onClick = { progress = id }) {
                    Text(kg(round1(r.bestE1rm)), style = Telemetry, color = Neon.Blue)
                }
            }
            if (lifts.size > PREVIEW) ShowAll(allRecords, lifts.size) { allRecords = !allRecords }
        }

        SettingsCard(vm)
    }

    if (creating || editing != null) RoutineEditor(vm, editing) { creating = false; editing = null }
    detail?.let { w ->
        WorkoutDialog(vm, w, close = { detail = null }, repeat = { begin { gym.repeat(w) } }, notify = notify)
    }
    progress?.let { id -> RecordDialog(vm, id) { progress = null } }
}

@Composable
private fun ShowAll(expanded: Boolean, total: Int, toggle: () -> Unit) {
    TextButton(toggle) { Text(if (expanded) "Show less" else "Show all $total", color = Neon.Blue) }
}

@Composable
private fun SettingsCard(vm: AolonViewModel) {
    val context = LocalContext.current
    SectionCard("Settings") {
        Text("Rest between sets", color = Neon.White, style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(REST_CHOICES.map { "${it}s" }, { it == "${vm.settings.restSeconds}s" }, { choice ->
            vm.updateSettings { it.copy(restSeconds = choice.removeSuffix("s").toInt()) }
        })
        RowDivider()
        when {
            !vm.lyftaConnected -> SettingRow(
                "Connect Lyfta",
                if (vm.lyftaBusy) "Opening Lyfta sign-in…" else "Import your latest 20 Lyfta workouts",
                onClick = { if (!vm.lyftaBusy) vm.connectLyfta { context.startActivity(Intent(Intent.ACTION_VIEW, it)) } },
            )
            else -> {
                SettingRow("Lyfta", if (vm.lyftaBusy) "Syncing…" else "Connected. Syncs each time the app opens.") {
                    TextButton(vm::syncLyfta, enabled = !vm.lyftaBusy) { Text("Sync", color = Neon.Blue) }
                }
                SettingRow("Disconnect Lyfta", "Imported workouts stay in your history.", onClick = vm::disconnectLyfta)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkoutDialog(vm: AolonViewModel, w: GymWorkout, close: () -> Unit, repeat: () -> Unit, notify: (String) -> Unit) {
    val gym = vm.gym
    AlertDialog(
        onDismissRequest = close,
        title = { Text(w.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary(w), color = Neon.Muted)
                w.entries.forEach { e ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(gym.exercise(e.exerciseId).name, color = Neon.White, style = MaterialTheme.typography.titleSmall)
                        // softWrap off keeps "13.5 × 12" on one line; FlowRow wraps whole sets instead.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            e.sets.forEach { Text("${kgText(it.kg)} × ${it.reps}", style = Telemetry, color = Neon.Blue, softWrap = false) }
                        }
                    }
                }
                RowDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({
                        val routine = gym.saveAsRoutine(w)
                        notify(if (routine == null) "A routine named ${w.name} already exists." else "Saved as routine ${routine.name}.")
                    }) { Text("Save as routine", color = Neon.Blue) }
                    TextButton({
                        gym.deleteWorkout(w)
                        close()
                    }) { Text("Delete", color = Neon.Red) }
                }
            }
        },
        // Repeat is the reason to open a past workout, so it takes the primary slot.
        confirmButton = {
            TextButton({
                close()
                repeat()
            }) { Text("Repeat workout", color = Neon.Purple) }
        },
        dismissButton = { TextButton(close) { Text("Close") } },
        containerColor = Neon.PanelLight,
    )
}

@Composable
private fun RecordDialog(vm: AolonViewModel, id: String, close: () -> Unit) {
    val r = records(vm.gym.history, id)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(vm.gym.exercise(id).name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (r == null) Text("No finished sets yet.", color = Neon.Muted)
                else {
                    Text("Estimated 1RM per workout", color = Neon.Muted, style = MaterialTheme.typography.labelMedium)
                    if (r.series.size >= 2) TrendLine(r.series.takeLast(12).map { it.second })
                    Text(kg(round1(r.series.last().second)), style = Telemetry.copy(fontSize = 32.sp), color = Neon.Purple)
                    Row(Modifier.fillMaxWidth()) {
                        Figure("Best 1RM", kg(round1(r.bestE1rm)), Modifier.weight(1f))
                        Figure("Heaviest", kg(r.heaviestKg), Modifier.weight(1f))
                    }
                    Text("1RM is estimated from your sets with the Epley formula.", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(close) { Text("Close") } },
        containerColor = Neon.PanelLight,
    )
}

@Composable
private fun RoutineEditor(vm: AolonViewModel, existing: Routine?, close: () -> Unit) {
    val gym = vm.gym
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    val picked = remember { mutableStateListOf<String>().apply { addAll(existing?.exercises.orEmpty()) } }
    var picking by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (existing == null) "New routine" else "Edit routine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name, e.g. Push Day") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                picked.forEachIndexed { i, id ->
                    SettingRow("${i + 1}. ${gym.exercise(id).name}") {
                        IconButton({ picked.removeAt(i) }) { Icon(Icons.Outlined.Delete, "Remove ${gym.exercise(id).name}", tint = Neon.Muted) }
                    }
                }
                TextButton({ picking = true }) { Text("Add exercise", color = Neon.Blue) }
                if (existing != null) TextButton({
                    gym.deleteRoutine(existing)
                    close()
                }) { Text("Delete routine", color = Neon.Red) }
            }
        },
        confirmButton = {
            TextButton({
                gym.saveRoutine(existing, name.trim(), picked.toList())
                close()
            }, enabled = name.isNotBlank() && picked.isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
        containerColor = Neon.PanelLight,
    )
    if (picking) ExercisePicker(gym, { picked += it; picking = false }, { picking = false })
}

/** Date, sets and volume; duration only when known, since Lyfta imports carry none. */
private fun summary(w: GymWorkout): String {
    val seconds = ((w.endedAt ?: w.startedAt) - w.startedAt) / 1000
    return listOfNotNull(
        SimpleDateFormat("EEE d MMM", Locale.US).format(Date(w.startedAt)),
        formatDuration(seconds).takeIf { seconds >= 60 },
        "${w.doneSets} sets",
        kg(volume(w)),
    ).joinToString(" · ")
}

// Non-breaking space so a unit never wraps onto its own line.
private fun kg(v: Double) = (if (v >= 1000) grouped(v.roundToInt()) else kgText(v)) + " kg"
private fun round1(v: Double) = Math.round(v * 10) / 10.0
