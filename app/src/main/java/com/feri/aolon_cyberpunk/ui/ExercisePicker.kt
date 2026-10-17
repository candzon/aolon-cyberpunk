package com.feri.aolon_cyberpunk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feri.aolon_cyberpunk.gym.GymLog
import com.feri.aolon_cyberpunk.gym.MUSCLES

/** Full-screen exercise search; [onPick] gets the id of a built-in or a just-created custom exercise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePicker(gym: GymLog, onPick: (String) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newMuscle by rememberSaveable { mutableStateOf(MUSCLES.first()) }
    val shown = gym.exercises.filter {
        (muscle == null || it.muscle == muscle) && it.name.contains(query.trim(), ignoreCase = true)
    }.sortedBy { it.name }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Neon.Void)) {
            TopAppBar(
                title = { Text("Add exercise") },
                navigationIcon = { IconButton(onClose) { Icon(Icons.Filled.Close, "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Neon.Void),
            )
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(40) },
                    label = { Text(if (creating) "New exercise name" else "Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (creating) {
                    ChoiceChips(MUSCLES, { it == newMuscle }, { newMuscle = it })
                    PrimaryButton("Create and add", {
                        onPick(gym.addCustomExercise(query.trim(), newMuscle).id)
                    }, Modifier.fillMaxWidth(), enabled = query.isNotBlank())
                    SecondaryButton("Cancel", { creating = false }, Modifier.fillMaxWidth())
                } else {
                    ChoiceChips(listOf("All") + MUSCLES, { (it == "All" && muscle == null) || it == muscle }, { muscle = it.takeIf { m -> m != "All" } })
                }
            }
            if (!creating) LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                item {
                    SettingRow("Create custom exercise", if (query.isBlank()) "Not in the list? Add your own." else "Add \"${query.trim()}\"", onClick = { creating = true })
                    RowDivider()
                }
                if (shown.isEmpty()) item {
                    Text("No exercise matches.", color = Neon.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
                }
                items(shown, key = { it.id }) { e ->
                    SettingRow(e.name, e.muscle + if (e.custom) " · custom" else "", onClick = { onPick(e.id) })
                }
            }
        }
    }
}
