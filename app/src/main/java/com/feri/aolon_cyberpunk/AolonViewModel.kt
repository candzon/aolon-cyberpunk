package com.feri.aolon_cyberpunk

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feri.aolon_cyberpunk.ble.FoundWatch
import com.feri.aolon_cyberpunk.ble.LinkState
import com.feri.aolon_cyberpunk.ble.Metric
import com.feri.aolon_cyberpunk.ble.Ute
import com.feri.aolon_cyberpunk.ble.WatchClient
import com.feri.aolon_cyberpunk.ble.WatchEvent
import com.feri.aolon_cyberpunk.cardio.CardioRecorder
import com.feri.aolon_cyberpunk.cardio.CardioService
import com.feri.aolon_cyberpunk.cardio.GPS_ACTIVITIES
import com.feri.aolon_cyberpunk.cardio.Polyline
import com.feri.aolon_cyberpunk.gym.GymLog
import com.feri.aolon_cyberpunk.notify.MessagePusher
import com.feri.aolon_cyberpunk.notify.WatchInbox
import com.feri.aolon_cyberpunk.gym.RecordStore
import com.feri.aolon_cyberpunk.lyfta.LyftaClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val ACTIVITIES = listOf("Walk", "Run", "Cycle", "Workout", "Indoor")
val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** [duration] is moving seconds; GPS fields are null for indoor and pre-GPS sessions. */
data class Session(
    val id: String,
    val activity: String,
    val startedAt: Long,
    val duration: Long,
    val distanceM: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val splits: List<Int> = emptyList(),
    val targetZone: Int? = null,
) {
    val date: LocalDate get() = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
}

/** Stored apart from the session so the history list never downloads every route. */
class Route(val points: List<Pair<Double, Double>>, val hr: List<Pair<Int, Int>>)
data class Plan(val id: String, val activity: String, val days: List<String>)
data class Alarm(val id: String, val label: String, val time: String)
data class AppSettings(
    val watchface: String = "violet",
    val stepGoal: Int? = null,
    val distanceUnit: String = "km",
    val restSeconds: Int = 90,
    /** Cardio target heart rate zone 1..5, or null for no target. */
    val targetZone: Int? = null,
)

data class Profile(val male: Boolean = true, val age: Int? = null, val heightCm: Double? = null, val weightKg: Double? = null) {
    val complete get() = age != null && heightCm != null && weightKg != null
}
data class BodyLog(val id: String, val at: Long, val weightKg: Double, val bodyFat: Double, val bmiMethod: Double)
data class Food(val id: String, val date: LocalDate, val name: String, val kcal: Int)

/** Daily energy estimate; every part is a formula, not a measurement. */
class Burn(val resting: Int, val steps: Int, val workouts: Int) {
    val total get() = resting + steps + workouts
}

// MET values from the Compendium of Physical Activities (walking 3.5, running 6 mph 9.8, cycling general 7.5, calisthenics 3.8).
private val MET = mapOf("Walk" to 3.5, "Run" to 9.8, "Cycle" to 7.5, "Workout" to 3.8)
// Compendium: resistance training, multiple exercises, 8 to 15 reps.
private const val GYM_MET = 3.5

/** The latest soft delete, offered back to the user once. */
class Undo(val message: String, val restore: () -> Unit)

sealed interface Cloud {
    data object Loading : Cloud
    data object SignedOut : Cloud
    data object Ready : Cloud
    data class Failed(val reason: String) : Cloud
}

/**
 * Records live in Firebase under /aolon/users/{uid}, uid from Google sign-in.
 * Loaded once, then every change is written through; nothing is kept in app storage.
 */
class AolonViewModel(app: Application) : AndroidViewModel(app), RecordStore {
    private val auth = FirebaseAuth.getInstance()
    private var root: DatabaseReference? = null
    val gym = GymLog(this)
    private val lyfta = LyftaClient(app)
    var lyftaConnected by mutableStateOf(lyfta.connected)
        private set
    var lyftaBusy by mutableStateOf(false)
        private set
    // Separate from lyftaBusy, which also covers sign-in, so a sync right after sign-in still runs.
    private var lyftaSyncing = false
    var cloud by mutableStateOf<Cloud>(Cloud.Loading)
        private set
    /** One-shot message (failed write, import result) shown by the UI then cleared. */
    var notice by mutableStateOf<String?>(null)
    /** Only the most recent delete can be undone; a newer delete replaces it. */
    var undo by mutableStateOf<Undo?>(null)

