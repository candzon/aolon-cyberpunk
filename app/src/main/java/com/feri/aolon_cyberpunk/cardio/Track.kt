package com.feri.aolon_cyberpunk.cardio

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackPoint(val t: Long, val lat: Double, val lon: Double, val accuracy: Float)

/** Per-activity speeds in m/s: below [pauseBelow] for 5 s auto-pauses, above [maxSpeed] is a GPS jump. */
data class Limits(val pauseBelow: Double, val maxSpeed: Double)

val GPS_ACTIVITIES = setOf("Walk", "Run", "Cycle")

fun limits(activity: String) = when (activity) {
    "Run" -> Limits(0.8, 8.0)
    "Cycle" -> Limits(1.5, 25.0)
    else -> Limits(0.4, 4.0)
}

private const val EARTH_M = 6_371_000.0
private const val MAX_ACCURACY_M = 25f

fun haversineM(a: TrackPoint, b: TrackPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_M * asin(sqrt(h))
}

/** Rejects vague fixes and teleports; the first good fix is always accepted. */
fun accept(prev: TrackPoint?, next: TrackPoint, activity: String): Boolean {
    if (next.accuracy > MAX_ACCURACY_M) return false
    if (prev == null) return true
    val seconds = (next.t - prev.t) / 1000.0
    if (seconds <= 0) return false
    return haversineM(prev, next) / seconds <= limits(activity).maxSpeed
}

/**
 * Pauses after 5 s below the activity's walking pace and resumes on the first faster fix.
 * [pausedAt] is when the slow stretch began, so the stopped seconds are not counted as moving.
 */
class AutoPause(private val limits: Limits) {
    var paused = false
        private set
    var pausedAt = 0L
        private set
    private var slowSince = 0L

    /** Returns true when the paused state changed. */
    fun update(t: Long, speedMps: Double): Boolean {
        if (speedMps >= limits.pauseBelow) {
            slowSince = 0L
            if (!paused) return false
            paused = false
            return true
        }
        if (slowSince == 0L) slowSince = t
        if (paused || t - slowSince < 5_000) return false
        paused = true
        pausedAt = slowSince
        return true
    }
}

/** One cumulative reading: moving seconds so far and metres so far. */
data class Sample(val movingSec: Double, val distanceM: Double)

/** Seconds taken for each full kilometre, interpolating the moment each km mark was crossed. */
fun splits(samples: List<Sample>): List<Int> {
    val result = mutableListOf<Int>()
    var lastMarkSec = 0.0
    var nextMark = 1000.0
    samples.zipWithNext().forEach { (a, b) ->
        while (b.distanceM >= nextMark && b.distanceM > a.distanceM) {
            val f = (nextMark - a.distanceM) / (b.distanceM - a.distanceM)
            val markSec = a.movingSec + f * (b.movingSec - a.movingSec)
            result += (markSec - lastMarkSec).roundToInt()
            lastMarkSec = markSec
            nextMark += 1000.0
        }
    }
    return result
}

/** "5:58" per km; "--" until there is distance to divide by. */
fun paceText(movingSec: Double, distanceM: Double): String {
    if (distanceM < 10) return "--"
    val perKm = (movingSec / (distanceM / 1000)).roundToLong()
    return if (perKm >= 3600) "--" else "%d:%02d".format(perKm / 60, perKm % 60)
}

fun speedKmh(movingSec: Double, distanceM: Double) = if (movingSec <= 0) 0.0 else distanceM / movingSec * 3.6

fun kmText(distanceM: Double) = "%.2f".format(java.util.Locale.US, distanceM / 1000)

/** Google's encoded polyline (precision 1e5): a route of thousands of points fits in a short string. */
object Polyline {
    fun encode(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        points.forEach { (lat, lon) ->
            val la = (lat * 1e5).roundToLong()
            val lo = (lon * 1e5).roundToLong()
            value(la - lastLat, out)
            value(lo - lastLon, out)
            lastLat = la
            lastLon = lo
        }
        return out.toString()
    }

    fun decode(text: String): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var i = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = text[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < text.length) {
            lat += next()
            lon += next()
            out += lat / 1e5 to lon / 1e5
        }
        return out
    }

    private fun value(v: Long, out: StringBuilder) {
        var n = if (v < 0) (v shl 1).inv() else v shl 1
        while (n >= 0x20) {
            out.append(((0x20 or (n and 0x1f).toInt()) + 63).toChar())
            n = n shr 5
        }
        out.append((n.toInt() + 63).toChar())
    }
}
