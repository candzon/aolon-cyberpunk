package com.feri.aolon_cyberpunk

import com.feri.aolon_cyberpunk.ble.Ute
import com.feri.aolon_cyberpunk.notify.MessagePusher
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchMessageTest {
    private fun hex(b: ByteArray) = Ute.hex(b)

    @Test fun payloadIsTypeLengthUtf16() {
        assertEquals("07 04 00 48 00 69", hex(Ute.messagePayload(7, "Hi")))
    }

    @Test fun payloadDropsEmojiAndCapsAt160Bytes() {
        assertEquals("04 02 00 41", hex(Ute.messagePayload(4, "A😀")))
        val long = Ute.messagePayload(4, "x".repeat(200))
        assertEquals(162, long.size)
        assertEquals(160, long[1].toInt() and 0xff)
    }

    @Test fun packetsAreC5IndexedChunksOf18() {
        // 2 + 2 * 20 chars = 42 payload bytes: 18 + 18 + 6.
        val packets = Ute.messagePackets(7, "a".repeat(20))
        assertEquals(listOf(20, 20, 8), packets.map { it.size })
        assertEquals(listOf("C5 00", "C5 01", "C5 02"), packets.map { hex(it.copyOf(2)) })
    }

    @Test fun pusherSendsPacketPerAckThenEndThenNextMessage() {
        val sent = mutableListOf<String>()
        val pusher = MessagePusher { sent += hex(it.copyOf(2)) }
        pusher.push(7, "a".repeat(20), now = 0)
        pusher.push(4, "b", now = 0)
        assertEquals(listOf("C5 00"), sent)
        pusher.onReply(0, now = 1)
        pusher.onReply(1, now = 2)
        pusher.onReply(2, now = 3)
        assertEquals(listOf("C5 00", "C5 01", "C5 02", "C5 FD"), sent)
        pusher.onReply(0xFD, now = 4)
        assertEquals("C5 00", sent.last())
        assertEquals(5, sent.size)
    }

    @Test fun pusherResendsOnFfAndGivesUpOnSilence() {
        val sent = mutableListOf<String>()
        val pusher = MessagePusher { sent += hex(it.copyOf(2)) }
        pusher.push(7, "a".repeat(20), now = 0)
        pusher.onReply(0, now = 1)
        pusher.onReply(0xFF, now = 2)
        assertEquals(listOf("C5 00", "C5 01", "C5 00"), sent)
        pusher.push(4, "b", now = 3)
        pusher.expire(now = 10_000)
        assertEquals(4, sent.size)
        assertEquals("C5 00", sent.last())
    }
}
