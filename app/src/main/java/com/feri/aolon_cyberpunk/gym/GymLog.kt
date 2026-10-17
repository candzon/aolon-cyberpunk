package com.feri.aolon_cyberpunk.gym

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.feri.aolon_cyberpunk.lyfta.LyftaWorkout
import com.google.firebase.database.DataSnapshot
import java.time.LocalDate

/** Persistence the ViewModel provides, so every Firebase write and soft delete goes through one place. */
interface RecordStore {
    fun write(path: String, value: Any?)
    fun newKey(list: String): String
    fun <T> deleteWithUndo(list: MutableList<T>, item: T, path: String, message: String)
}

/**
 * Gym log state: custom exercises, routines, finished workouts and the one in progress.
 * The active workout is rewritten to "gymActive" on every change so a killed app resumes mid-set.
 */
class GymLog(private val store: RecordStore) {
    val customExercises = mutableStateListOf<Exercise>()
    val routines = mutableStateListOf<Routine>()
    /** Finished workouts, oldest first. */
    val history = mutableStateListOf<GymWorkout>()
    var active by mutableStateOf<GymWorkout?>(null)
        private set
    /** Wall-clock end of the current rest; 0 when no rest is running. Not persisted on purpose. */
    var restEndsAt by mutableLongStateOf(0L)
    /** Every workout key in Firebase, soft-deleted ones included, so an import never revives a deleted workout. */
    private val knownIds = mutableSetOf<String>()

    val exercises: List<Exercise> get() = BUILT_IN_EXERCISES + customExercises
    fun exercise(id: String) = exercises.firstOrNull { it.id == id } ?: Exercise(id, "Unknown exercise", "Other")
    fun workoutsOn(date: LocalDate) = history.filter { it.date == date }

    fun load(data: DataSnapshot) {
        customExercises.clear()
        routines.clear()
        history.clear()
        customExercises += data.child("exercises").live().mapNotNull { e ->
            Exercise(e.key!!, e.str("name") ?: return@mapNotNull null, e.str("muscle") ?: "Other", custom = true)
        }
        routines += data.child("routines").live().mapNotNull { r ->
            Routine(r.key!!, r.str("name") ?: return@mapNotNull null, r.child("exercises").children.mapNotNull { it.getValue(String::class.java) })
        }
        history += data.child("gym").live().mapNotNull { parseWorkout(it) }.sortedBy { it.startedAt }
        knownIds.clear()
        knownIds += data.child("gym").children.mapNotNull { it.key }
        active = data.child("gymActive").takeIf { it.exists() }?.let { parseWorkout(it, data.child("gymActive").str("id")) }
        restEndsAt = 0L
    }

    fun clear() {
        knownIds.clear()
        customExercises.clear()
        routines.clear()
        history.clear()
        active = null
        restEndsAt = 0L
    }

    fun startEmpty() = begin(GymWorkout(store.newKey("gym"), "Workout", null, System.currentTimeMillis(), null, emptyList()))

    fun start(routine: Routine) = begin(
        GymWorkout(store.newKey("gym"), routine.name, routine.id, System.currentTimeMillis(), null, routine.exercises.map(::freshLog)),
    )

