package com.feri.aolon_cyberpunk.cardio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.feri.aolon_cyberpunk.MainActivity
import com.feri.aolon_cyberpunk.R
import com.feri.aolon_cyberpunk.formatDuration
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Keeps GPS running with the screen off; the notification is Android's price for that. */
class CardioService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { CardioRecorder.onLocation(TrackPoint(it.time, it.latitude, it.longitude, it.accuracy)) }
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(ID, notification())
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!CardioRecorder.recording || !hasLocation(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        channel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(ID, notification())
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000).setMinUpdateIntervalMillis(1_000).build()
        runCatching { fused.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
            .onFailure { Log.w("AolonCardio", "location updates refused", it) }
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 5_000)
        Log.i("AolonCardio", "recording ${CardioRecorder.activity}")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val r = CardioRecorder
        val text = buildString {
            append("${kmText(r.distanceM)} km · ${formatDuration(r.movingSec().toLong())}")
            if (r.paused) append(" · Paused")
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aolon)
            .setColor(0xFFCE82FF.toInt())
            .setContentTitle("Recording ${r.activity.lowercase()}")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Activity recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "cardio"

        fun hasLocation(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, CardioService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, CardioService::class.java))
    }
}
