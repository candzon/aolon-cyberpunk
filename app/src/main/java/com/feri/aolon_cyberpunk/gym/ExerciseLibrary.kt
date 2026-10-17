package com.feri.aolon_cyberpunk.gym

val MUSCLES = listOf("Chest", "Back", "Shoulders", "Arms", "Legs", "Core")

/** Built-ins live in code with stable ids, so workouts that reference them never need a sync. */
val BUILT_IN_EXERCISES: List<Exercise> = mapOf(
    "Chest" to listOf(
        "Bench Press", "Incline Bench Press", "Decline Bench Press", "Dumbbell Bench Press", "Incline Dumbbell Press",
        "Dumbbell Fly", "Cable Crossover", "Chest Press Machine", "Pec Deck", "Push-up", "Chest Dip",
    ),
    "Back" to listOf(
        "Deadlift", "Pull-up", "Chin-up", "Lat Pulldown", "Barbell Row", "Dumbbell Row", "Seated Cable Row",
        "T-Bar Row", "Face Pull", "Back Extension",
    ),
    "Shoulders" to listOf(
        "Overhead Press", "Dumbbell Shoulder Press", "Arnold Press", "Lateral Raise", "Front Raise",
        "Rear Delt Fly", "Upright Row", "Shrug",
    ),
    "Arms" to listOf(
        "Barbell Curl", "Dumbbell Curl", "Hammer Curl", "Preacher Curl", "Cable Curl", "Triceps Pushdown",
        "Skull Crusher", "Overhead Triceps Extension", "Close-Grip Bench Press", "Triceps Dip",
    ),
    "Legs" to listOf(
        "Squat", "Front Squat", "Leg Press", "Romanian Deadlift", "Lunge", "Bulgarian Split Squat", "Leg Extension",
        "Leg Curl", "Hip Thrust", "Standing Calf Raise", "Seated Calf Raise", "Goblet Squat",
    ),
    "Core" to listOf(
        "Plank", "Crunch", "Hanging Leg Raise", "Cable Crunch", "Russian Twist", "Ab Wheel Rollout", "Side Plank",
    ),
).flatMap { (muscle, names) ->
    names.map { Exercise("b-" + it.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), it, muscle) }
}
