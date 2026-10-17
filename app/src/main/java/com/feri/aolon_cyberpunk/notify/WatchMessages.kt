package com.feri.aolon_cyberpunk.notify

import android.content.Context
import com.feri.aolon_cyberpunk.ble.Ute

/** UTE app types the watch has icons for; anything else shows as "Others" (4). */
private val APP_TYPES = mapOf(
    "com.tencent.mobileqq" to 1, "com.tencent.mm" to 2, "com.google.android.apps.messaging" to 3,
    "com.samsung.android.messaging" to 3, "com.facebook.katana" to 5, "com.twitter.android" to 6,
    "com.whatsapp" to 7, "com.skype.raider" to 8, "com.facebook.orca" to 9, "jp.naver.line.android" to 11,
    "com.linkedin.android" to 12, "com.instagram.android" to 13, "com.viber.voip" to 14,
    "com.snapchat.android" to 17, "com.google.android.gm" to 19, "com.google.android.youtube" to 23,
    "org.telegram.messenger" to 24, "com.microsoft.teams" to 29, "com.microsoft.office.outlook" to 30,
    "com.whatsapp.w4b" to 52, "com.Slack" to 60, "com.spotify.music" to 61, "com.shopee.id" to 68,
    "com.zhiliaoapp.musically" to 69, "com.ss.android.ugc.trill" to 69, "com.google.android.calendar" to 85,
    "com.discord" to 130, "us.zoom.videomeetings" to 162,
)
const val TYPE_OTHERS = 4

fun watchType(pkg: String) = APP_TYPES[pkg] ?: TYPE_OTHERS

/** Apps with their own watch icon are listed first in the picker, since they display best. */
fun hasWatchIcon(pkg: String) = pkg in APP_TYPES

/** "Others" has no app icon on the watch, so the app name goes into the text instead. */
fun watchText(pkg: String, app: String, title: String, text: String): String {
    val message = listOf(title, text).filter { it.isNotBlank() }.joinToString(": ")
    return if (watchType(pkg) == TYPE_OTHERS) "$app · $message" else message
}

/**
 * Sends one message at a time, a packet per watch ack, as the UTE SDK does. The backlog is capped so
 * a burst of chats doesn't keep the watch buzzing for minutes after.
 */
class MessagePusher(private val send: (ByteArray) -> Unit) {
    private val queue = ArrayDeque<List<ByteArray>>()
    private var current: List<ByteArray>? = null
    private var next = 0
    private var retries = 0
    private var sentAt = 0L

    fun push(type: Int, text: String, now: Long = System.currentTimeMillis()) {
        if (queue.size >= 5) queue.removeFirst()
        queue.addLast(Ute.messagePackets(type, text))
        if (current == null) startNext(now)
    }

    fun onReply(code: Int, now: Long = System.currentTimeMillis()) {
        val packets = current ?: return
        when {
            code == 0xFD -> startNext(now)
            code == 0xFF && retries < 2 -> {
                retries++
                next = 0
                sendNext(now)
            }
            code == 0xFF -> startNext(now)
            next <= packets.size -> sendNext(now)
        }
    }

    /** A watch that stops answering mid-message would otherwise block every later one. */
    fun expire(now: Long = System.currentTimeMillis()) {
        if (current != null && now - sentAt > 5_000) startNext(now)
    }

    fun clear() {
        queue.clear()
        current = null
    }

    private fun startNext(now: Long) {
        current = queue.removeFirstOrNull()
        next = 0
        retries = 0
        if (current != null) sendNext(now)
    }

    private fun sendNext(now: Long) {
        val packets = current ?: return
        send(packets.getOrElse(next) { Ute.MESSAGE_END })
        next++
        sentAt = now
    }
}

/** Device-local choices: which apps reach the watch is about this phone, so it stays out of the cloud. */
class WatchNotifyPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("watch_notify", Context.MODE_PRIVATE)
    var phoneNotifications: Boolean
        get() = prefs.getBoolean("phone", false)
        set(v) = prefs.edit().putBoolean("phone", v).apply()
    var heartRateAlerts: Boolean
        get() = prefs.getBoolean("hr", true)
        set(v) = prefs.edit().putBoolean("hr", v).apply()
    var apps: Set<String>
        get() = prefs.getStringSet("apps", emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet("apps", v).apply()
}

/** Lets the notification listener reach the watch link, which lives in the ViewModel. */
object WatchInbox {
    @Volatile var sink: ((type: Int, text: String) -> Unit)? = null
}
