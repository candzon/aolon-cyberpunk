package com.feri.aolon_cyberpunk

import com.feri.aolon_cyberpunk.cardio.CardioRecorder
import com.feri.aolon_cyberpunk.cardio.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardioRecorderTest {
    private val step = 3 / 111_195.0 // 3 m of latitude

    @Test fun indoorActivityKeepsHeartRateWithoutGpsMovement() {
        assertTrue("Indoor" in ACTIVITIES)
        val r = CardioRecorder
        r.start("Indoor", now = 0)
        r.onHeartRate(142, now = 10_000)

        assertFalse(r.usesGps)
        assertEquals(142, r.heartRate)
        assertEquals(10_000L, r.lastHrAt)
        assertEquals(142, r.finish(now = 20_000)!!.avgHr)
    }

    @Test fun autoPauseExcludesStoppedTimeAndDrift() {
        val r = CardioRecorder
        r.start("Run", now = 0)
        var lat = 0.0
        for (s in 0..10) { r.onLocation(TrackPoint(s * 1000L, lat, 0.0, 5f)); lat += step } // run 30 m
        lat -= step
        for (s in 11..20) r.onLocation(TrackPoint(s * 1000L, lat, 0.0, 5f)) // stand still
        assertTrue(r.autoPaused)
        for (s in 21..25) { lat += step; r.onLocation(TrackPoint(s * 1000L, lat, 0.0, 5f)) } // run 15 m
        assertFalse(r.autoPaused)
        r.onHeartRate(150, now = 2_000)
        r.onHeartRate(160, now = 9_000)
        val done = r.finish(now = 25_000)!!
        assertEquals(45.0, done.distanceM, 1.0)
        assertEquals(15L, done.movingSec) // 0 to 11 s, then 21 to 25 s
        assertEquals(155, done.avgHr)
        assertEquals(160, done.maxHr)
        assertFalse(r.recording)
    }

    @Test fun manualPauseStopsClockAndIgnoresFixes() {
        val r = CardioRecorder
        r.start("Walk", now = 0)
        r.pause(now = 10_000)
        r.onLocation(TrackPoint(12_000, 1.0, 1.0, 5f))
        r.resume(now = 30_000)
        assertEquals(15L, r.finish(now = 35_000)!!.movingSec)
    }
}
