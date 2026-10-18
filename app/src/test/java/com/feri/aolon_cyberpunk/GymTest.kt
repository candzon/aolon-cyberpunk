package com.feri.aolon_cyberpunk

import com.feri.aolon_cyberpunk.gym.BUILT_IN_EXERCISES
import com.feri.aolon_cyberpunk.gym.ExerciseLog
import com.feri.aolon_cyberpunk.gym.GymSet
import com.feri.aolon_cyberpunk.gym.GymWorkout
import com.feri.aolon_cyberpunk.gym.SetType
import com.feri.aolon_cyberpunk.gym.e1rm
import com.feri.aolon_cyberpunk.gym.kgText
import com.feri.aolon_cyberpunk.gym.newRecords
import com.feri.aolon_cyberpunk.gym.previousSets
import com.feri.aolon_cyberpunk.gym.records
import com.feri.aolon_cyberpunk.gym.recoveredPercent
import com.feri.aolon_cyberpunk.gym.recovery
import com.feri.aolon_cyberpunk.gym.setWeight
import com.feri.aolon_cyberpunk.gym.volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

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

    @Test fun warmUpsSkipVolumeAndRecords() {
        val w = workout(5, GymSet(100.0, 5, true, SetType.WarmUp), GymSet(50.0, 10, true))
        assertEquals(500.0, volume(w), 0.0)
        assertEquals(50.0, records(listOf(w), bench)!!.heaviestKg, 0.0)
    }

    @Test fun setTypeCyclesBackToNormal() = assertEquals(SetType.Normal, SetType.Failure.next())

    @Test fun newRecordBeatsEarlierSessions() {
        assertEquals(listOf(bench), newRecords(history, workout(4, GymSet(75.0, 5, true))))
        assertEquals(emptyList<String>(), newRecords(history, workout(4, GymSet(60.0, 5, true))))
        assertEquals(emptyList<String>(), newRecords(emptyList(), history[0]))
    }

    @Test fun recoveryFollowsLyftaModel() {
        val today = LocalDate.of(2026, 10, 18)
        val at = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sets = List(10) { GymSet(60.0, 8, true) }
        // 10 sets today is fatigue 10 of 20, so half recovered; every other group is untouched.
        val r = recovery(listOf(workout(at, *sets.toTypedArray())), { "Chest" }, today)
        assertEquals(50, r["Chest"])
        assertEquals(100, r["Legs"])
        val yesterday = recovery(listOf(workout(at, *sets.toTypedArray())), { "Chest" }, today.plusDays(1))
        assertEquals(65, yesterday["Chest"])
        assertEquals(100, recovery(listOf(workout(at, *sets.toTypedArray())), { "Chest" }, today.plusDays(8))["Chest"])
    }

    @Test fun recoveryWeightsSetTypes() {
        assertEquals(0.0, setWeight(GymSet(40.0, 10, true, SetType.WarmUp), 0), 0.0)
        assertEquals(0.35, setWeight(GymSet(100.0, 2, true, SetType.Failure), 0), 0.0)
        assertEquals(0.75, setWeight(GymSet(80.0, 6, true, SetType.Failure), 0), 0.0)
        assertEquals(0.25, setWeight(GymSet(30.0, 8, true, SetType.Drop), 5), 0.0)
        assertEquals(0, recoveredPercent(30.0))
        assertEquals(0, recoveredPercent(25.0))
    }

    @Test fun builtInIdsAreUniqueAndStable() {
        assertEquals(BUILT_IN_EXERCISES.size, BUILT_IN_EXERCISES.map { it.id }.toSet().size)
        assertEquals("b-bench-press", BUILT_IN_EXERCISES.first { it.name == "Bench Press" }.id)
        assertEquals("b-push-up", BUILT_IN_EXERCISES.first { it.name == "Push-up" }.id)
    }
}