    val sessions = mutableStateListOf<Session>()
    val plans = mutableStateListOf<Plan>()
    val alarms = mutableStateListOf<Alarm>()
    val bodyLogs = mutableStateListOf<BodyLog>()
    val food = mutableStateListOf<Food>()
    var profile by mutableStateOf(Profile())
        private set
    var settings by mutableStateOf(AppSettings())
        private set

    var cardioActivity by mutableStateOf(ACTIVITIES.first())
    private var hrKeepAlive: Job? = null

    val watch = WatchClient(app)
    var savedWatch by mutableStateOf<FoundWatch?>(null)
        private set
    var battery by mutableStateOf<WatchEvent.Battery?>(null)
        private set
    var firmware by mutableStateOf<String?>(null)
        private set
    var heartRate by mutableStateOf<Int?>(null)
        private set
    var oxygen by mutableStateOf<Int?>(null)
        private set
    var pressure by mutableStateOf<WatchEvent.BloodPressure?>(null)
        private set
    /** From the watch's feature bitmap; null until read. */
    var hasBloodPressure by mutableStateOf<Boolean?>(null)
        private set
    val measuring = mutableStateMapOf<Metric, Boolean>()
    /** Metrics this watch never acknowledged, e.g. no blood pressure sensor. */
    val unsupported = mutableStateMapOf<Metric, Boolean>()
    private val ackWait = mutableMapOf<Metric, Job>()

    /** Steps per hour for each day the watch has reported. */
    val steps = mutableStateMapOf<LocalDate, Map<Int, Int>>()
    var syncing by mutableStateOf(false)
        private set
    private var syncTimeout: Job? = null

    fun stepsOn(date: LocalDate) = steps[date]?.values?.sum() ?: 0
    val stepsToday get() = stepsOn(LocalDate.now())

    private val messages = MessagePusher { watch.send(it) }

    init {
        connectCloud()
        viewModelScope.launch { watch.events.collect(::onWatchEvent) }
        WatchInbox.sink = ::sendToWatch
        viewModelScope.launch {
            while (true) {
                delay(2_000)
                messages.expire()
            }
        }
        viewModelScope.launch {
            watch.state.collect { if (it == LinkState.Ready) onWatchReady() }
        }
    }

    val account: String? get() = auth.currentUser?.email

    /** Loads the signed-in user's records; with no user, waits on the sign-in screen. */
    fun connectCloud() {
        val uid = auth.currentUser?.uid ?: run {
            cloud = Cloud.SignedOut
            return
        }
        cloud = Cloud.Loading
        viewModelScope.launch {
            cloud = runCatching {
                val ref = FirebaseDatabase.getInstance().getReference("aolon/users/$uid")
                load(importLegacy(ref, ref.get().await()))
                root = ref
                Cloud.Ready
            }.getOrElse { Cloud.Failed(it.message ?: "Could not reach Firebase.") }
            if (cloud == Cloud.Ready) {
                reconnect()
                if (lyfta.connected) importLyfta(quiet = true)
            }
        }
    }

    /** Opens Lyfta's sign-in page; the browser comes back through [finishLyfta]. */
    fun connectLyfta(open: (Uri) -> Unit) {
        viewModelScope.launch {
            lyftaBusy = true
            runCatching { lyfta.authorizeUrl() }.onSuccess(open).onFailure { notice = "Lyfta: ${it.message}" }
            lyftaBusy = false
        }
    }

    fun finishLyfta(redirect: Uri) {
        viewModelScope.launch {
            lyftaBusy = true
            runCatching { lyfta.finish(redirect) }
                .onSuccess { lyftaConnected = true; importLyfta(quiet = false) }
                .onFailure { notice = "Lyfta: ${it.message}" }
            lyftaBusy = false
        }
    }

    fun syncLyfta() {
        viewModelScope.launch { importLyfta(quiet = false) }
    }

    fun disconnectLyfta() {
        lyfta.disconnect()
        lyftaConnected = false
        notice = "Lyfta disconnected. Imported workouts stay in your history."
    }

