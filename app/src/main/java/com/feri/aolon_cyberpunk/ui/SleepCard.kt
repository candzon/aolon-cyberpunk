package com.feri.aolon_cyberpunk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.SleepNight
import com.feri.aolon_cyberpunk.SleepStage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun stageColor(stage: SleepStage): Color = when (stage) {
    SleepStage.Deep -> Neon.Purple
    SleepStage.Light -> Neon.Blue
    SleepStage.Rem -> Neon.Red
    SleepStage.Awake -> Neon.Muted
}

internal fun hoursMinutes(minutes: Int) = "%dh %02dm".format(minutes / 60, minutes % 60)

private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
fun SleepCard(vm: AolonViewModel) {
    val nights = vm.sleep.keys.sorted()
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    val date = picked?.let(LocalDate::ofEpochDay)?.takeIf { it in vm.sleep } ?: nights.lastOrNull()
    val night = date?.let { vm.sleep[it] }
    SectionCard("Sleep", trailing = night?.let { hoursMinutes(it.asleep) }) {
        if (night == null) {
            StateMessage(
                if (vm.hasSleep == false) "This watch does not share sleep over Bluetooth."
                else "Wear the watch to bed. Last night's sleep shows up here after the next sync.",
            )
            return@SectionCard
        }
        val index = nights.indexOf(night.date)
        val today = LocalDate.now()
        PeriodNavigator(
            when (night.date) {
                today -> "Last night"
                today.minusDays(1) -> "Night before last"
                else -> "Night to " + night.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
            },
            canBack = index > 0,
            canForward = index < nights.lastIndex,
            unit = "night",
            onBack = { picked = nights[index - 1].toEpochDay() },
            onForward = { picked = nights[index + 1].toEpochDay() },
        )
        Text(hoursMinutes(night.asleep), style = Telemetry.copy(fontSize = 40.sp), color = Neon.Purple)
        Text(
            "Asleep, ${clock(night.bedtime!!)} to ${clock(night.wake!!)}. Woke ${night.wakeUps} " + if (night.wakeUps == 1) "time." else "times.",
            color = Neon.Soft,
            style = MaterialTheme.typography.bodySmall,
        )
        Hypnogram(night)
        val total = night.segments.sumOf { it.minutes }.coerceAtLeast(1)
        Row(Modifier.fillMaxWidth()) {
            listOf(SleepStage.Deep, SleepStage.Light, SleepStage.Rem, SleepStage.Awake).forEach { stage ->
                val m = night.minutesIn(stage)
                StageFigure(stage, m, m * 100 / total, Modifier.weight(1f))
            }
        }
        val week = nights.filter { it > night.date.minusDays(7) && it <= night.date }.mapNotNull { vm.sleep[it]?.asleep }
        if (week.size >= 2) {
            Text(
                "Average over the last ${week.size} nights: ${hoursMinutes(week.sum() / week.size)}.",
                color = Neon.Soft,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Stages as rows (awake on top, deep at the bottom) across the night, like the official app's chart. */
@Composable
private fun Hypnogram(night: SleepNight) {
    val rows = listOf(SleepStage.Awake, SleepStage.Rem, SleepStage.Light, SleepStage.Deep)
    val span = night.segments.sumOf { it.minutes }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(88.dp).semantics { contentDescription = "Sleep stages chart" }) {
        val rowHeight = size.height / rows.size
        var x = 0f
        night.segments.forEach { s ->
            val w = size.width * s.minutes / span
            drawRect(stageColor(s.stage), Offset(x, rows.indexOf(s.stage) * rowHeight + 2.dp.toPx()), Size(w, rowHeight - 4.dp.toPx()))
            x += w
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(clock(night.bedtime!!), color = Neon.Soft, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text(clock(night.wake!!), color = Neon.Soft, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StageFigure(stage: SleepStage, minutes: Int, percent: Int, modifier: Modifier) {
    Column(modifier) {
        Text(stage.label, color = stageColor(stage), style = MaterialTheme.typography.labelMedium)
        Text(hoursMinutes(minutes), style = Telemetry.copy(fontSize = 15.sp))
        Text("$percent%", color = Neon.Soft, style = MaterialTheme.typography.labelSmall)
    }
}
