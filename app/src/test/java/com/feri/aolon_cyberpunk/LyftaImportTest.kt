package com.feri.aolon_cyberpunk

import com.feri.aolon_cyberpunk.gym.GymLog
import com.feri.aolon_cyberpunk.gym.RecordStore
import com.feri.aolon_cyberpunk.lyfta.LyftaClient
import com.feri.aolon_cyberpunk.lyfta.LyftaExercise
import com.feri.aolon_cyberpunk.lyfta.LyftaSet
import com.feri.aolon_cyberpunk.lyfta.LyftaWorkout
import com.feri.aolon_cyberpunk.lyfta.RawSet
import com.feri.aolon_cyberpunk.lyfta.liftedSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyftaImportTest {
    private class FakeStore : RecordStore {
        val writes = mutableMapOf<String, Any?>()
        private var key = 0
        override fun write(path: String, value: Any?) { writes[path] = value }
        override fun newKey(list: String) = "k${key++}"
        override fun <T> deleteWithUndo(list: MutableList<T>, item: T, path: String, message: String) { list.remove(item) }
    }

    private val workout = LyftaWorkout(
        42, "Push", 1_700_000_000_000,
        listOf(
            LyftaExercise("bench press", listOf(LyftaSet(60.0, 8), LyftaSet(62.5, 6))),
            LyftaExercise("Cable Fly (Low)", listOf(LyftaSet(15.0, 12))),
            LyftaExercise("Warm-up", emptyList()),
        ),
    )

    @Test fun importsOnceAndMapsNames() {
        val store = FakeStore()
        val gym = GymLog(store)
        assertEquals(1, gym.importLyfta(listOf(workout)))
        assertEquals(0, gym.importLyfta(listOf(workout)))

        val saved = gym.history.single()
        assertEquals("lyfta-42", saved.id)
        assertEquals(listOf("b-bench-press", "k0"), saved.entries.map { it.exerciseId })
        assertEquals(3, saved.doneSets)
        assertEquals("Cable Fly (Low)", gym.customExercises.single().name)
        assertTrue("gym/lyfta-42" in store.writes)
    }

    @Test fun reusesCustomExerciseAcrossImports() {
        val gym = GymLog(FakeStore())
        gym.importLyfta(listOf(workout))
        gym.importLyfta(listOf(workout.copy(id = 43)))
        assertEquals(1, gym.customExercises.size)
        assertEquals(2, gym.history.size)
    }

    @Test fun repeatCopiesSetsUnticked() {
        val gym = GymLog(FakeStore())
        gym.importLyfta(listOf(workout))
        val past = gym.history.single()
        gym.repeat(past)
        val again = gym.active!!
        assertTrue(again.id != past.id && again.endedAt == null)
        assertEquals(past.entries.map { it.exerciseId }, again.entries.map { it.exerciseId })
        assertEquals(past.entries.flatMap { e -> e.sets.map { it.kg to it.reps } }, again.entries.flatMap { e -> e.sets.map { it.kg to it.reps } })
        assertEquals(0, again.doneSets)
    }

    @Test fun saveAsRoutineOnce() {
        val gym = GymLog(FakeStore())
        gym.importLyfta(listOf(workout))
        val past = gym.history.single()
        assertEquals(listOf("b-bench-press", "k0"), gym.saveAsRoutine(past)!!.exercises)
        assertEquals(null, gym.saveAsRoutine(past))
        assertEquals(1, gym.routines.size)
    }

    @Test fun parsesUtcTimes() {
        assertEquals(1_700_000_000_000, LyftaClient.parseTime("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000_000, LyftaClient.parseTime("2023-11-14T22:13:20.000Z"))
        assertEquals(1_700_000_000_000, LyftaClient.parseTime("2023-11-14 22:13:20"))
        assertEquals(1_699_920_000_000, LyftaClient.parseTime("2023-11-14"))
    }

    @Test fun unflaggedWorkoutKeepsSetsWithReps() {
        // Matches what Lyfta returned on device: every set completed=false in a finished workout.
        val sets = liftedSets(listOf(listOf(RawSet(60.0, 8, false), RawSet(0.0, 0, false)), listOf(RawSet(20.0, 12, false))))
        assertEquals(listOf(listOf(LyftaSet(60.0, 8)), listOf(LyftaSet(20.0, 12))), sets)
    }

    @Test fun flaggedWorkoutKeepsOnlyCompleted() {
        val sets = liftedSets(listOf(listOf(RawSet(60.0, 8, true), RawSet(70.0, 5, false))))
        assertEquals(listOf(listOf(LyftaSet(60.0, 8))), sets)
    }
}
