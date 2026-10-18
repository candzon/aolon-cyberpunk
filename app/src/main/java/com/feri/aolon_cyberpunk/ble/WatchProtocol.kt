package com.feri.aolon_cyberpunk.ble

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * UTE watch protocol as used by the Aolon / GloryFit family, recovered from com.yc.utesdk.
 * Commands are raw bytes with no framing: short commands go to 33f1 and replies arrive on 33f2;
 * 34f1/34f2 carry the larger payloads on newer firmware.
 */
object Ute {
    val SERVICE: UUID = uuid16(0x55ff)
    val WRITE: UUID = uuid16(0x33f1)
    val NOTIFY: UUID = uuid16(0x33f2)
    val BULK_SERVICE: UUID = uuid16(0x56ff)
    val BULK_WRITE: UUID = uuid16(0x34f1)
    val BULK_NOTIFY: UUID = uuid16(0x34f2)
    val CCCD: UUID = uuid16(0x2902)

    private fun uuid16(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    fun firmware() = bytes(0xA1)
    fun battery() = bytes(0xA2)
    fun syncTime(now: LocalDateTime = LocalDateTime.now()) = bytes(
        0xA3, now.year shr 8, now.year and 0xff, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second,
    )
    fun syncSteps() = bytes(0xB2, 0xFA)
    /** Sleep sync: EC 01 on watches with the Cywee sleep algorithm, else 31 01. The SDK no longer parses B3 FA replies. */
    fun syncSleep(cywee: Boolean) = if (cywee) bytes(0xEC, 0x01) else bytes(0x31, 0x01)
    fun heartRate(start: Boolean) = bytes(0xE5, if (start) 0x11 else 0x00)
    fun oxygen(start: Boolean) = bytes(0x34, if (start) 0x11 else 0x00)
    fun bloodPressure(start: Boolean) = bytes(0xC7, if (start) 0x11 else 0x00)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Closes a message after its last packet; the watch answers C5 FD once it has shown it. */
    val MESSAGE_END = bytes(0xC5, 0xFD)

    private val DROPPED = setOf(0x2005, 0x26C4, 0x2614, 0x200E, 0x200F)

    /**
     * Notification body as the UTE SDK builds it: [app type, byte count, UTF-16BE text], capped at
     * 160 text bytes. Control characters and emoji (outside the BMP) are dropped, as the SDK does.
     */
    fun messagePayload(type: Int, text: String): ByteArray {
        val clean = text.replace('\n', ' ').filter { c ->
            !c.isSurrogate() && c.code >= 32 && c.code !in 127..159 && c.code !in DROPPED
        }
        val body = clean.toByteArray(Charsets.UTF_16BE).let { it.copyOf(minOf(it.size, 160)) }
        return byteArrayOf(type.toByte(), body.size.toByte()) + body
    }

    /** C5 <index> + up to 18 payload bytes per packet; each packet waits for the watch's ack. */
    fun messagePackets(type: Int, text: String): List<ByteArray> =
        messagePayload(type, text).toList().chunked(18).mapIndexed { i, part ->
            byteArrayOf(0xC5.toByte(), i.toByte()) + part.toByteArray()
        }

    fun parse(data: ByteArray): WatchEvent? {
        if (data.isEmpty()) return null
        val u = IntArray(data.size) { data[it].toInt() and 0xff }
        return when (u[0]) {
            0xA1 -> if (u.size > 1 && u[1] != 0x01) WatchEvent.Firmware(ascii(data, 1)) else null
            0xA2 -> if (u.size > 1) WatchEvent.Battery(minOf(u[1], 100), if (u.size > 2) u[2] == 1 else null) else null
            0xA3 -> WatchEvent.TimeSynced
            0xC5 -> if (u.size >= 2) WatchEvent.MessageReply(u[1]) else null
            // B1 is pushed live as steps change; B2 replays stored hours after B2 FA.
            // Both share: [YYYY][MM][DD][HH][steps u16] then activity minutes we do not use yet.
            0xB1 -> if (u.size >= 8) stepHour(u) else null
            0xB2 -> if (u.size >= 8) stepHour(u) else WatchEvent.StepSyncDone
            // 31 01 [YYYY][MM][DD] opens a night, 31 02 ends the sync; 31 AC is the sleep alarm, ignored.
            0x31 -> when {
                u.size >= 6 && u[1] == 0x01 -> runCatching { LocalDate.of((u[2] shl 8) or u[3], u[4], u[5]) }.getOrNull()?.let(WatchEvent::SleepNight)
                u.size >= 2 && u[1] == 0x02 -> WatchEvent.SleepSyncDone
                else -> null
            }
            0x32 -> WatchEvent.SleepData(data.copyOfRange(1, data.size))
            // Cywee framing: EC 01 header as 31 01, EC 02 data after two bytes, EC 03 ends the sync.
            0xEC -> when {
                u.size >= 6 && u[1] == 0x01 -> runCatching { LocalDate.of((u[2] shl 8) or u[3], u[4], u[5]) }.getOrNull()?.let(WatchEvent::SleepNight)
                u.size >= 2 && u[1] == 0x02 -> WatchEvent.SleepData(data.copyOfRange(2, data.size))
                u.size >= 2 && u[1] == 0x03 -> WatchEvent.SleepSyncDone
                else -> null
            }
            0xE5 -> when {
                u.size == 2 && u[1] == 0x11 -> WatchEvent.Measuring(Metric.HeartRate, true)
                u.size == 2 && u[1] == 0x00 -> WatchEvent.Measuring(Metric.HeartRate, false)
                u.size >= 4 && u[3] in 30..230 -> WatchEvent.HeartRate(u[3])
                u.size >= 2 && u[1] == 0xFD -> WatchEvent.Measuring(Metric.HeartRate, false)
                else -> null
            }
            0x34 -> when {
                u.size >= 4 && (u[1] == 0x00 || u[1] == 0x11) && u[2] == 0 && u[3] in 70..100 -> WatchEvent.Oxygen(u[3])
                u.size == 2 && u[1] == 0x11 -> WatchEvent.Measuring(Metric.Oxygen, true)
                u.size == 2 && u[1] == 0x00 -> WatchEvent.Measuring(Metric.Oxygen, false)
                else -> null
            }
            0xC7 -> when {
                u.size >= 5 && u[1] == 0 && u[2] == 0 && u[3] in 60..250 -> WatchEvent.BloodPressure(u[3], u[4])
                u.size >= 2 && u[1] == 0x11 -> WatchEvent.Measuring(Metric.BloodPressure, true)
                u.size >= 2 && (u[1] == 0xFD || u[1] == 0xFF || u[1] == 0x06) -> WatchEvent.Measuring(Metric.BloodPressure, false)
                else -> null
            }
            else -> null
        }
    }

    /** 20-byte bitmap read from 34f1 holding function lists 8..13; list 8 is the last three bytes. */
    fun moreFeatures(data: ByteArray): WatchEvent.MoreFeatures? {
        if (data.size != 20) return null
        val list8 = (0..2).fold(0) { acc, i -> (acc shl 8) or (data[17 + i].toInt() and 0xff) }
        return WatchEvent.MoreFeatures(cyweeSleep = list8 and 0x20 != 0)
    }

    /** 20-byte bitmap read from 33f1; function list 1 is the last three bytes, big-endian. */
    fun features(data: ByteArray): WatchEvent.Features? {
        if (data.size != 20) return null
        fun list(from: Int) = (0..2).fold(0) { acc, i -> (acc shl 8) or (data[from + i].toInt() and 0xff) }
        // List 4 sits at bytes 8..10; its 0x40000 bit selects the 31 01 sleep sync.
        return WatchEvent.Features(bloodPressure = list(17) and 0x100000 != 0, sleep = list(8) and 0x40000 != 0)
    }

    private fun stepHour(u: IntArray): WatchEvent? {
        val date = runCatching { LocalDate.of((u[1] shl 8) or u[2], u[3], u[4]) }.getOrNull() ?: return null
        val hour = u[5].takeIf { it in 0..23 } ?: return null
        return WatchEvent.StepHour(date, hour, (u[6] shl 8) or u[7])
    }

    private fun ascii(data: ByteArray, from: Int): String {
        val text = data.drop(from).filter { it in 0x20..0x7e }.map { it.toInt().toChar() }.joinToString("")
        return text.ifBlank { hex(data) }
    }

    fun hex(data: ByteArray) = data.joinToString(" ") { "%02X".format(it) }

    /** Message packets carry chat text, so the log keeps only the header and the byte count. */
    fun loggable(data: ByteArray) =
        if (data.size > 2 && (data[0].toInt() and 0xff) == 0xC5) "${hex(data.copyOf(2))} <${data.size - 2} bytes hidden>" else hex(data)
}

enum class Metric { HeartRate, Oxygen, BloodPressure }

sealed interface WatchEvent {
    data class Battery(val percent: Int, val charging: Boolean?) : WatchEvent
    data class Firmware(val version: String) : WatchEvent
    data class Features(val bloodPressure: Boolean, val sleep: Boolean) : WatchEvent
    data class MoreFeatures(val cyweeSleep: Boolean) : WatchEvent
    data object TimeSynced : WatchEvent
    data class StepHour(val date: LocalDate, val hour: Int, val steps: Int) : WatchEvent
    data object StepSyncDone : WatchEvent
    data class SleepNight(val date: LocalDate) : WatchEvent
    class SleepData(val bytes: ByteArray) : WatchEvent
    data object SleepSyncDone : WatchEvent
    data class Measuring(val metric: Metric, val active: Boolean) : WatchEvent
    data class HeartRate(val bpm: Int) : WatchEvent
    /** Reply to a C5 message packet: FD shown, FF resend from the start, anything else next packet. */
    data class MessageReply(val code: Int) : WatchEvent
    data class Oxygen(val percent: Int) : WatchEvent
    data class BloodPressure(val systolic: Int, val diastolic: Int) : WatchEvent
}
