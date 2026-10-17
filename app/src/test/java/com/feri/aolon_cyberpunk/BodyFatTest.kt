package com.feri.aolon_cyberpunk

import org.junit.Assert.assertEquals
import org.junit.Test

class BodyFatTest {
    // calculator.net default example: male, 25 y, 178 cm, 70 kg, neck 50 cm, waist 96 cm.
    private val example = BodyFat.calculate(true, 25, 178.0, 70.0, 50.0, 96.0, null)!!

    @Test fun navyMatchesCalculatorNet() = assertEquals(15.7, example.navy, 0.05)
    @Test fun bmiMethodMatchesCalculatorNet() = assertEquals(16.1, example.bmiMethod, 0.05)
    @Test fun massesMatchCalculatorNet() {
        assertEquals(11.0, example.fatMassKg, 0.05)
        assertEquals(59.0, example.leanMassKg, 0.05)
    }
    @Test fun idealAndToLoseMatchCalculatorNet() {
        assertEquals(10.5, example.ideal, 0.001)
        assertEquals(3.6, example.toLoseKg, 0.05)
        assertEquals("Fitness", example.category)
    }
    @Test fun femaleNeedsHip() = assertEquals(null, BodyFat.calculate(false, 30, 165.0, 60.0, 33.0, 75.0, null))
    @Test fun idealInterpolates() = assertEquals(11.38, BodyFat.ideal(true, 27), 0.01)
}
