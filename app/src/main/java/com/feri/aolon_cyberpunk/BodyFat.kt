package com.feri.aolon_cyberpunk

import kotlin.math.log10

/** Body fat formulas as published by calculator.net/body-fat-calculator.html, metric units. */
object BodyFat {
    class Result(
        val navy: Double,
        val bmiMethod: Double,
        val category: String,
        val fatMassKg: Double,
        val leanMassKg: Double,
        val ideal: Double,
        val toLoseKg: Double,
    )

    /** Returns null when the tape measurements cannot produce a valid log (waist not larger than neck). */
    fun calculate(male: Boolean, age: Int, heightCm: Double, weightKg: Double, neckCm: Double, waistCm: Double, hipCm: Double?): Result? {
        val girth = if (male) waistCm - neckCm else waistCm + (hipCm ?: return null) - neckCm
        if (girth <= 0) return null
        val navy = if (male) 495 / (1.0324 - 0.19077 * log10(girth) + 0.15456 * log10(heightCm)) - 450
        else 495 / (1.29579 - 0.35004 * log10(girth) + 0.22100 * log10(heightCm)) - 450
        val bmi = weightKg / (heightCm / 100) / (heightCm / 100)
        val bmiMethod = when {
            age >= 18 -> 1.20 * bmi + 0.23 * age - if (male) 16.2 else 5.4
            male -> 1.51 * bmi - 0.70 * age - 2.2
            else -> 1.51 * bmi - 0.70 * age + 1.4
        }
        val fat = weightKg * navy / 100
        val ideal = ideal(male, age)
        return Result(navy, bmiMethod, category(male, navy), fat, weightKg - fat, ideal, (weightKg * (navy - ideal) / 100).coerceAtLeast(0.0))
    }

    // American Council on Exercise ranges; gaps between published bands go to the higher band.
    fun category(male: Boolean, percent: Double): String {
        val bands = if (male) listOf(6.0, 14.0, 18.0, 25.0) else listOf(14.0, 21.0, 25.0, 32.0)
        val names = listOf("Essential fat", "Athletes", "Fitness", "Average", "Obese")
        return names[bands.indexOfFirst { percent < it }.takeIf { it >= 0 } ?: 4]
    }

    private val AGES = listOf(20, 25, 30, 35, 40, 45, 50, 55)
    private val MEN = listOf(8.5, 10.5, 12.7, 13.7, 15.3, 16.4, 18.9, 20.9)
    private val WOMEN = listOf(17.7, 18.4, 19.3, 21.5, 22.2, 22.9, 25.2, 26.3)

    /** Jackson & Pollock ideal body fat, linear between the published ages and clamped outside 20 to 55. */
    fun ideal(male: Boolean, age: Int): Double {
        val table = if (male) MEN else WOMEN
        val a = age.coerceIn(AGES.first(), AGES.last())
        val i = (AGES.indexOfLast { it <= a }).coerceAtMost(AGES.size - 2)
        return table[i] + (table[i + 1] - table[i]) * (a - AGES[i]) / (AGES[i + 1] - AGES[i])
    }

    /** Mifflin-St Jeor resting energy, kcal per day. */
    fun restingKcal(male: Boolean, age: Int, heightCm: Double, weightKg: Double) =
        10 * weightKg + 6.25 * heightCm - 5 * age + if (male) 5 else -161
}
