package com.feri.aolon_cyberpunk.cardio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class Recording(
    val activity: String,
    val startedAt: Long,
    val movingSec: Long,
    val distanceM: Double,
    val route: List<TrackPoint>,
    /** [secondsFromStart, bpm] every 5 s. */
    val hr: List<Pair<Int, Int>>,
    val splits: List<Int>,
) {
    val avgHr get() = hr.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toInt()
    val maxHr get() = hr.maxOfOrNull { it.second }
}

/**
 * The one activity being recorded, shared by the location service and the UI so the run survives
 * the screen turning off. Main-thread only: location callbacks and BLE events both land there.
 */
object CardioRecorder {
    var activity by mutableStateOf("")
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var recording by mutableStateOf(false)
        private set
    var manualPause by mutableStateOf(false)
        private set
    var autoPaused by mutableStateOf(false)
        private set
    val paused get() = manualPause || autoPaused
    var distanceM by mutableDoubleStateOf(0.0)
        private set
    var heartRate by mutableIntStateOf(0)
        private set
    var lastHrAt by mutableLongStateOf(0L)
        private set
    var gpsFixes by mutableIntStateOf(0)
        private set
    val route = mutableStateListOf<TrackPoint>()

    private var movingMs = 0L
    /** Start of the current moving stretch; null while paused. */
    private var movingSince: Long? = null
    private var last: TrackPoint? = null
    private var autoPause = AutoPause(limits("Walk"))
    private val samples = mutableListOf<Sample>()
    private val hr = mutableListOf<Pair<Int, Int>>()

    val usesGps get() = activity in GPS_ACTIVITIES

    fun movingSec(now: Long = System.currentTimeMillis()) =
        (movingMs + (movingSince?.let { now - it } ?: 0L)) / 1000.0

    /** Pace over the last 30 moving seconds, so it reacts to speed changes without jumping on every fix. */
    fun currentPace(): String {
        val end = samples.lastOrNull() ?: return "--"
        val start = samples.lastOrNull { end.movingSec - it.movingSec >= 30 } ?: samples.first()
        return paceText(end.movingSec - start.movingSec, end.distanceM - start.distanceM)
    }

    fun start(activity: String, now: Long = System.currentTimeMillis()) {
        reset()
        this.activity = activity
        recording = true
        startedAt = now
        movingSince = now
        autoPause = AutoPause(limits(activity))
    }

    fun pause(now: Long = System.currentTimeMillis()) {
        if (!recording || manualPause) return
        manualPause = true
        stopClock(now)
    }

    fun resume(now: Long = System.currentTimeMillis()) {
        if (!recording || !manualPause) return
        manualPause = false
        autoPaused = false
        last = null // the first fix after a pause starts a fresh segment instead of a straight jump
        movingSince = now
    }

    fun onLocation(p: TrackPoint) {
        if (!recording || manualPause || !usesGps || !accept(last, p, activity)) return
        gpsFixes++
        val prev = last
        last = p
        if (prev == null) {
            route += p
            return
        }
        val metres = haversineM(prev, p)
        if (autoPause.update(p.t, metres / ((p.t - prev.t) / 1000.0))) {
            autoPaused = autoPause.paused
            if (autoPaused) stopClock(autoPause.pausedAt) else movingSince = p.t
        }
        // Standing still, GPS wanders a few metres; that drift is neither route nor distance.
        if (autoPaused) return
        route += p
        distanceM += metres
        samples += Sample(movingSec(p.t), distanceM)
    }

    fun onHeartRate(bpm: Int, now: Long = System.currentTimeMillis()) {
        if (!recording) return
        heartRate = bpm
        lastHrAt = now
        val at = ((now - startedAt) / 1000).toInt()
        if (hr.isEmpty() || at - hr.last().first >= 5) hr += at to bpm
    }

    fun finish(now: Long = System.currentTimeMillis()): Recording? {
        if (!recording) return null
        stopClock(now)
        val result = Recording(activity, startedAt, (movingMs / 1000), distanceM, route.toList(), hr.toList(), splits(samples))
        reset()
        return result
    }

    fun discard() = reset()

    private fun stopClock(at: Long) {
        val since = movingSince ?: return
        movingMs += (at - since).coerceAtLeast(0)
        movingSince = null
    }

    private fun reset() {
        activity = ""
        recording = false
        startedAt = 0L
        manualPause = false
        autoPaused = false
        distanceM = 0.0
        heartRate = 0
        lastHrAt = 0L
        gpsFixes = 0
        route.clear()
        movingMs = 0L
        movingSince = null
        last = null
        samples.clear()
        hr.clear()
    }
}
