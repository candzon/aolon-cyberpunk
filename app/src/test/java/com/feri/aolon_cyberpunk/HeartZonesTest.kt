package com.feri.aolon_cyberpunk

import com.feri.aolon_cyberpunk.cardio.ZONES
import com.feri.aolon_cyberpunk.cardio.maxHr
import com.feri.aolon_cyberpunk.cardio.timeInZones
import com.feri.aolon_cyberpunk.cardio.zoneBpm
import com.feri.aolon_cyberpunk.cardio.zoneOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartZonesTest {
    // The source's own example: age 30 gives 220 - 30 = 190 bpm.
    private val mhr = maxHr(30)

    @Test fun maxFromAge() = assertEquals(190, mhr)

    @Test fun zoneBoundaries() {
        assertEquals(0, zoneOf(94, mhr))  // 49%
        assertEquals(1, zoneOf(95, mhr))  // 50%
        assertEquals(1, zoneOf(113, mhr))
        assertEquals(2, zoneOf(114, mhr)) // 60%
        assertEquals(3, zoneOf(133, mhr)) // exactly 70%
        assertEquals(4, zoneOf(152, mhr)) // 80%
        assertEquals(5, zoneOf(171, mhr)) // 90%
        assertEquals(5, zoneOf(200, mhr)) // above max still zone 5
    }

    @Test fun bpmRangesTileWithoutGaps() {
        assertEquals(listOf(95..113, 114..132, 133..151, 152..170, 171..190), ZONES.map { zoneBpm(it, mhr) })
    }

    @Test fun timeInZonesUsesGapsAndCaps() {
        // 10 s in Z2, 5 s in Z3, then a 60 s watch dropout capped at 30 s in Z3, last sample counts 5 s in Z1.
        val hr = listOf(0 to 120, 5 to 125, 10 to 140, 15 to 140, 75 to 100)
        assertArrayEquals(intArrayOf(0, 5, 10, 35, 0, 0), timeInZones(hr, mhr))
    }
}
