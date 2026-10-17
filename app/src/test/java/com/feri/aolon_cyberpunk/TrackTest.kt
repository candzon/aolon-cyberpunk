package com.feri.aolon_cyberpunk

import com.feri.aolon_cyberpunk.cardio.AutoPause
import com.feri.aolon_cyberpunk.cardio.Polyline
import com.feri.aolon_cyberpunk.cardio.Sample
import com.feri.aolon_cyberpunk.cardio.TrackPoint
import com.feri.aolon_cyberpunk.cardio.accept
import com.feri.aolon_cyberpunk.cardio.haversineM
import com.feri.aolon_cyberpunk.cardio.limits
import com.feri.aolon_cyberpunk.cardio.paceText
import com.feri.aolon_cyberpunk.cardio.splits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    private fun p(t: Long, lat: Double, lon: Double, acc: Float = 5f) = TrackPoint(t, lat, lon, acc)

    // One degree of latitude is about 111.19 km on a 6371 km sphere.
    @Test fun haversineOneDegree() = assertEquals(111_195.0, haversineM(p(0, 0.0, 0.0), p(0, 1.0, 0.0)), 1.0)

    @Test fun rejectsVagueFixesAndJumps() {
        val a = p(0, -6.2, 106.8)
        assertFalse(accept(a, p(1_000, -6.2, 106.8001, acc = 40f), "Run"))
        assertFalse(accept(a, p(1_000, -6.201, 106.8), "Run")) // ~111 m in 1 s
        assertTrue(accept(a, p(1_000, -6.20003, 106.8), "Run")) // ~3.3 m in 1 s
    }

    @Test fun autoPauseAfterFiveSecondsAndResume() {
        val pause = AutoPause(limits("Run"))
        assertFalse(pause.update(0, 3.0))
        assertFalse(pause.update(1_000, 0.1))
        assertFalse(pause.update(5_000, 0.1))
        assertTrue(pause.update(6_000, 0.1))
        assertTrue(pause.paused)
        assertEquals(1_000, pause.pausedAt)
        assertTrue(pause.update(7_000, 3.0))
        assertFalse(pause.paused)
    }

    @Test fun splitsInterpolateKmMarks() {
        // Steady 4 m/s for 3.2 km: each km takes 250 s.
        val samples = (0..800).map { Sample(it * 1.0, it * 4.0) }
        assertEquals(listOf(250, 250, 250), splits(samples))
    }

    @Test fun splitsFollowPaceChanges() {
        val samples = listOf(Sample(0.0, 0.0), Sample(300.0, 1000.0), Sample(540.0, 2000.0), Sample(600.0, 2100.0))
        assertEquals(listOf(300, 240), splits(samples))
    }

    @Test fun pace() {
        assertEquals("5:00", paceText(300.0, 1000.0))
        assertEquals("--", paceText(10.0, 0.0))
    }

    @Test fun polylineMatchesGoogleReference() {
        val route = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        assertEquals(encoded, Polyline.encode(route))
        Polyline.decode(encoded).zip(route).forEach { (d, r) ->
            assertEquals(r.first, d.first, 1e-6)
            assertEquals(r.second, d.second, 1e-6)
        }
    }
}
