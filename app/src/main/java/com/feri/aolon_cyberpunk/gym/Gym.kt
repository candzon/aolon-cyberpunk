package com.feri.aolon_cyberpunk.gym

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Exercise(val id: String, val name: String, val muscle: String, val custom: Boolean = false)
data class GymSet(val kg: Double, val reps: Int, val done: Boolean = false)
data class ExerciseLog(val exerciseId: String, val sets: List<GymSet>)
data class Routine(val id: String, val name: String, val exercises: List<String>)

data class GymWorkout(
    val id: String,
    val name: String,
    val routineId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val entries: List<ExerciseLog>,
) {
    val date: LocalDate get() = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val doneSets get() = entries.sumOf { e -> e.sets.count { it.done } }
}

class Records(
    val heaviestKg: Double,
    val bestE1rm: Double,
    val bestSetVolume: Double,
    /** Best estimated 1RM per finished workout, oldest first, for the progress chart. */
    val series: List<Pair<Long, Double>>,
)

/** Epley estimate; a single rep is the 1RM itself. */
fun e1rm(kg: Double, reps: Int): Double = if (reps <= 1) kg else kg * (1 + reps / 30.0)

/** Total kg moved in completed sets only. */
fun volume(workout: GymWorkout): Double = workout.entries.sumOf { e -> e.sets.filter { it.done }.sumOf { it.kg * it.reps } }

/** Completed sets of [exerciseId] from the most recent finished workout that contains it. */
fun previousSets(history: List<GymWorkout>, exerciseId: String): List<GymSet> =
    history.filter { it.endedAt != null }.sortedByDescending { it.startedAt }.firstNotNullOfOrNull { w ->
        w.entries.firstOrNull { it.exerciseId == exerciseId }?.sets?.filter { it.done }?.takeIf { it.isNotEmpty() }
    }.orEmpty()

fun records(history: List<GymWorkout>, exerciseId: String): Records? {
    val sessions = history.filter { it.endedAt != null }.sortedBy { it.startedAt }.mapNotNull { w ->
        val sets = w.entries.filter { it.exerciseId == exerciseId }.flatMap { it.sets }.filter { it.done && it.reps > 0 }
        if (sets.isEmpty()) null else w.startedAt to sets
    }
    if (sessions.isEmpty()) return null
    val all = sessions.flatMap { it.second }
    return Records(
        heaviestKg = all.maxOf { it.kg },
        bestE1rm = all.maxOf { e1rm(it.kg, it.reps) },
        bestSetVolume = all.maxOf { it.kg * it.reps },
        series = sessions.map { (at, sets) -> at to sets.maxOf { e1rm(it.kg, it.reps) } },
    )
}

/** "60" for whole kilos, "62.5" otherwise. */
fun kgText(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else "%.1f".format(java.util.Locale.US, kg).trimEnd('0').trimEnd('.')
