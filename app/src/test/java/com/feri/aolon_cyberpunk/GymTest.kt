package com.feri.aolon_cyberpunk

import com.feri.aolon_cyberpunk.gym.BUILT_IN_EXERCISES
import com.feri.aolon_cyberpunk.gym.ExerciseLog
import com.feri.aolon_cyberpunk.gym.GymSet
import com.feri.aolon_cyberpunk.gym.GymWorkout
import com.feri.aolon_cyberpunk.gym.e1rm
import com.feri.aolon_cyberpunk.gym.kgText
import com.feri.aolon_cyberpunk.gym.previousSets
import com.feri.aolon_cyberpunk.gym.records
import com.feri.aolon_cyberpunk.gym.volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GymTest {
    private val bench = "b-bench-press"
    private fun workout(at: Long, vararg sets: GymSet, ended: Boolean = true) =
        GymWorkout("w$at", "Workout", null, at, if (ended) at + 1 else null, listOf(ExerciseLog(bench, sets.toList())))

    private val history = listOf(
        workout(1, GymSet(60.0, 8, true), GymSet(60.0, 6, true)),
        workout(2, GymSet(65.0, 5, true), GymSet(70.0, 1, true)),
        workout(3, GymSet(62.5, 8, true), GymSet(80.0, 3, false)),
    )

    @Test fun epley() = assertEquals(76.0, e1rm(60.0, 8), 0.001)
    @Test fun singleRepIsItself() = assertEquals(100.0, e1rm(100.0, 1), 0.0)

    @Test fun volumeCountsDoneSetsOnly() = assertEquals(500.0, volume(history[2]), 0.001)

    @Test fun previousIsLatestFinishedWithDoneSets() {
        val unfinished = workout(4, GymSet(90.0, 5, true), ended = false)
        assertEquals(listOf(GymSet(62.5, 8, true)), previousSets(history + unfinished, bench))
    }

    @Test fun noPreviousForUnusedExercise() = assertEquals(emptyList<GymSet>(), previousSets(history, "b-squat"))

    @Test fun recordsAcrossWorkouts() {
        val r = records(history, bench)!!
        assertEquals(70.0, r.heaviestKg, 0.0)
        assertEquals(79.17, r.bestE1rm, 0.01)
        assertEquals(500.0, r.bestSetVolume, 0.0)
        assertEquals(listOf(1L, 2L, 3L), r.series.map { it.first })
        assertEquals(76.0, r.series[0].second, 0.01)
    }

    @Test fun noRecordsWithoutHistory() = assertNull(records(emptyList(), bench))

    @Test fun kgFormatting() {
        assertEquals("60", kgText(60.0))
        assertEquals("62.5", kgText(62.5))
    }

    @Test fun builtInIdsAreUniqueAndStable() {
        assertEquals(BUILT_IN_EXERCISES.size, BUILT_IN_EXERCISES.map { it.id }.toSet().size)
        assertEquals("b-bench-press", BUILT_IN_EXERCISES.first { it.name == "Bench Press" }.id)
        assertEquals("b-push-up", BUILT_IN_EXERCISES.first { it.name == "Push-up" }.id)
    }
}
