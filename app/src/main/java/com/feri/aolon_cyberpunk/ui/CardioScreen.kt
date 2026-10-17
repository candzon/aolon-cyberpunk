package com.feri.aolon_cyberpunk.ui

import android.Manifest
import android.content.Intent
import android.graphics.Paint
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.feri.aolon_cyberpunk.ACTIVITIES
import com.feri.aolon_cyberpunk.AolonViewModel
import com.feri.aolon_cyberpunk.Route
import com.feri.aolon_cyberpunk.Session
import com.feri.aolon_cyberpunk.ble.LinkState
import com.feri.aolon_cyberpunk.cardio.CardioRecorder
import com.feri.aolon_cyberpunk.cardio.CardioService
import com.feri.aolon_cyberpunk.cardio.GPS_ACTIVITIES
import com.feri.aolon_cyberpunk.cardio.kmText
import com.feri.aolon_cyberpunk.cardio.maxHr
import com.feri.aolon_cyberpunk.cardio.zoneOf
import kotlin.math.sign
import com.feri.aolon_cyberpunk.cardio.paceText
import com.feri.aolon_cyberpunk.cardio.speedKmh
import com.feri.aolon_cyberpunk.formatDuration
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private val START_PERMISSIONS =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LOCATION + Manifest.permission.POST_NOTIFICATIONS else LOCATION

class CardioStarter(val denied: Boolean, val start: (activity: String) -> Unit)

/**
 * One start path for the Start button and plan rows: asks for location only for GPS activities,
 * and reopens the running activity instead of starting a second one.
 */
@Composable
fun rememberCardioStarter(vm: AolonViewModel, open: () -> Unit): CardioStarter {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val begin = {
        vm.startCardio()
        open()
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result[Manifest.permission.ACCESS_FINE_LOCATION] != true
        if (!denied) begin()
    }
    return CardioStarter(denied) { activity ->
        if (CardioRecorder.recording) open()
        else {
            vm.cardioActivity = activity
            if (activity !in GPS_ACTIVITIES || CardioService.hasLocation(context)) begin() else ask.launch(START_PERMISSIONS)
        }
    }
}

