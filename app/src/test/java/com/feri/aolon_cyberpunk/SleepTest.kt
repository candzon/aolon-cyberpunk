package com.feri.aolon_cyberpunk

import com.feri.aolon_cyberpunk.ble.Ute
import com.feri.aolon_cyberpunk.ble.WatchEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SleepTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // 23:10 light 50 min, 00:00 deep 90 min, 01:30 awake 5 min, 01:35 REM 300 min (0x012C).
    private val night = bytes(
        0x32, 23, 10, 2, 0, 0, 50, 0, 0, 1, 0, 0, 90, 1, 30, 3,
        0, 0, 5, 1, 35, 4, 0, 1, 0x2C,
    )

    @Test fun commandMatchesSdk() = assertArrayEquals(bytes(0x31, 0x01), Ute.syncSleep(cywee = false))

    @Test fun cyweeCommand() = assertArrayEquals(bytes(0xEC, 0x01), Ute.syncSleep(cywee = true))

    @Test fun cyweeFraming() {
        assertEquals(WatchEvent.SleepNight(LocalDate.of(2026, 10, 18)), Ute.parse(bytes(0xEC, 0x01, 0x07, 0xEA, 10, 18, 1, 0)))
        assertArrayEquals(bytes(23, 10, 2, 0, 0, 50), (Ute.parse(bytes(0xEC, 0x02, 23, 10, 2, 0, 0, 50)) as WatchEvent.SleepData).bytes)
        assertEquals(WatchEvent.SleepSyncDone, Ute.parse(bytes(0xEC, 0x03)))
    }

    @Test fun cyweeBitFromListEight() {
        assertEquals(true, Ute.moreFeatures(ByteArray(20).also { it[19] = 0x20 })?.cyweeSleep)
        assertEquals(false, Ute.moreFeatures(ByteArray(20))?.cyweeSleep)
    }

    // 255 marks the gap between a night and a nap; it is not a stage.
    @Test fun napSeparatorSkipped() = assertEquals(1, Sleep.segments(bytes(23, 0, 1, 0, 0, 30, 2, 0, 255, 0, 0, 0)).size)

    @Test fun headerOpensNight() =
        assertEquals(WatchEvent.SleepNight(LocalDate.of(2026, 10, 18)), Ute.parse(bytes(0x31, 0x01, 0x07, 0xEA, 10, 18, 4, 0)))

    @Test fun endMarker() = assertEquals(WatchEvent.SleepSyncDone, Ute.parse(bytes(0x31, 0x02)))

    @Test fun alarmReplyIgnored() = assertNull(Ute.parse(bytes(0x31, 0xAC, 0x01)))

    @Test fun recordsSplitAcrossPacketsJoin() {
        val a = SleepAssembler()
        a.start(LocalDate.of(2026, 10, 18))
        // Cut mid-record, as a 20-byte MTU would.
        (Ute.parse(night.copyOfRange(0, 9)) as WatchEvent.SleepData).let { a.data(it.bytes) }
        (Ute.parse(byteArrayOf(0x32) + night.copyOfRange(9, night.size)) as WatchEvent.SleepData).let { a.data(it.bytes) }
        val n = a.finish().single()
        assertEquals(440, n.asleep)
        assertEquals(90, n.minutesIn(SleepStage.Deep))
        assertEquals(1, n.wakeUps)
        assertEquals(23 * 60 + 10, n.bedtime)
        assertEquals(6 * 60 + 35, n.wake)
    }

    @Test fun eachHeaderStartsANewNight() {
        val a = SleepAssembler()
        a.start(LocalDate.of(2026, 10, 17))
        a.data(night.copyOfRange(1, 7))
        a.start(LocalDate.of(2026, 10, 18))
        a.data(night.copyOfRange(7, 13))
        assertEquals(listOf(50, 90), a.finish().map { it.asleep })
        assertTrue(a.finish().isEmpty())
    }

    @Test fun badRecordsDropped() = assertTrue(Sleep.segments(bytes(25, 0, 1, 0, 0, 10, 1, 0, 9, 0, 0, 10)).isEmpty())

    @Test fun storageRoundTrips() {
        val segments = Sleep.segments(night.copyOfRange(1, night.size))
        assertEquals(segments, Sleep.decode(Sleep.encode(segments)))
    }

    @Test fun featureBitFromListFour() {
        val bitmap = ByteArray(20).also { it[8] = 0x04 }
        assertEquals(true, Ute.features(bitmap)?.sleep)
        assertEquals(false, Ute.features(ByteArray(20))?.sleep)
    }
}
