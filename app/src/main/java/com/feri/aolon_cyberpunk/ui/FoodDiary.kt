package com.feri.aolon_cyberpunk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feri.aolon_cyberpunk.Activity
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.Food
import com.feri.aolon_cyberpunk.Macros
import com.feri.aolon_cyberpunk.Meal
import com.feri.aolon_cyberpunk.WeightGoal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** fatsecret-style diary: the day's target up top, then food grouped by meal, each meal with its own add. */
@Composable
fun DiaryCard(vm: AolonViewModel) {
    val today = LocalDate.now()
    var dayEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(dayEpoch)
    var adding by rememberSaveable { mutableStateOf<Meal?>(null) }
    var editing by remember { mutableStateOf<Food?>(null) }
    val foods = vm.food.filter { it.date == day }
    val eaten = foods.sumOf { it.kcal }
    val rdi = vm.profile.rdi
    val burn = vm.burnOn(day)

    SectionCard("Food diary") {
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
            Figure("Goal", rdi?.let(::grouped) ?: "--", Modifier.weight(1f))
            Figure(if (rdi != null && eaten > rdi) "Over" else "Left", rdi?.let { grouped(abs(it - eaten)) } ?: "--", Modifier.weight(1f))
        }
        if (rdi != null) {
            LinearProgressIndicator(
                progress = { (eaten / rdi.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (eaten > rdi) Neon.Red else Neon.Purple,
                trackColor = Neon.PanelLight,
            )
            Text("${eaten * 100 / rdi}% of your daily target", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
        }
        foods.mapNotNull { it.macros }.reduceOrNull(Macros::plus)?.let { m ->
            val split = m.energySplit()
            Text(
                "Protein ${grams(m.protein)} · Carbs ${grams(m.carbs)} · Fat ${grams(m.fat)}" +
                    (split?.let { (p, c, f) -> "\nEnergy split $p% / $c% / $f%" } ?: ""),
                color = Neon.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when {
            !vm.profile.complete -> "Run the body fat calculator once so age, height and weight can set your daily target."
            rdi == null -> "The daily target uses the adult formula, so it needs an age of 19 or over."
            burn != null -> "Burned, estimated: ${grouped(burn.total)} kcal (resting ${grouped(burn.resting)}, steps ${grouped(burn.steps)}, workouts ${grouped(burn.workouts)})."
            else -> null
        }?.let { Text(it, color = Neon.Soft, style = MaterialTheme.typography.bodySmall) }
        Meal.entries.forEach { meal ->
            val items = foods.filter { it.meal == meal }
            RowDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meal.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(if (items.isEmpty()) "" else "${grouped(items.sumOf { it.kcal })} kcal", style = Telemetry, color = Neon.Blue)
            }
            items.forEach { food ->
                SettingRow(food.name, foodDetail(food), onClick = { editing = food }) {
                    IconButton({ vm.removeFood(food) }) { Icon(Icons.Outlined.Delete, "Delete ${food.name}", tint = Neon.Red) }
                }
            }
            TextButton({ adding = meal }) { Text("Add to ${meal.label}", color = Neon.Blue) }
        }
    }

    adding?.let { meal -> FoodDialog(vm, day, meal, null) { adding = null } }
    editing?.let { food -> FoodDialog(vm, food.date, food.meal, food) { editing = null } }
}

/** Adds a food to [initialMeal], or edits [existing] when given, where the meal can also change. */
@Composable
private fun FoodDialog(vm: AolonViewModel, day: LocalDate, initialMeal: Meal, existing: Food?, close: () -> Unit) {
    val m = existing?.macros
    var meal by rememberSaveable { mutableStateOf(initialMeal) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var kcal by rememberSaveable { mutableStateOf(existing?.kcal?.toString() ?: "") }
    var protein by rememberSaveable { mutableStateOf(m?.protein?.let(::trimKg) ?: "") }
    var carbs by rememberSaveable { mutableStateOf(m?.carbs?.let(::trimKg) ?: "") }
    var fat by rememberSaveable { mutableStateOf(m?.fat?.let(::trimKg) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val recent = remember { if (existing == null) vm.recentFoods() else emptyList() }

    fun save() {
        val amount = kcal.toIntOrNull()
        val grams = listOf(protein, carbs, fat).map { it.toDoubleOrNull() }
        error = when {
            name.isBlank() -> "Enter what you ate."
            amount == null || amount !in 1..10_000 -> "Enter calories from 1 to 10,000."
            grams.any { it != null && it > 1000 } -> "Enter macros up to 1,000 g."
            else -> null
        }
        if (error != null) return
        // Macros are optional; any one filled in stores all three, blanks as zero.
        val macros = if (grams.all { it == null }) null else Macros(grams[0] ?: 0.0, grams[1] ?: 0.0, grams[2] ?: 0.0)
        if (existing == null) vm.addFood(day, meal, name.trim(), amount!!, macros)
        else vm.updateFood(existing.copy(meal = meal, name = name.trim(), kcal = amount!!, macros = macros))
        close()
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (existing == null) "Add to ${meal.label}" else "Edit food") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) ChoiceChips(Meal.entries.map { it.label }, { it == meal.label }, { label ->
                    meal = Meal.entries.first { it.label == label }
                })
                if (recent.isNotEmpty()) {
                    Text("Recently eaten", color = Neon.Soft, style = MaterialTheme.typography.labelMedium)
                    recent.forEach { food ->
                        SettingRow(food.name, foodDetail(food), onClick = {
                            vm.addFood(day, meal, food.name, food.kcal, food.macros)
                            close()
                        })
                    }
                    RowDivider()
                    Text("Or add a new food", color = Neon.Soft, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40); error = null },
                    label = { Text("Food") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField("kcal", kcal, { kcal = it; error = null }, Modifier.fillMaxWidth(), decimal = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Protein g", protein, { protein = it; error = null }, Modifier.weight(1f))
                    NumberField("Carbs g", carbs, { carbs = it; error = null }, Modifier.weight(1f))
                    NumberField("Fat g", fat, { fat = it; error = null }, Modifier.weight(1f))
                }
                Text("Macros are optional.", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = Neon.Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(::save) { Text(if (existing == null) "Add" else "Save", color = Neon.Purple) } },
        dismissButton = { TextButton(close) { Text("Cancel") } },
        containerColor = Neon.PanelLight,
    )
}

/** Start, current and goal weight with progress, plus a weight-only log. */
@Composable
fun WeightCard(vm: AolonViewModel, notify: (String) -> Unit) {
    val logs = vm.bodyLogs
    val goal = vm.profile.goalWeightKg
    val current = logs.lastOrNull()?.weightKg ?: vm.profile.weightKg
    var weight by rememberSaveable { mutableStateOf("") }
    var goalText by rememberSaveable { mutableStateOf(goal?.let(::trimKg) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    SectionCard("Weight", trailing = goal?.let { "Goal ${trimKg(it)} kg" }) {
        if (current == null) Text("No weight yet. Weigh in below to start tracking.", color = Neon.Muted)
        else {
            val start = logs.firstOrNull()?.weightKg ?: current
            val change = start - current
            Row(Modifier.fillMaxWidth()) {
                Figure("Start", "%.1f kg".format(start), Modifier.weight(1f))
                Figure("Current", "%.1f kg".format(current), Modifier.weight(1f))
                Figure(if (change >= 0) "Lost so far" else "Gained so far", "%.1f kg".format(abs(change)), Modifier.weight(1f))
            }
            if (goal != null) Text(
                if (abs(current - goal) < 0.05) "Goal reached." else "%.1f kg to go".format(abs(current - goal)),
                style = Telemetry,
                color = Neon.Purple,
            )
            if (logs.size >= 2) TrendLine(logs.takeLast(12).map { it.weightKg }, "Weight trend chart")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Weight (kg)", weight, { weight = it; error = null }, Modifier.weight(1f))
            PrimaryButton("Weigh in", {
                val w = weight.toDoubleOrNull()
                if (w == null || w !in 10.0..300.0) error = "Enter a weight from 10 to 300 kg."
                else {
                    vm.weighIn(w)
                    weight = ""
                    notify("Weight saved.")
                }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Goal weight (kg)", goalText, { goalText = it; error = null }, Modifier.weight(1f))
            SecondaryButton("Set goal", {
                val g = goalText.toDoubleOrNull()
                if (g == null || g !in 10.0..300.0) error = "Enter a goal weight from 10 to 300 kg."
                else {
                    vm.updateProfile(vm.profile.copy(goalWeightKg = g))
                    notify("Goal weight set.")
                }
            })
        }
        error?.let { Text(it, color = Neon.Red, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Activity level and diet goal; together with the profile they set the daily calorie target. */
@Composable
fun CalorieGoalCard(vm: AolonViewModel) {
    val p = vm.profile
    SectionCard("Daily target", trailing = p.rdi?.let { "${grouped(it)} kcal" }) {
        Text("Activity level", color = Neon.White, style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(Activity.entries.map { it.label }, { it == p.activity.label }, { label ->
            vm.updateProfile(vm.profile.copy(activity = Activity.entries.first { it.label == label }))
        })
        RowDivider()
        Text("Goal", color = Neon.White, style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(WeightGoal.entries.map { it.label }, { it == p.goal.label }, { label ->
            vm.updateProfile(vm.profile.copy(goal = WeightGoal.entries.first { it.label == label }))
        })
        Text(
            "Institute of Medicine energy estimate for your age, height, weight and activity, then 500 kcal a day per 0.5 kg a week. " +
                "Never set below ${if (p.male) "1,500" else "1,200"} kcal.",
            color = Neon.Soft,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true) {
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

private fun foodDetail(food: Food): String =
    "${grouped(food.kcal)} kcal" + (food.macros?.let { " · P ${grams(it.protein)} C ${grams(it.carbs)} F ${grams(it.fat)}" } ?: "")

private fun grams(g: Double) = (if (g % 1.0 == 0.0) g.toInt().toString() else "%.1f".format(Locale.US, g)) + " g"

internal fun trimKg(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
