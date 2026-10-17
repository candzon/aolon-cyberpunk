package com.feri.aolon_cyberpunk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.cardio.ZONES
import com.feri.aolon_cyberpunk.cardio.maxHr
import com.feri.aolon_cyberpunk.cardio.timeInZones
import com.feri.aolon_cyberpunk.cardio.zoneBpm
import com.feri.aolon_cyberpunk.formatDuration

/** Target zone choice before Start; asks for age first because every zone hangs off 220 - age. */
@Composable
fun ZonePicker(vm: AolonViewModel) {
    val age = vm.profile.age
    if (age == null) {
        var text by rememberSaveable { mutableStateOf("") }
        Text("Heart rate zones need your age (max heart rate = 220 - age).", color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                label = { Text("Age") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextButton({ text.toIntOrNull()?.takeIf { it in 10..100 }?.let { vm.updateProfile(vm.profile.copy(age = it)) } }) {
                Text("Save age", color = Neon.Blue)
            }
        }
        return
    }
    val mhr = maxHr(age)
    val target = vm.settings.targetZone
    Text("Target heart rate zone", color = Neon.White, style = MaterialTheme.typography.bodyLarge)
    ChoiceChips(listOf("Off") + ZONES.map { "Zone ${it.number}" }, { it == (target?.let { z -> "Zone $z" } ?: "Off") }, { choice ->
        vm.updateSettings { it.copy(targetZone = choice.removePrefix("Zone ").toIntOrNull()) }
    })
    val zone = target?.let { ZONES[it - 1] }
    Text(
        if (zone == null) "Max heart rate $mhr bpm (220 - age $age, an estimate). Pick a zone to get alerts when you drift out of it."
        else zoneBpm(zone, mhr).let { "${zone.name}: ${it.first}-${it.last} bpm. ${zone.purpose} ${zone.feel}" },
        color = Neon.Soft,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Five segments, the current zone filled and the target outlined, so both read at a glance while moving. */
@Composable
fun ZoneBar(current: Int, target: Int?) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).semantics {
            contentDescription = "Heart rate zone ${if (current == 0) "below 1" else current}" + (target?.let { ", target zone $it" } ?: "")
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZONES.forEach { z ->
            val now = z.number == current
            Box(
                Modifier.weight(1f).height(28.dp)
                    .background(if (now) Neon.Purple else Neon.PanelLight, ControlShape)
                    .then(if (z.number == target) Modifier.border(2.dp, Neon.Blue, ControlShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("${z.number}", color = if (now) Neon.Void else Neon.Muted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** One line for the live screen: which zone, and what to do about the target. */
fun zoneMessage(zone: Int, target: Int?): String {
    val name = if (zone == 0) "Below zone 1" else "Zone $zone, ${ZONES[zone - 1].name}"
    return when {
        target == null -> "$name."
        zone > target -> "$name. Above target zone $target, ease off."
        zone < target -> "$name. Below target zone $target, pick up the pace."
        else -> "$name. In your target zone."
    }
}

@Composable
fun ZoneTimes(hr: List<Pair<Int, Int>>, age: Int, target: Int?) {
    val seconds = timeInZones(hr, maxHr(age))
    val total = seconds.sum().takeIf { it > 0 } ?: return
    SectionCard("Time in heart rate zones", trailing = target?.let { "Target zone $it" }) {
        target?.let { Text("${seconds[it] * 100 / total}% of the time in your target zone.", color = Neon.Muted) }
        (5 downTo 0).filter { it > 0 || seconds[0] > 0 }.forEach { z ->
            val label = if (z == 0) "Below zone 1" else "Zone $z, ${ZONES[z - 1].name}"
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f), color = if (z == target) Neon.Purple else Neon.White, style = MaterialTheme.typography.bodyMedium)
                    Text(formatDuration(seconds[z].toLong()), style = Telemetry, color = Neon.Muted)
                }
                LinearProgressIndicator(
                    progress = { seconds[z] / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (z == target) Neon.Purple else Neon.Blue,
                    trackColor = Neon.PanelLight,
                )
            }
        }
        Text("Zones from max heart rate = 220 - age (Cleveland Clinic bands). Your real max may differ.", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
    }
}
