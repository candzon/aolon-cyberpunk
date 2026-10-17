package com.feri.aolon_cyberpunk.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.formatDuration
import com.feri.aolon_cyberpunk.gym.GymSet
import com.feri.aolon_cyberpunk.gym.GymWorkout
import com.feri.aolon_cyberpunk.gym.kgText
import com.feri.aolon_cyberpunk.gym.previousSets
import com.feri.aolon_cyberpunk.gym.volume
import kotlinx.coroutines.delay

@Composable
fun GymWorkoutScreen(vm: AolonViewModel, workout: GymWorkout, notify: (String) -> Unit, onBack: () -> Unit) {
    val gym = vm.gym
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Rest end is checked here so the buzz fires even while the user is scrolling.
            if (gym.restEndsAt != 0L && now >= gym.restEndsAt) {
                gym.restEndsAt = 0L
                buzz(context)
            }
            delay(250)
        }
    }

    TabScaffold(
        workout.name,
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to gym") } },
        actions = {
            TextButton({
                val done = gym.finish()
                if (done == null) notify("Tick at least one set with reps before finishing.")
                else {
                    notify("${done.name} saved: ${done.doneSets} sets, ${kgText(volume(done))} kg.")
                    onBack()
                }
            }) { Text("Finish", color = Neon.Purple, fontWeight = FontWeight.SemiBold) }
        },
        bottomBar = { if (gym.restEndsAt > now) RestBar(gym.restEndsAt - now, vm.settings.restSeconds, { gym.restEndsAt += it }, { gym.restEndsAt = 0L }) },
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                Figure("Duration", formatDuration((now - workout.startedAt).coerceAtLeast(0) / 1000), Modifier.weight(1f))
                Figure("Sets done", "${workout.doneSets}", Modifier.weight(1f))
                Figure("Volume", "${kgText(volume(workout))} kg", Modifier.weight(1f))
            }
        }
        if (workout.entries.isEmpty()) item { StateMessage("No exercises yet. Add one to start logging sets.") }
        workout.entries.forEachIndexed { ei, entry ->
            item(key = "$ei-${entry.exerciseId}") {
                val exercise = gym.exercise(entry.exerciseId)
                val previous = previousSets(gym.history, entry.exerciseId)
                SectionCard(exercise.name, trailing = exercise.muscle) {
                    SetHeader()
                    entry.sets.forEachIndexed { si, set ->
                        key(si) {
                            SetRow(si + 1, previous.getOrNull(si), set, { gym.updateSet(ei, si, it) }) {
                                if (!set.done && set.reps <= 0) notify("Enter reps first.")
                                else gym.toggleDone(ei, si, vm.settings.restSeconds)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Add set", { gym.addSet(ei) }, Modifier.weight(1f), Neon.Blue)
                        if (entry.sets.size > 1) SecondaryButton("Remove last set", { gym.removeSet(ei, entry.sets.lastIndex) }, Modifier.weight(1f))
                        else SecondaryButton("Remove exercise", { gym.removeExercise(ei) }, Modifier.weight(1f), Neon.Red)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Add exercise", { picking = true }, Modifier.fillMaxWidth())
                SecondaryButton("Discard workout", { confirmDiscard = true }, Modifier.fillMaxWidth(), Neon.Red)
            }
        }
    }

    if (picking) ExercisePicker(gym, { gym.addExercise(it); picking = false }, { picking = false })
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard this workout?") },
        text = { Text("Sets logged in this workout won't be saved. Finished workouts are not affected.") },
        confirmButton = {
            TextButton({
                gym.discard()
                confirmDiscard = false
                onBack()
            }) { Text("Discard", color = Neon.Red) }
        },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep logging") } },
        containerColor = Neon.PanelLight,
    )
}

@Composable
private fun SetHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Set" to 0.45f, "Previous" to 1.1f, "kg" to 1f, "Reps" to 1f).forEach { (label, w) ->
            Text(label, Modifier.weight(w), color = Neon.Soft, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
        Text("Done", Modifier.width(48.dp), color = Neon.Soft, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SetRow(number: Int, previous: GymSet?, set: GymSet, onChange: (GymSet) -> Unit, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$number", Modifier.weight(0.45f), style = Telemetry, textAlign = TextAlign.Center, color = if (set.done) Neon.Purple else Neon.White)
        Text(
            previous?.let { "${kgText(it.kg)} × ${it.reps}" } ?: "–",
            Modifier.weight(1.1f),
            color = Neon.Muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        SetField(set.kg, { onChange(set.copy(kg = it)) }, "kg", decimal = true, Modifier.weight(1f))
        SetField(set.reps.toDouble(), { onChange(set.copy(reps = it.toInt())) }, "reps", decimal = false, Modifier.weight(1f))
        IconButton(
            onToggle,
            Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (set.done) Neon.Purple else Neon.PanelLight,
                contentColor = if (set.done) Neon.Void else Neon.Muted,
            ),
        ) { Icon(Icons.Filled.Check, if (set.done) "Mark set $number not done" else "Mark set $number done") }
    }
}

/** Keeps the typed text (e.g. "62.") while it still parses to the stored value, so decimals can be typed. */
@Composable
private fun SetField(value: Double, onChange: (Double) -> Unit, label: String, decimal: Boolean, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    val shown = if ((text.toDoubleOrNull() ?: 0.0) == value) text else if (value == 0.0) "" else kgText(value)
    OutlinedTextField(
        value = shown,
        onValueChange = { v ->
            text = v.replace(',', '.').filter { it.isDigit() || decimal && it == '.' }.take(if (decimal) 6 else 3)
            onChange(text.toDoubleOrNull() ?: 0.0)
        },
        placeholder = { Text(label, color = Neon.Soft) },
        singleLine = true,
        textStyle = Telemetry.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}

@Composable
private fun RestBar(leftMs: Long, total: Int, adjust: (Long) -> Unit, skip: () -> Unit) {
    val seconds = (leftMs + 999) / 1000
    Column(Modifier.fillMaxWidth().background(Neon.PanelLight).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rest  %d:%02d".format(seconds / 60, seconds % 60), Modifier.weight(1f), style = Telemetry, color = Neon.Blue)
            TextButton({ adjust(-15_000) }) { Text("−15s") }
            TextButton({ adjust(15_000) }) { Text("+15s") }
            TextButton(skip) { Text("Skip", color = Neon.Purple) }
        }
        LinearProgressIndicator(
            progress = { (leftMs / (total * 1000f)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = Neon.Blue,
            trackColor = Neon.Panel,
        )
    }
}

/** Short double vibration for rest-over and heart-rate-zone alerts. */
fun buzz(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
}
