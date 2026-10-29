package com.konstantin.pianotrainer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class MidiPlaybackRangeTest {
    private fun midi(): ByteArray {
        val track = ByteArrayOutputStream()
        fun event(delta: Int, vararg bytes: Int) {
            var value = delta
            val vlq = mutableListOf(value and 127)
            value = value ushr 7
            while (value > 0) { vlq.add(0, (value and 127) or 128); value = value ushr 7 }
            vlq.forEach(track::write); bytes.forEach(track::write)
        }
        event(0, 0xC0, 5)
        event(0, 0xB0, 64, 127)
        event(0, 0x90, 60, 100)
        event(240, 0xFF, 0x51, 3, 0x0F, 0x42, 0x40) // tempo becomes 1 second / quarter
        event(240, 0x80, 60, 0)
        event(0, 0x90, 62, 100)
        event(480, 0x80, 62, 0)
        event(0, 0x90, 64, 100)
        event(480, 0x80, 64, 0)
        event(0, 0xFF, 0x2F, 0)
        val bytes = track.toByteArray()
        return byteArrayOf(77, 84, 104, 100, 0, 0, 0, 6, 0, 0, 0, 1, 1, 0xE0.toByte(),
            77, 84, 114, 107, (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    @Test fun singleNoteIncludesItsDurationAndExcludesNextNoteOn() {
        val segment = midiPlaybackSegment(midi(), 480, 960)
        val attacks = segment.filter { it.message.size == 3 && it.message[0].toInt() and 0xF0 == 0x90 }
        assertEquals(listOf(62), attacks.map { it.message[1].toInt() })
        assertEquals(750L, segment.first().millis)
        assertEquals(1750L, segment.last().millis)
        assertTrue(segment.last().message.isEmpty())
    }

    @Test fun boundaryMarkersRespectTempoChangesAndPreserveLeadingTime() {
        val segment = midiPlaybackSegment(midi(), 120, 720)
        assertEquals(125L, segment.first().millis)
        assertEquals(1250L, segment.last().millis)
        assertFalse(segment.any { it.message.size == 3 && it.message[0].toInt() and 0xF0 == 0x90 && it.message[1].toInt() == 60 })
    }

    @Test fun startRestoresProgramAndPedalWithoutEarlierAttacks() {
        val segment = midiPlaybackSegment(midi(), 480, 960)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 5), segment[0].message)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 64, 127), segment[1].message)
        assertEquals(480L, segment[0].tick)
    }

    @Test fun openEndKeepsTheFinalNoteOffAndInvalidBoundsAreRejected() {
        val segment = midiPlaybackSegment(midi(), 960, null)
        assertEquals(1440L, segment.last().tick)
        assertEquals(0x80, segment.last().message[0].toInt() and 0xF0)
        assertThrows(IllegalArgumentException::class.java) { midiPlaybackSegment(midi(), 480, 480) }
    }
}
