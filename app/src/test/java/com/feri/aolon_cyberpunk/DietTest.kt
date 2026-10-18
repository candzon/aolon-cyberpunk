package com.feri.aolon_cyberpunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DietTest {
    // IOM adult EER by hand: 662 - 9.53*30 + 1.25*(15.91*70 + 539.6*1.75) = 2949.
    @Test fun activeManMaintains() = assertEquals(2949, Diet.rdi(true, 30, 175.0, 70.0, Activity.Active, WeightGoal.Maintain))

    // 354 - 6.91*30 + 1.0*(9.36*60 + 726*1.65) = 1906.
    @Test fun sedentaryWoman() = assertEquals(1906, Diet.rdi(false, 30, 165.0, 60.0, Activity.Sedentary, WeightGoal.Maintain))

    @Test fun goalShiftsTarget() = assertEquals(2449, Diet.rdi(true, 30, 175.0, 70.0, Activity.Active, WeightGoal.LoseHalf))

    @Test fun neverBelowFloor() = assertEquals(1200, Diet.rdi(false, 80, 150.0, 40.0, Activity.Sedentary, WeightGoal.LoseHalf))

    @Test fun minorsGetNoTarget() = assertNull(Diet.rdi(true, 16, 175.0, 65.0, Activity.Active, WeightGoal.Maintain))

    @Test fun energySplitUsesAtwater() = assertEquals(Triple(25, 50, 25), Macros(25.0, 50.0, 100.0 / 9).energySplit())

    @Test fun noSplitWithoutMacros() = assertNull(Macros.ZERO.energySplit())
}
