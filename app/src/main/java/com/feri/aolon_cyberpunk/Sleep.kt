package com.feri.aolon_cyberpunk

import java.time.LocalDate

/** Stage codes as the watch sends them in each segment's third byte. */
enum class SleepStage(val code: Int, val label: String) {
    Deep(1, "Deep"), Light(2, "Light"), Awake(3, "Awake"), Rem(4, "REM");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** One stretch of a single stage; [start] is minutes after midnight and may wrap past it. */
data class SleepSegment(val start: Int, val minutes: Int, val stage: SleepStage)

/** A night is filed under the morning it ended, as the watch does. */
data class SleepNight(val date: LocalDate, val segments: List<SleepSegment>) {
    fun minutesIn(stage: SleepStage) = segments.filter { it.stage == stage }.sumOf { it.minutes }
    val asleep get() = segments.filter { it.stage != SleepStage.Awake }.sumOf { it.minutes }
    val wakeUps get() = segments.count { it.stage == SleepStage.Awake }
    val bedtime get() = segments.firstOrNull()?.start
    val wake get() = segments.lastOrNull()?.let { (it.start + it.minutes) % 1440 }
}

object Sleep {
    /**
     * Segment records from the 32 packets, six bytes each: [HH][MM][stage][unused][minutes u16].
     * Out-of-range times and unknown stages are dropped, as the SDK's newer parser does.
     */
    fun segments(data: ByteArray): List<SleepSegment> {
        val u = IntArray(data.size) { data[it].toInt() and 0xff }
        return (0 until u.size / 6).mapNotNull { i ->
            val o = i * 6
            val stage = SleepStage.of(u[o + 2]) ?: return@mapNotNull null
            if (u[o] > 23 || u[o + 1] > 59) return@mapNotNull null
            SleepSegment(u[o] * 60 + u[o + 1], (u[o + 4] shl 8) or u[o + 5], stage)
        }
    }

    fun encode(segments: List<SleepSegment>) = segments.joinToString(" ") { "${it.start},${it.minutes},${it.stage.code}" }

    fun decode(text: String) = text.split(' ').mapNotNull { part ->
        val f = part.split(',').mapNotNull(String::toIntOrNull)
        if (f.size != 3) null else SleepStage.of(f[2])?.let { SleepSegment(f[0], f[1], it) }
    }
}

/**
 * Rebuilds nights from a 31 01 sync: a 31 01 header opens each night, 32 packets carry its
 * segments (a record can straddle two packets), and 31 02 closes the whole sync.
 */
class SleepAssembler {
    private var date: LocalDate? = null
    private var buffer = ByteArray(0)
    private val nights = mutableListOf<SleepNight>()

    fun start(day: LocalDate) {
        close()
        date = day
    }

    fun data(bytes: ByteArray) {
        if (date != null) buffer += bytes
    }

    /** Returns every night of the sync, then resets for the next one. */
    fun finish(): List<SleepNight> {
        close()
        return nights.toList().also { nights.clear() }
    }

    private fun close() {
        val day = date ?: return
        val segments = Sleep.segments(buffer)
        if (segments.isNotEmpty()) nights += SleepNight(day, segments)
        date = null
        buffer = ByteArray(0)
    }
}
