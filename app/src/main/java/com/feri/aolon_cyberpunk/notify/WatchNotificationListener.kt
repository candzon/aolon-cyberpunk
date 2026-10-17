package com.feri.aolon_cyberpunk.notify

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/** Forwards notifications from the apps the user picked; nothing is stored or sent anywhere else. */
class WatchNotificationListener : NotificationListenerService() {
    /** Chat apps repost the same notification on every update; only changed text reaches the watch. */
    private val lastText = LinkedHashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = WatchNotifyPrefs(this)
        if (!prefs.phoneNotifications || sbn.packageName == packageName || sbn.packageName !in prefs.apps) return
        val n = sbn.notification
        val skip = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and skip != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        val body = watchText(sbn.packageName, app, title, text)
        if (lastText[sbn.key] == body) return
        lastText[sbn.key] = body
        if (lastText.size > 50) lastText.remove(lastText.keys.first())
        val sink = WatchInbox.sink
        // Package and length only: message text never goes to logcat.
        Log.i("AolonNotify", "${sbn.packageName} -> watch ${if (sink == null) "skipped, app not running" else "queued"}, ${body.length} chars")
        sink?.invoke(watchType(sbn.packageName), body)
    }

    companion object {
        fun hasAccess(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
