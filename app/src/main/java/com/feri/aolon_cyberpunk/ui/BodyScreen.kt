package com.feri.aolon_cyberpunk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.BodyFat
import com.feri.aolon_cyberpunk.Profile
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun BodyScreen(vm: AolonViewModel, notify: (String) -> Unit) {
    val saved = vm.profile
    var male by rememberSaveable { mutableStateOf(saved.male) }
    var age by rememberSaveable { mutableStateOf(saved.age?.toString() ?: "") }
    var height by rememberSaveable { mutableStateOf(saved.heightCm?.let(::trim) ?: "") }
    var weight by rememberSaveable { mutableStateOf(saved.weightKg?.let(::trim) ?: "") }
    var neck by rememberSaveable { mutableStateOf("") }
    var waist by rememberSaveable { mutableStateOf("") }
    var hip by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<BodyFat.Result?>(null) }
    var savedResult by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Any edit invalidates the shown result so it never describes other numbers.
    val edit: (String, (String) -> Unit) -> Unit = { v, set -> set(v); result = null; error = null }

    fun calculate() {
        val a = age.toIntOrNull()
        val h = height.toDoubleOrNull()
        val w = weight.toDoubleOrNull()
        val n = neck.toDoubleOrNull()
        val wa = waist.toDoubleOrNull()
        val hi = hip.toDoubleOrNull()
        error = when {
            a == null || a !in 2..120 -> "Enter an age from 2 to 120."
            h == null || h !in 50.0..250.0 -> "Enter a height from 50 to 250 cm."
            w == null || w !in 10.0..300.0 -> "Enter a weight from 10 to 300 kg."
            n == null || n !in 10.0..80.0 -> "Enter a neck size from 10 to 80 cm."
            wa == null || wa !in 30.0..250.0 -> "Enter a waist size from 30 to 250 cm."
            !male && (hi == null || hi !in 30.0..250.0) -> "Enter a hip size from 30 to 250 cm."
            else -> null
        }
        if (error != null) return
        result = BodyFat.calculate(male, a!!, h!!, w!!, n!!, wa!!, if (male) null else hi)
        if (result == null) error = if (male) "Waist must be larger than neck." else "Waist plus hip must be larger than neck."
        else {
            savedResult = false
            // Age, height and weight also drive the calorie estimate below.
            vm.updateProfile(Profile(male, a, h, w))
        }
    }

    TabScaffold("Body") {
        item {
            SectionCard("Body fat calculator", trailing = "U.S. Navy method") {
                ChoiceChips(listOf("Male", "Female"), { (it == "Male") == male }, { edit(it) { male = it == "Male" } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Age", age, { edit(it) { v -> age = v } }, Modifier.weight(1f), decimal = false)
                    NumberField("Weight (kg)", weight, { edit(it) { v -> weight = v } }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Height (cm)", height, { edit(it) { v -> height = v } }, Modifier.weight(1f))
                    NumberField("Neck (cm)", neck, { edit(it) { v -> neck = v } }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Waist (cm)", waist, { edit(it) { v -> waist = v } }, Modifier.weight(1f))
                    if (!male) NumberField("Hip (cm)", hip, { edit(it) { v -> hip = v } }, Modifier.weight(1f))
                    else Text("", Modifier.weight(1f))
                }
                Text(
                    "Measure the neck below the larynx, the waist at the navel" + (if (male) "." else ", and the hip at its widest point."),
                    color = Neon.Soft,
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = Neon.Red, style = MaterialTheme.typography.bodySmall) }
                PrimaryButton("Calculate", ::calculate, Modifier.fillMaxWidth())
            }
        }
        result?.let { r ->
            item {
                SectionCard("Result", trailing = r.category) {
                    Text("%.1f%%".format(r.navy), style = Telemetry.copy(fontSize = 44.sp), color = Neon.Purple)
                    Row(Modifier.fillMaxWidth()) {
                        Figure("Fat mass", "%.1f kg".format(r.fatMassKg), Modifier.weight(1f))
                        Figure("Lean mass", "%.1f kg".format(r.leanMassKg), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Figure("Ideal for age", "%.1f%%".format(r.ideal), Modifier.weight(1f))
                        Figure("To reach ideal", "%.1f kg".format(r.toLoseKg), Modifier.weight(1f))
                    }
                    Figure("BMI method", "%.1f%%".format(r.bmiMethod))
                    Text(
                        "Category from the American Council on Exercise. Ideal from Jackson & Pollock. Tape-measure estimates, not a medical test.",
                        color = Neon.Soft,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PrimaryButton(if (savedResult) "Saved" else "Save to history", {
                        vm.saveBodyLog(weight.toDouble(), r)
                        savedResult = true
                        notify("Body fat saved.")
                    }, Modifier.fillMaxWidth(), enabled = !savedResult)
                }
            }
        }
        item { BodyHistory(vm) }
        item { CaloriesCard(vm) }
    }
}

@Composable
private fun BodyHistory(vm: AolonViewModel) {
    SectionCard("Body fat history", trailing = vm.bodyLogs.size.takeIf { it > 0 }?.let { "$it saved" }) {
        if (vm.bodyLogs.isEmpty()) {
            StateMessage("No measurements saved yet. Calculate above and tap Save to history.")
            return@SectionCard
        }
        if (vm.bodyLogs.size >= 2) TrendLine(vm.bodyLogs.takeLast(12).map { it.bodyFat })
        val format = SimpleDateFormat("d MMM yyyy", Locale.US)
        vm.bodyLogs.asReversed().forEachIndexed { i, log ->
            if (i > 0) RowDivider()
            SettingRow("%.1f%%, %.1f kg".format(log.bodyFat, log.weightKg), format.format(Date(log.at))) {
                IconButton({ vm.removeBodyLog(log) }) { Icon(Icons.Outlined.Delete, "Delete measurement", tint = Neon.Red) }
            }
        }
    }
}

/** Body fat over the last saved measurements; padded by one point so a flat trend stays visible. */
@Composable
fun TrendLine(values: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "Body fat trend chart" }) {
        val lo = values.min() - 1
        val hi = values.max() + 1
        val step = size.width / (values.size - 1)
        val points = values.mapIndexed { i, v -> Offset(i * step, (size.height * (1 - (v - lo) / (hi - lo))).toFloat()) }
        drawPath(Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }, Neon.Purple, style = Stroke(2.dp.toPx()))
        points.forEach { drawCircle(Neon.Purple, 4.dp.toPx(), it) }
    }
}

