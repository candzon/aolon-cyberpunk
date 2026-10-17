package com.feri.aolon_cyberpunk.cardio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.feri.aolon_cyberpunk.MainActivity
import com.feri.aolon_cyberpunk.R

/** Posts through Android so a paired watch can mirror the cardio alert. */
object ZoneAlertNotification {
    // Channel sound and vibration are fixed once created, so the old silent channel is replaced, not edited.
    private const val CHANNEL_ID = "cardio_zone_alerts_v2"
    private const val OLD_CHANNEL_ID = "cardio_zone_alerts"
    private const val NOTIFICATION_ID = 8

    fun post(context: Context, activity: String, drift: Int, targetZone: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
            // No setSound call: the channel keeps the phone's default notification sound.
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Heart rate zone alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                },
            )
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }

        val direction = if (drift > 0) "above" else "below"
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_aolon)
                .setColor(0xFFCE82FF.toInt())
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentTitle("${activity}: $direction target zone")
                .setContentText("Heart rate is $direction target zone $targetZone.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return true
    }
}