    // Startup syncs stay quiet unless something new arrived or the sign-in broke.
    private suspend fun importLyfta(quiet: Boolean) {
        if (root == null || lyftaSyncing) {
            Log.i("AolonLyfta", "sync skipped: cloud=${root != null} running=$lyftaSyncing")
            return
        }
        lyftaSyncing = true
        lyftaBusy = true
        runCatching {
            val fetched = lyfta.recentWorkouts()
            Log.i("AolonLyfta", "fetched ${fetched.size} workouts, ${fetched.sumOf { it.exercises.sumOf { e -> e.sets.size } }} completed sets")
            gym.importLyfta(fetched)
        }
            .onSuccess { n ->
                Log.i("AolonLyfta", "imported $n new")
                if (n > 0 || !quiet) notice = if (n > 0) "Imported $n workouts from Lyfta." else "Lyfta is up to date."
            }
            .onFailure {
                Log.w("AolonLyfta", "sync failed", it)
                notice = "Lyfta: ${it.message}"
                lyftaConnected = lyfta.connected
            }
        lyftaSyncing = false
        lyftaBusy = false
    }

    /** Exchanges the Google ID token from Credential Manager for a Firebase session. */
    fun signIn(googleIdToken: String) {
        cloud = Cloud.Loading
        viewModelScope.launch {
            runCatching { auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null)).await() }
                .onSuccess { connectCloud() }
                .onFailure { cloud = Cloud.Failed(it.message ?: "Google sign-in failed.") }
        }
    }

    fun signOut() {
        watch.disconnect()
        auth.signOut()
        root = null
        sessions.clear()
        plans.clear()
        alarms.clear()
        steps.clear()
        bodyLogs.clear()
        food.clear()
        profile = Profile()
        gym.clear()
        settings = AppSettings()
        discardCardio()
        savedWatch = null
        battery = null
        firmware = null
        cloud = Cloud.SignedOut
    }

    /** Moves pre-Firebase records into the account once; on failure they stay on the phone for the next launch. */
    private suspend fun importLegacy(ref: DatabaseReference, cloud: DataSnapshot): DataSnapshot {
        val app = getApplication<Application>()
        val plan = LegacyImport.plan(app, cloud) ?: return cloud
        return runCatching {
            if (plan.updates.isNotEmpty()) ref.updateChildren(plan.updates).await()
            LegacyImport.clear(app)
            if (plan.workouts + plan.stepDays > 0) {
                notice = "Moved ${plan.workouts} workouts and ${plan.stepDays} days of steps from this phone to your account."
            }
            ref.get().await()
        }.getOrElse {
            notice = "Old data not moved yet: ${it.message}"
            cloud
        }
    }

    private fun load(data: DataSnapshot) {
        sessions.clear()
        plans.clear()
        alarms.clear()
        steps.clear()
        bodyLogs.clear()
        food.clear()
        sessions += data.child("sessions").entries { s ->
            Session(
                s.key!!, s.str("activity")!!, s.long("startedAt")!!, s.long("duration")!!,
                distanceM = s.num("distanceM"),
                avgHr = s.long("avgHr")?.toInt(),
                maxHr = s.long("maxHr")?.toInt(),
                splits = s.child("splits").children.mapNotNull { (it.value as? Number)?.toInt() },
                targetZone = s.long("targetZone")?.toInt(),
            )
        }.sortedBy { it.startedAt }
        plans += data.child("plans").entries { p ->
            Plan(p.key!!, p.str("activity")!!, p.child("days").children.mapNotNull { it.getValue(String::class.java) })
        }
        alarms += data.child("alarms").entries { a -> Alarm(a.key!!, a.str("label")!!, a.str("time")!!) }.sortedBy { it.time }
        bodyLogs += data.child("bodyLogs").entries { b ->
            BodyLog(b.key!!, b.long("at")!!, b.num("weightKg")!!, b.num("bodyFat")!!, b.num("bmiMethod")!!)
        }.sortedBy { it.at }
        data.child("food").children.forEach { day ->
            val date = runCatching { LocalDate.parse(day.key) }.getOrNull() ?: return@forEach
            food += day.entries { f -> Food(f.key!!, date, f.str("name")!!, f.long("kcal")!!.toInt()) }
        }
        val pr = data.child("profile")
        profile = Profile(
            male = pr.child("male").getValue(Boolean::class.java) ?: true,
            age = pr.long("age")?.toInt(),
            heightCm = pr.num("heightCm"),
            weightKg = pr.num("weightKg"),
        )
        val s = data.child("settings")
        settings = AppSettings(
            watchface = s.str("watchface") ?: "violet",
            stepGoal = s.long("stepGoal")?.toInt()?.takeIf { it > 0 },
            distanceUnit = s.str("distanceUnit") ?: "km",
            restSeconds = s.long("restSeconds")?.toInt() ?: 90,
            targetZone = s.long("targetZone")?.toInt()?.takeIf { it in 1..5 },
        )
        data.child("steps").children.forEach { day ->
            val date = runCatching { LocalDate.parse(day.key) }.getOrNull() ?: return@forEach
            steps[date] = day.children.associate { it.key!!.removePrefix("h").toInt() to (it.getValue(Long::class.java) ?: 0L).toInt() }
        }
        gym.load(data)
        savedWatch = data.child("watch").str("address")?.let { FoundWatch(it, data.child("watch").str("name") ?: it, 0, true) }
    }

    // Corrupt entries are skipped one by one instead of failing the whole load.
    private fun <T> DataSnapshot.entries(parse: (DataSnapshot) -> T) =
        children.filterNot { it.hasChild("deletedAt") }.mapNotNull { runCatching { parse(it) }.getOrNull() }
    private fun DataSnapshot.str(key: String) = child(key).getValue(String::class.java)
    private fun DataSnapshot.long(key: String) = child(key).getValue(Long::class.java)
    // Firebase returns whole numbers as Long and fractions as Double.
    private fun DataSnapshot.num(key: String) = (child(key).value as? Number)?.toDouble()

    override fun write(path: String, value: Any?) {
        val ref = root ?: return
        ref.child(path).setValue(value).addOnFailureListener { notice = "Not saved: ${it.message}" }
    }

    /** Soft delete: the record stays in Firebase with a server timestamp and is skipped on load. */
    private fun softDelete(path: String) = write("$path/deletedAt", ServerValue.TIMESTAMP)

    /** Hides [item] now and offers Undo, which puts it back at the same position and clears the marker. */
    override fun <T> deleteWithUndo(list: MutableList<T>, item: T, path: String, message: String) {
        val index = list.indexOf(item).takeIf { it >= 0 } ?: return
        list.removeAt(index)
        softDelete(path)
        undo = Undo(message) {
            if (item !in list) list.add(index.coerceAtMost(list.size), item)
            write("$path/deletedAt", null)
        }
    }

    override fun newKey(list: String) = root?.child(list)?.push()?.key ?: System.currentTimeMillis().toString()

    /** Reconnects to the last watch; the UI calls this again once Bluetooth permission is granted. */
    fun reconnect() {
        val saved = savedWatch ?: return
        if (watch.state.value == LinkState.Idle && watch.isAvailable) watch.connect(saved.address, saved.name)
    }

    fun pair(found: FoundWatch) {
        write("watch", mapOf("address" to found.address, "name" to found.name))
        savedWatch = found
        watch.connect(found.address, found.name)
    }

    fun forgetWatch() {
        watch.disconnect()
        write("watch", null)
        savedWatch = null
        battery = null
        firmware = null
    }

    private fun onWatchReady() {
        watch.send(Ute.syncTime())
        watch.send(Ute.firmware())
        watch.send(Ute.battery())
        syncSteps()
    }

    fun syncSteps() {
        syncing = true
        watch.send(Ute.syncSteps())
        syncTimeout?.cancel()
        // Older firmware never sends an end marker, so stop the spinner after a quiet period.
        syncTimeout = viewModelScope.launch {
            delay(15_000)
            syncing = false
        }
    }

    fun refreshBattery() = watch.send(Ute.battery())

    fun syncTime() = watch.send(Ute.syncTime())

    fun measure(metric: Metric, start: Boolean) {
        val command = when (metric) {
            Metric.HeartRate -> Ute.heartRate(start)
            Metric.Oxygen -> Ute.oxygen(start)
            Metric.BloodPressure -> Ute.bloodPressure(start)
        }
        measuring[metric] = start
        watch.send(command)
        ackWait.remove(metric)?.cancel()
        if (start) {
            ackWait[metric] = viewModelScope.launch {
                delay(6_000)
                measuring[metric] = false
                unsupported[metric] = true
            }
        }
    }

    private fun acknowledged(metric: Metric) {
        ackWait.remove(metric)?.cancel()
        unsupported.remove(metric)
    }

    private fun onWatchEvent(event: WatchEvent) {
        when (event) {
            is WatchEvent.Battery -> battery = event
            is WatchEvent.Firmware -> firmware = event.version
            is WatchEvent.Features -> hasBloodPressure = event.bloodPressure
            is WatchEvent.StepHour -> {
                val day = steps[event.date] ?: emptyMap()
                // A sync replays every stored hour; only changed hours cost a write.
                if (day[event.hour] != event.steps) {
                    steps[event.date] = day + (event.hour to event.steps)
                    // "h" prefix stops Firebase from turning 0..23 keys into an array.
                    write("steps/${event.date}/h%02d".format(event.hour), event.steps)
                }
            }
            WatchEvent.StepSyncDone -> {
                syncTimeout?.cancel()
                syncing = false
            }
            is WatchEvent.Measuring -> {
                acknowledged(event.metric)
                measuring[event.metric] = event.active
            }
            is WatchEvent.HeartRate -> {
                acknowledged(Metric.HeartRate)
                heartRate = event.bpm
                CardioRecorder.onHeartRate(event.bpm)
            }
            is WatchEvent.Oxygen -> {
                acknowledged(Metric.Oxygen)
                oxygen = event.percent
                measuring[Metric.Oxygen] = false
            }
            is WatchEvent.BloodPressure -> {
                acknowledged(Metric.BloodPressure)
                pressure = event
                measuring[Metric.BloodPressure] = false
            }
            is WatchEvent.MessageReply -> messages.onReply(event.code)
            WatchEvent.TimeSynced -> Unit
        }
    }

    /** Queues a message for the watch screen; dropped when the watch is offline, like a missed buzz. */
    fun sendToWatch(type: Int, text: String) {
        viewModelScope.launch {
            if (watch.state.value == LinkState.Ready) messages.push(type, text) else messages.clear()
        }
    }

    override fun onCleared() {
        if (WatchInbox.sink == ::sendToWatch) WatchInbox.sink = null
        watch.disconnect()
    }

    /** Starts recording; GPS activities also start the location service (permission is checked by the UI). */
    fun startCardio() {
        if (CardioRecorder.recording) return
        CardioRecorder.start(cardioActivity)
        val app = getApplication<Application>()
        if (CardioRecorder.usesGps) CardioService.start(app)
        if (watch.state.value == LinkState.Ready) measure(Metric.HeartRate, true)
        // Cheap watches stop streaming after a while; ask again when readings go quiet. The first reading
        // can take over 30 s, and re-sending restarts the measurement, so the wait is generous.
        hrKeepAlive = viewModelScope.launch {
            // Counting the last ask too: without it a silent watch got a restart every 5 s and never finished a reading.
            var askedAt = CardioRecorder.startedAt
            while (CardioRecorder.recording) {
                delay(5_000)
                val now = System.currentTimeMillis()
                val quiet = now - maxOf(CardioRecorder.lastHrAt, askedAt) > 60_000
                if (quiet && watch.state.value == LinkState.Ready) {
                    watch.send(Ute.heartRate(true))
                    askedAt = now
                }
            }
        }
    }

    /** Saves the activity; null when under 10 moving seconds, which is a mis-tap rather than a workout. */
    fun finishCardio(): Session? {
        val fixes = CardioRecorder.gpsFixes
        val r = stopCardio(CardioRecorder.finish()) ?: return null
        Log.i("AolonCardio", "finish ${r.activity}: moving ${r.movingSec}s, ${fixes} gps fixes, ${"%.0f".format(r.distanceM)} m, ${r.hr.size} hr samples")
        if (r.movingSec < 10) return null
        val session = Session(
            newKey("sessions"), r.activity, r.startedAt, r.movingSec,
            distanceM = r.distanceM.takeIf { r.activity in GPS_ACTIVITIES },
            avgHr = r.avgHr, maxHr = r.maxHr, splits = r.splits, targetZone = settings.targetZone,
        )
        sessions += session
        write("sessions/${session.id}", mapOf(
            "activity" to session.activity, "startedAt" to session.startedAt, "duration" to session.duration,
            "distanceM" to session.distanceM, "avgHr" to session.avgHr, "maxHr" to session.maxHr, "splits" to session.splits,
            "targetZone" to session.targetZone,
        ))
        if (r.route.size >= 2 || r.hr.isNotEmpty()) write("routes/${session.id}", mapOf(
            "poly" to Polyline.encode(r.route.map { it.lat to it.lon }),
            "hr" to r.hr.map { listOf(it.first, it.second) },
        ))
        Log.i("AolonCardio", "saved ${r.activity}: ${r.route.size} points, ${r.hr.size} hr samples, ${r.splits.size} splits")
        return session
    }

    fun discardCardio() {
        if (CardioRecorder.recording) Log.i("AolonCardio", "discarded after ${CardioRecorder.movingSec().toInt()}s, ${CardioRecorder.gpsFixes} gps fixes")
        stopCardio(null)
        CardioRecorder.discard()
    }

    private fun <T> stopCardio(result: T): T {
        hrKeepAlive?.cancel()
        CardioService.stop(getApplication())
        if (watch.state.value == LinkState.Ready) measure(Metric.HeartRate, false)
        return result
    }

    suspend fun route(sessionId: String): Route? = runCatching {
        val d = root?.child("routes/$sessionId")?.get()?.await()?.takeIf { it.exists() } ?: return null
        Route(
            Polyline.decode(d.str("poly").orEmpty()),
            d.child("hr").children.mapNotNull { p ->
                val pair = p.children.mapNotNull { (it.value as? Number)?.toInt() }
                if (pair.size == 2) pair[0] to pair[1] else null
            },
        )
    }.getOrNull()

    fun deleteSession(session: Session) = deleteWithUndo(sessions, session, "sessions/${session.id}", "${session.activity} deleted.")

    fun addPlan(activity: String, days: List<String>) {
        val plan = Plan(newKey("plans"), activity, DAYS.filter { it in days })
        plans += plan
        write("plans/${plan.id}", mapOf("activity" to activity, "days" to plan.days))
    }

    fun removePlan(plan: Plan) {
        deleteWithUndo(plans, plan, "plans/${plan.id}", "${plan.activity} plan deleted.")
    }

    fun addAlarm(label: String, time: String) {
        val alarm = Alarm(newKey("alarms"), label, time)
        alarms += alarm
        alarms.sortBy { it.time }
        write("alarms/${alarm.id}", mapOf("label" to label, "time" to time))
    }

    fun removeAlarm(alarm: Alarm) {
        deleteWithUndo(alarms, alarm, "alarms/${alarm.id}", "Alarm ${alarm.time} deleted.")
    }

    fun updateProfile(next: Profile) {
        profile = next
        write("profile", mapOf("male" to next.male, "age" to next.age, "heightCm" to next.heightCm, "weightKg" to next.weightKg))
    }

    fun saveBodyLog(weightKg: Double, result: BodyFat.Result) {
        val log = BodyLog(newKey("bodyLogs"), System.currentTimeMillis(), weightKg, result.navy, result.bmiMethod)
        bodyLogs += log
        write("bodyLogs/${log.id}", mapOf("at" to log.at, "weightKg" to weightKg, "bodyFat" to log.bodyFat, "bmiMethod" to log.bmiMethod))
    }

    fun removeBodyLog(log: BodyLog) {
        deleteWithUndo(bodyLogs, log, "bodyLogs/${log.id}", "Measurement deleted.")
    }

    fun addFood(date: LocalDate, name: String, kcal: Int) {
        val item = Food(newKey("food"), date, name, kcal)
        food += item
        write("food/$date/${item.id}", mapOf("name" to name, "kcal" to kcal))
    }

    fun removeFood(item: Food) {
        deleteWithUndo(food, item, "food/${item.date}/${item.id}", "${item.name} deleted.")
    }

    /**
     * Resting energy (Mifflin-St Jeor) plus net activity above rest:
     * steps at 0.0005 kcal per step per kg, workouts at (MET - 1) x kg x hours. Null until the profile is complete.
     */
    fun burnOn(date: LocalDate): Burn? {
        val p = profile
        if (!p.complete) return null
        val kg = p.weightKg!!
        val resting = BodyFat.restingKcal(p.male, p.age!!, p.heightCm!!, kg)
        val cardio = sessions.filter { it.date == date }.sumOf { ((MET[it.activity] ?: 3.8) - 1) * kg * it.duration / 3600.0 }
        val lifting = gym.workoutsOn(date).sumOf { (GYM_MET - 1) * kg * ((it.endedAt ?: it.startedAt) - it.startedAt) / 3_600_000.0 }
        val workouts = cardio + lifting
        return Burn(resting.toInt(), (stepsOn(date) * 0.0005 * kg).toInt(), workouts.toInt())
    }

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        settings = change(settings)
        write("settings", mapOf(
            "watchface" to settings.watchface, "stepGoal" to settings.stepGoal,
            "distanceUnit" to settings.distanceUnit, "restSeconds" to settings.restSeconds,
            "targetZone" to settings.targetZone,
        ))
    }

}

fun formatDuration(seconds: Long): String =
    "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