/** Start card: activity choice and Start, or a way back into the activity already recording. */
@Composable
fun CardioStart(vm: AolonViewModel, starter: CardioStarter, open: () -> Unit) {
    val context = LocalContext.current

    if (CardioRecorder.recording) {
        SectionCard("Recording", trailing = CardioRecorder.activity) {
            Text(
                if (CardioRecorder.usesGps) "${kmText(CardioRecorder.distanceM)} km so far" else "Recording time and heart rate",
                color = Neon.Muted,
            )
            PrimaryButton("Back to activity", open, Modifier.fillMaxWidth())
        }
        return
    }
    SectionCard("Record an activity") {
        ChoiceChips(ACTIVITIES, { it == vm.cardioActivity }, { vm.cardioActivity = it })
        Text(
            if (vm.cardioActivity in GPS_ACTIVITIES) "Records your route with the phone's GPS and heart rate from the watch."
            else "Records time and heart rate from the watch, without GPS. Use for walking or running in place.",
            color = Neon.Soft,
            style = MaterialTheme.typography.bodySmall,
        )
        ZonePicker(vm)
        if (starter.denied) {
            Text("Aolon can't record a route without location access.", color = Neon.Red, style = MaterialTheme.typography.bodySmall)
            TextButton({
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Open app settings", color = Neon.Blue) }
        }
        PrimaryButton("Start ${vm.cardioActivity.lowercase()}", { starter.start(vm.cardioActivity) }, Modifier.fillMaxWidth())
    }
}

/** Live page while recording. Distance is the one big number; the rest support it. */
@Composable
fun CardioRecording(vm: AolonViewModel, notify: (String) -> Unit, back: () -> Unit) {
    val r = CardioRecorder
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val moving = r.movingSec(now)
    val watchReady = vm.watch.state.collectAsState().value == LinkState.Ready
    val hrFresh = r.lastHrAt != 0L && now - r.lastHrAt < 30_000
    val mhr = vm.profile.age?.let(::maxHr)
    val target = vm.settings.targetZone
    val zone = if (hrFresh && mhr != null) zoneOf(r.heartRate, mhr) else null
    var notificationsAllowed by remember { mutableStateOf<Boolean?>(null) }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = it }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationsAllowed = true
        }
    }
    // -1 below, 0 in, 1 above the target. Buzz once if it stays off-target for 10 s, not on every blip.
    val drift = if (zone == null || target == null) 0 else (zone - target).sign
    LaunchedEffect(drift, target, r.activity) {
        if (drift != 0) {
            delay(10_000)
            // The notification carries the phone's sound and vibration; buzz only when it can't be shown.
            val posted = target?.let { com.feri.aolon_cyberpunk.cardio.ZoneAlertNotification.post(context, r.activity, drift, it) } == true
            if (target != null && com.feri.aolon_cyberpunk.notify.WatchNotifyPrefs(context).heartRateAlerts) {
                // The watch screen is small: name, bpm, zone and one action, nothing more.
                val way = if (drift > 0) "above Z$target. Ease off." else "below Z$target. Speed up."
                vm.sendToWatch(com.feri.aolon_cyberpunk.notify.TYPE_OTHERS, "Aolon Cyberpunk · ${r.heartRate} bpm, $way")
            }
            if (!posted) {
                buzz(context)
                notificationsAllowed = false
            }
        }
    }
    val gpsOff = r.usesGps && context.getSystemService(LocationManager::class.java)?.isLocationEnabled == false
    val status = when {
        r.manualPause -> "Paused"
        r.autoPaused -> "Auto-paused. Resumes when you move."
        gpsOff -> "Location is turned off on the phone. Turn it on to record the route."
        r.usesGps && r.gpsFixes == 0 -> "Waiting for GPS. Stay outdoors with a clear sky."
        !watchReady -> "Watch not connected, so no heart rate."
        !hrFresh -> "Waiting for heart rate from the watch."
        else -> null
    }

    Column(Modifier.fillMaxSize().background(Neon.Void)) {
        TopBar(r.activity, back)
        if (r.usesGps) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RouteMap(r.route.map { it.lat to it.lon }, follow = true, modifier = Modifier.fillMaxSize())
                Attribution(Modifier.align(Alignment.BottomEnd))
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (hrFresh) "${r.heartRate}" else "--", style = Telemetry.copy(fontSize = 96.sp), color = Neon.Red)
                    Text("bpm", color = Neon.Muted)
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (r.usesGps) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(kmText(r.distanceM), style = Telemetry.copy(fontSize = 56.sp), color = Neon.White)
                    Text(" km", color = Neon.Muted, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Figure("Time", formatDuration(moving.toLong()), Modifier.weight(1f))
                if (r.usesGps) {
                    if (r.activity == "Cycle") Figure("Speed", "%.1f km/h".format(Locale.US, speedKmh(moving, r.distanceM)), Modifier.weight(1f))
                    else Figure("Pace", "${r.currentPace()} /km", Modifier.weight(1f))
                }
                Figure("Heart rate", if (hrFresh) "${r.heartRate} bpm" else "--", Modifier.weight(1f))
            }
            if (zone != null) {
                ZoneBar(zone, target)
                Text(zoneMessage(zone, target), color = if (drift == 0) Neon.Muted else Neon.Blue, style = MaterialTheme.typography.bodyMedium)
            } else if (mhr == null && hrFresh) {
                Text("Add your age in the Cardio start card to see heart rate zones.", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
            }
            status?.let { Text(it, color = if (r.paused) Neon.Blue else Neon.Muted, style = MaterialTheme.typography.bodyMedium) }
            if (notificationsAllowed == false) {
                Text("Allow notifications in Android settings to send cardio alerts to your watch.", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(if (r.manualPause) "Resume" else "Pause", { if (r.manualPause) r.resume() else r.pause() }, Modifier.weight(1f))
                PrimaryButton("Finish", {
                    val saved = vm.finishCardio()
                    notify(if (saved != null) "${saved.activity} saved." else "Too short to save.")
                    back()
                }, Modifier.weight(1f))
            }
            TextButton({ confirmDiscard = true }, Modifier.align(Alignment.CenterHorizontally)) { Text("Discard", color = Neon.Red) }
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard this ${r.activity.lowercase()}?") },
        text = { Text("The route and time recorded so far won't be saved.") },
        confirmButton = {
            TextButton({
                vm.discardCardio()
                confirmDiscard = false
                back()
            }) { Text("Discard", color = Neon.Red) }
        },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep recording") } },
        containerColor = Neon.PanelLight,
    )
}

/** One history line: what, when, how far, how long, how fast. */
fun sessionSummary(s: Session): String = listOfNotNull(
    SimpleDateFormat("EEE d MMM", Locale.US).format(Date(s.startedAt)),
    s.distanceM?.let { "${kmText(it)} km" },
    formatDuration(s.duration),
    s.distanceM?.let { if (s.activity == "Cycle") "%.1f km/h".format(Locale.US, speedKmh(s.duration.toDouble(), it)) else "${paceText(s.duration.toDouble(), it)} /km" },
).joinToString(" · ")

