package com.feri.aolon_cyberpunk

import kotlin.math.roundToInt

/** fatsecret's four activity levels, which are the Institute of Medicine's physical activity categories. */
enum class Activity(val label: String, val male: Double, val female: Double) {
    Sedentary("Sedentary", 1.0, 1.0),
    Low("Low active", 1.11, 1.12),
    Active("Active", 1.25, 1.27),
    High("Very active", 1.48, 1.45),
}

/** fatsecret's diet goals; its own tip puts 0.5 kg a week at 500 kcal a day. */
enum class WeightGoal(val label: String, val kcal: Int) {
    LoseHalf("Lose 0.5 kg/wk", -500),
    LoseQuarter("Lose 0.25 kg/wk", -250),
    Maintain("Maintain", 0),
    GainQuarter("Gain 0.25 kg/wk", 250),
    GainHalf("Gain 0.5 kg/wk", 500),
}

enum class Meal(val label: String) { Breakfast("Breakfast"), Lunch("Lunch"), Dinner("Dinner"), Snacks("Snacks/Other") }

data class Macros(val protein: Double, val carbs: Double, val fat: Double) {
    operator fun plus(o: Macros) = Macros(protein + o.protein, carbs + o.carbs, fat + o.fat)

    /** Percent of macro energy from protein, carbs and fat at 4, 4 and 9 kcal per gram; null when nothing was entered. */
    fun energySplit(): Triple<Int, Int, Int>? {
        val p = protein * 4
        val c = carbs * 4
        val f = fat * 9
        val total = p + c + f
        if (total <= 0) return null
        return Triple((p / total * 100).roundToInt(), (c / total * 100).roundToInt(), (f / total * 100).roundToInt())
    }

    companion object { val ZERO = Macros(0.0, 0.0, 0.0) }
}

object Diet {
    // Common floors for unsupervised diets, so a large deficit never sets an unsafe target.
    private const val MIN_MALE = 1500
    private const val MIN_FEMALE = 1200

    /**
     * Daily calorie target: Institute of Medicine adult estimated energy requirement for the activity level,
     * plus the goal's daily surplus or deficit. Null under 19, where the adult equation does not apply.
     */
    fun rdi(male: Boolean, age: Int, heightCm: Double, weightKg: Double, activity: Activity, goal: WeightGoal): Int? {
        if (age < 19) return null
        val m = heightCm / 100
        val eer = if (male) 662 - 9.53 * age + activity.male * (15.91 * weightKg + 539.6 * m)
        else 354 - 6.91 * age + activity.female * (9.36 * weightKg + 726 * m)
        return (eer + goal.kcal).roundToInt().coerceAtLeast(if (male) MIN_MALE else MIN_FEMALE)
    }
}
