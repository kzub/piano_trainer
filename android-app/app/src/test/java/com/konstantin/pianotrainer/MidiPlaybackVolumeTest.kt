package com.konstantin.pianotrainer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiPlaybackVolumeTest {
    @Test
    fun scalesNoteOnVelocity() {
        val scaled = scaleNoteVelocity(byteArrayOf(0x90.toByte(), 60, 80), 0.4f)

        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 32), scaled)
    }

    @Test
    fun keepsFullVelocityUntouched() {
        val scaled = scaleNoteVelocity(byteArrayOf(0x92.toByte(), 60, 80), 1f)

        assertArrayEquals(byteArrayOf(0x92.toByte(), 60, 80), scaled)
    }

    @Test
    fun neverSoftensANoteIntoSilence() {
        // Velocity 0 is a Note Off, so the quietest playback must still round up to 1.
        val scaled = scaleNoteVelocity(byteArrayOf(0x90.toByte(), 60, 1), 0.05f)

        assertEquals(1, scaled[2].toInt())
    }

    @Test
    fun leavesNonNoteOnMessagesAlone() {
        val noteOff = byteArrayOf(0x80.toByte(), 60, 64)
        val zeroVelocityNoteOff = byteArrayOf(0x90.toByte(), 60, 0)
        val channelVolume = byteArrayOf(0xB0.toByte(), 7, 100)
        val programChange = byteArrayOf(0xC0.toByte(), 0)

        assertArrayEquals(noteOff, scaleNoteVelocity(noteOff, 0.4f))
        assertArrayEquals(zeroVelocityNoteOff, scaleNoteVelocity(zeroVelocityNoteOff, 0.4f))
        assertArrayEquals(channelVolume, scaleNoteVelocity(channelVolume, 0.4f))
        assertArrayEquals(programChange, scaleNoteVelocity(programChange, 0.4f))
    }

    @Test
    fun doesNotMutateTheParsedMessage() {
        // Parsed events are reused when the playback loop restarts, so scaling must copy.
        val original = byteArrayOf(0x90.toByte(), 60, 80)

        scaleNoteVelocity(original, 0.25f)

        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 80), original)
    }
}