@Composable
fun CardioDetail(vm: AolonViewModel, s: Session, close: () -> Unit) {
    var route by remember { mutableStateOf<Route?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(s.id) {
        route = vm.route(s.id)
        loading = false
    }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Neon.Void)) {
            DetailBar(s.activity, close) {
                vm.deleteSession(s)
                close()
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(Date(s.startedAt)), color = Neon.Muted)
                val points = route?.points.orEmpty()
                when {
                    loading && s.distanceM != null -> Text("Loading route…", color = Neon.Muted)
                    points.size >= 2 -> Box(Modifier.fillMaxWidth().height(260.dp)) {
                        RouteMap(points, follow = false, modifier = Modifier.fillMaxSize())
                        Attribution(Modifier.align(Alignment.BottomEnd))
                    }
                    s.distanceM != null -> Text("No route was saved for this activity.", color = Neon.Muted)
                }
                s.distanceM?.let { d ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(kmText(d), style = Telemetry.copy(fontSize = 48.sp), color = Neon.White)
                        Text(" km", color = Neon.Muted, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Figure("Moving time", formatDuration(s.duration), Modifier.weight(1f))
                    s.distanceM?.let { d ->
                        if (s.activity == "Cycle") Figure("Avg speed", "%.1f km/h".format(Locale.US, speedKmh(s.duration.toDouble(), d)), Modifier.weight(1f))
                        else Figure("Avg pace", "${paceText(s.duration.toDouble(), d)} /km", Modifier.weight(1f))
                    }
                }
                if (s.avgHr != null) {
                    Row(Modifier.fillMaxWidth()) {
                        Figure("Avg heart rate", "${s.avgHr} bpm", Modifier.weight(1f))
                        Figure("Max heart rate", "${s.maxHr} bpm", Modifier.weight(1f))
                    }
                    route?.hr?.takeIf { it.size >= 2 }?.let { hr ->
                        Text("Heart rate over the activity", color = Neon.Muted, style = MaterialTheme.typography.labelMedium)
                        TrendLine(hr.map { it.second.toDouble() })
                    }
                    val hr = route?.hr.orEmpty()
                    val age = vm.profile.age
                    if (hr.isNotEmpty() && age != null) ZoneTimes(hr, age, s.targetZone)
                } else {
                    Text("No heart rate: the watch wasn't connected during this activity.", color = Neon.Soft, style = MaterialTheme.typography.bodySmall)
                }
                if (s.splits.isNotEmpty()) SectionCard("Splits") {
                    val fastest = s.splits.min()
                    s.splits.forEachIndexed { i, sec ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("Km ${i + 1}", Modifier.weight(1f), color = Neon.Muted)
                            Text(
                                "%d:%02d".format(sec / 60, sec % 60) + if (sec == fastest && s.splits.size > 1) "  fastest" else "",
                                style = Telemetry,
                                color = if (sec == fastest && s.splits.size > 1) Neon.Purple else Neon.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(title: String, back: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Sport") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Neon.Void),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailBar(title: String, close: () -> Unit, delete: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = { IconButton(close) { Icon(Icons.Filled.Close, "Close") } },
        actions = { IconButton(delete) { Icon(Icons.Outlined.Delete, "Delete $title", tint = Neon.Red) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Neon.Void),
    )
}

// OSM's licence requires visible credit wherever its tiles are shown.
@Composable
private fun Attribution(modifier: Modifier) {
    Text(
        "© OpenStreetMap contributors",
        modifier.background(Neon.Void.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Neon.Muted,
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * OpenStreetMap through osmdroid. Tiles are colour-inverted so the map sits in the dark app instead
 * of glaring white; the route is the purple line. [follow] keeps the newest point centred while recording.
 */
@Composable
fun RouteMap(points: List<Pair<Double, Double>>, follow: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName // OSM tile policy: identify the app
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            controller.setZoom(17.0)
        }
    }
    val line = remember {
        Polyline(map).apply {
            outlinePaint.color = Neon.Purple.toArgb()
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            map.overlays.add(this)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }
    AndroidView({ map }, modifier, update = { view ->
        val geo = points.map { GeoPoint(it.first, it.second) }
        line.setPoints(geo)
        when {
            follow -> geo.lastOrNull()?.let { view.controller.setCenter(it) }
            geo.size >= 2 -> view.post { view.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.3f), false) }
        }
        view.invalidate()
    })
}