@Composable
private fun CaloriesCard(vm: AolonViewModel) {
    val today = LocalDate.now()
    var dayEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(dayEpoch)
    var name by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val meals = vm.food.filter { it.date == day }
    val eaten = meals.sumOf { it.kcal }
    val burn = vm.burnOn(day)

    SectionCard("Calories") {
        PeriodNavigator(
            when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US))
            },
            canBack = true,
            canForward = day < today,
            unit = "day",
            onBack = { dayEpoch-- },
            onForward = { dayEpoch++ },
        )
        Row(Modifier.fillMaxWidth()) {
            Figure("Eaten", grouped(eaten), Modifier.weight(1f))
            Figure("Burned", burn?.let { grouped(it.total) } ?: "--", Modifier.weight(1f))
            Figure("Balance", burn?.let { "%+,d".format(Locale.US, eaten - it.total) } ?: "--", Modifier.weight(1f))
        }
        Text(
            burn?.let { "Burned is an estimate: resting ${grouped(it.resting)} + steps ${grouped(it.steps)} + workouts ${grouped(it.workouts)} kcal." }
                ?: "Run the body fat calculator once so age, height and weight can estimate calories burned.",
            color = Neon.Soft,
            style = MaterialTheme.typography.bodySmall,
        )
        meals.forEach { meal ->
            RowDivider()
            SettingRow(meal.name, "${grouped(meal.kcal)} kcal") {
                IconButton({ vm.removeFood(meal) }) { Icon(Icons.Outlined.Delete, "Delete ${meal.name}", tint = Neon.Red) }
            }
        }
        if (meals.isEmpty()) Text("Nothing logged for this day.", color = Neon.Muted, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40); error = null },
                label = { Text("Food") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.weight(1.4f),
            )
            NumberField("kcal", kcal, { kcal = it; error = null }, Modifier.weight(1f), decimal = false)
        }
        error?.let { Text(it, color = Neon.Red, style = MaterialTheme.typography.bodySmall) }
        PrimaryButton("Add food", {
            val amount = kcal.toIntOrNull()
            when {
                name.isBlank() -> error = "Enter what you ate."
                amount == null || amount !in 1..10_000 -> error = "Enter calories from 1 to 10,000."
                else -> {
                    vm.addFood(day, name.trim(), amount)
                    name = ""
                    kcal = ""
                }
            }
        }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true) {
    OutlinedTextField(
        value = value,
        // Some keyboards type a comma as the decimal mark.
        onValueChange = { v -> onChange(v.replace(',', '.').filter { it.isDigit() || decimal && it == '.' }.take(6)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}

private fun trim(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