    /** Starts a new workout with a past workout's exercises and kg × reps, every set unticked. */
    fun repeat(past: GymWorkout) = begin(
        GymWorkout(store.newKey("gym"), past.name, past.routineId, System.currentTimeMillis(), null,
            past.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = false) }) }),
    )

    /** Saves a past workout's exercise order as a routine; null when a routine with that name exists. */
    fun saveAsRoutine(past: GymWorkout): Routine? {
        if (routines.any { it.name.equals(past.name, ignoreCase = true) }) return null
        saveRoutine(null, past.name, past.entries.map { it.exerciseId }.distinct())
        return routines.last()
    }

    fun addExercise(id: String) = edit { it.copy(entries = it.entries + freshLog(id)) }
    fun removeExercise(index: Int) = edit { it.copy(entries = it.entries.filterIndexed { i, _ -> i != index }) }

    fun addSet(exercise: Int) = editSets(exercise) { sets ->
        sets + (sets.lastOrNull()?.copy(done = false) ?: GymSet(0.0, 0))
    }

    fun updateSet(exercise: Int, set: Int, value: GymSet) = editSets(exercise) { sets -> sets.mapIndexed { i, s -> if (i == set) value else s } }
    fun removeSet(exercise: Int, set: Int) = editSets(exercise) { sets -> sets.filterIndexed { i, _ -> i != set } }

    /** Ticking a set starts the rest countdown; unticking leaves the timer alone. */
    fun toggleDone(exercise: Int, set: Int, restSeconds: Int) {
        val current = active?.entries?.getOrNull(exercise)?.sets?.getOrNull(set) ?: return
        updateSet(exercise, set, current.copy(done = !current.done))
        if (!current.done) restEndsAt = System.currentTimeMillis() + restSeconds * 1000L
    }

    /** Saves completed sets only; returns null when nothing was ticked so the UI can say so. */
    fun finish(): GymWorkout? {
        val w = active ?: return null
        val entries = w.entries.map { e -> e.copy(sets = e.sets.filter { it.done && it.reps > 0 }) }.filter { it.sets.isNotEmpty() }
        if (entries.isEmpty()) return null
        val done = w.copy(endedAt = System.currentTimeMillis(), entries = entries)
        history += done
        knownIds += done.id
        store.write("gym/${done.id}", toMap(done, withDone = false))
        store.write("gymActive", null)
        active = null
        restEndsAt = 0L
        return done
    }

    /**
     * Adds Lyfta workouts not seen before as finished workouts. Lyfta sends no duration, so start equals end.
     * Exercise names map to an existing exercise by name, otherwise a custom one is created once.
     */
    fun importLyfta(workouts: List<LyftaWorkout>): Int {
        val added = workouts.filter { "lyfta-${it.id}" !in knownIds }.mapNotNull { w ->
            val entries = w.exercises.filter { it.sets.isNotEmpty() }.map { e ->
                val id = exercises.firstOrNull { it.name.equals(e.name.trim(), ignoreCase = true) }?.id
                    ?: addCustomExercise(e.name.trim(), "Other").id
                ExerciseLog(id, e.sets.map { GymSet(it.kg, it.reps, done = true) })
            }
            if (entries.isEmpty()) null
            else GymWorkout("lyfta-${w.id}", w.title, null, w.performedAt, w.performedAt, entries)
        }
        added.forEach {
            knownIds += it.id
            store.write("gym/${it.id}", toMap(it, withDone = false) + ("source" to "lyfta"))
        }
        if (added.isNotEmpty()) {
            val sorted = (history + added).sortedBy { it.startedAt }
            history.clear()
            history += sorted
        }
        return added.size
    }

    fun discard() {
        store.write("gymActive", null)
        active = null
        restEndsAt = 0L
    }

    fun deleteWorkout(w: GymWorkout) = store.deleteWithUndo(history, w, "gym/${w.id}", "${w.name} deleted.")

    fun saveRoutine(existing: Routine?, name: String, exercises: List<String>) {
        val routine = Routine(existing?.id ?: store.newKey("routines"), name, exercises)
        val index = routines.indexOfFirst { it.id == routine.id }
        if (index >= 0) routines[index] = routine else routines += routine
        store.write("routines/${routine.id}", mapOf("name" to name, "exercises" to exercises))
    }

    fun deleteRoutine(r: Routine) = store.deleteWithUndo(routines, r, "routines/${r.id}", "${r.name} deleted.")

    fun addCustomExercise(name: String, muscle: String): Exercise {
        val e = Exercise(store.newKey("exercises"), name, muscle, custom = true)
        customExercises += e
        store.write("exercises/${e.id}", mapOf("name" to name, "muscle" to muscle))
        return e
    }

    private fun begin(w: GymWorkout) {
        active = w
        restEndsAt = 0L
        persistActive()
    }

    // New exercises copy last session's sets as a starting point, the way Lyfta prefills.
    private fun freshLog(id: String) =
        ExerciseLog(id, previousSets(history, id).map { it.copy(done = false) }.ifEmpty { listOf(GymSet(0.0, 0)) })

    private fun editSets(exercise: Int, change: (List<GymSet>) -> List<GymSet>) = edit { w ->
        w.copy(entries = w.entries.mapIndexed { i, e -> if (i == exercise) e.copy(sets = change(e.sets)) else e })
    }

    private fun edit(change: (GymWorkout) -> GymWorkout) {
        active = change(active ?: return)
        persistActive()
    }

    private fun persistActive() {
        val w = active ?: return
        store.write("gymActive", toMap(w, withDone = true) + ("id" to w.id))
    }

    private fun toMap(w: GymWorkout, withDone: Boolean): Map<String, Any?> = mapOf(
        "name" to w.name,
        "routineId" to w.routineId,
        "startedAt" to w.startedAt,
        "endedAt" to w.endedAt,
        "entries" to w.entries.map { e ->
            mapOf("exerciseId" to e.exerciseId, "sets" to e.sets.map { s ->
                if (withDone) mapOf("kg" to s.kg, "reps" to s.reps, "done" to s.done) else mapOf("kg" to s.kg, "reps" to s.reps)
            })
        },
    )

    // Finished sets are stored without "done", so a missing flag means completed.
    private fun parseWorkout(d: DataSnapshot, id: String? = d.key): GymWorkout? = runCatching {
        GymWorkout(
            id = id!!,
            name = d.str("name") ?: "Workout",
            routineId = d.str("routineId"),
            startedAt = d.child("startedAt").getValue(Long::class.java)!!,
            endedAt = d.child("endedAt").getValue(Long::class.java),
            entries = d.child("entries").children.map { e ->
                ExerciseLog(e.str("exerciseId")!!, e.child("sets").children.map { s ->
                    GymSet(
                        kg = (s.child("kg").value as? Number)?.toDouble() ?: 0.0,
                        reps = (s.child("reps").value as? Number)?.toInt() ?: 0,
                        done = s.child("done").getValue(Boolean::class.java) ?: true,
                    )
                })
            },
        )
    }.getOrNull()

    private fun DataSnapshot.live() = children.filterNot { it.hasChild("deletedAt") }
    private fun DataSnapshot.str(key: String) = child(key).getValue(String::class.java)
}
