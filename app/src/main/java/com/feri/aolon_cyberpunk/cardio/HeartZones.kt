package com.feri.aolon_cyberpunk.cardio

/** One of the five Cleveland Clinic zones, as a share of max heart rate in whole percent. */
data class Zone(val number: Int, val name: String, val lowPct: Int, val highPct: Int, val purpose: String, val feel: String)

val ZONES = listOf(
    Zone(1, "Recovery", 50, 60, "Warm-up, cool-down, or active recovery after a hard session.", "Very easy. You can talk without getting out of breath."),
    Zone(2, "Endurance / fat burn", 60, 70, "Builds your aerobic base and trains the body to burn fat efficiently.", "You can still hold a full conversation or sing lightly."),
    Zone(3, "Aerobic / stamina", 70, 80, "Improves lung capacity, blood flow, and cardiovascular stamina.", "Harder. Singing or long sentences get difficult."),
    Zone(4, "Anaerobic / threshold", 80, 90, "Builds speed, peak performance, and tolerance to lactic acid.", "Hard. Breathing heavily, only a few words at a time."),
    Zone(5, "Maximum / VO2 max", 90, 100, "Extreme efforts such as all-out sprints, for power and VO2 max.", "Exhausting. You can only hold it briefly."),
)

/** The common estimate the user chose; real max can differ by 10 or more bpm. */
fun maxHr(age: Int) = 220 - age

/** 0 means below zone 1 (under 50%); anything at or above 90% is zone 5. Integer maths avoids 0.7 * 10 rounding. */
fun zoneOf(bpm: Int, mhr: Int): Int {
    if (mhr <= 0) return 0
    val pct = bpm * 100 / mhr
    return when {
        pct < 50 -> 0
        pct >= 90 -> 5
        else -> (pct - 40) / 10
    }
}

/** Whole-bpm range for a zone: from the first bpm that reaches lowPct up to the last below highPct. */
fun zoneBpm(zone: Zone, mhr: Int): IntRange {
    val low = (zone.lowPct * mhr + 99) / 100
    val high = if (zone.number == 5) mhr else (zone.highPct * mhr + 99) / 100 - 1
    return low..high
}

/**
 * Seconds spent in each zone (index 0 = below zone 1) from [secondsFromStart, bpm] samples.
 * Each sample counts until the next one, capped at 30 s so a dropped watch link isn't credited to one zone.
 */
fun timeInZones(hr: List<Pair<Int, Int>>, mhr: Int): IntArray {
    val out = IntArray(6)
    hr.forEachIndexed { i, (t, bpm) ->
        val span = hr.getOrNull(i + 1)?.let { (it.first - t).coerceIn(0, 30) } ?: 5
        out[zoneOf(bpm, mhr)] += span
    }
    return out
}
