package com.feri.aolon_cyberpunk.gym

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.roundToInt

// Constants match Lyfta 1.602's MuscleRecoveryScoreCalculator.
private const val WINDOW_DAYS = 7
private const val DECAY = 0.35
private const val FULL_FATIGUE = 20.0
private const val MAX_FATIGUE = 30.0

/**
 * Percent recovered per muscle group, Lyfta's model: every counted set adds fatigue that fades by
 * e^(-0.35 × days) over the last 7 days. Each exercise counts toward its one group at full weight,
 * since the library has no secondary muscles. Groups never trained read 100.
 */
fun recovery(history: List<GymWorkout>, muscleOf: (String) -> String, today: LocalDate = LocalDate.now()): Map<String, Int> {
    val fatigue = MUSCLES.associateWith { 0.0 }.toMutableMap()
    history.filter { it.endedAt != null }.forEach { w ->
        val days = ChronoUnit.DAYS.between(w.date, today)
        if (days !in 0..WINDOW_DAYS) return@forEach
        val decay = exp(-DECAY * days)
        w.entries.forEach { e ->
            val muscle = muscleOf(e.exerciseId).takeIf { it in fatigue } ?: return@forEach
            var drops = 0
            e.sets.filter { it.done }.forEach { s ->
                if (s.type == SetType.Drop) drops++
                fatigue[muscle] = fatigue.getValue(muscle) + setWeight(s, drops) * decay
            }
        }
    }
    return fatigue.mapValues { (_, f) -> recoveredPercent(f) }
}

/** Lyfta's SetFatigueWeight: low-rep failure sets and long drop chains count for less. */
internal fun setWeight(set: GymSet, dropIndex: Int): Double = when (set.type) {
    SetType.WarmUp -> 0.0
    SetType.Drop -> if (dropIndex > 4) 0.25 else 0.5
    SetType.Failure -> if (set.reps <= 0) 0.0 else if (set.reps <= 2) 0.35 else 0.75
    SetType.Normal -> 1.0
}

internal fun recoveredPercent(fatigue: Double): Int = when {
    fatigue <= 0 -> 100
    fatigue >= MAX_FATIGUE -> 0
    else -> ((1 - fatigue / FULL_FATIGUE) * 100).coerceAtLeast(0.0).roundToInt()
}
