package com.konstantin.pianotrainer

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiMessageDecoderTest {
    @Test
    fun decodesEveryNoteMessageInOneBatchedCallback() {
        val events = decodeMidiMessages(
            byteArrayOf(0x90.toByte(), 60, 100, 0x80.toByte(), 60, 0),
            offset = 0,
            count = 6,
        )

        assertEquals(
            listOf(IncomingMidiEvent.Note(60, 100, true), IncomingMidiEvent.Note(60, 0, false)),
            events,
        )
    }

    @Test
    fun decodesRunningStatusAndZeroVelocityNoteOff() {
        val events = decodeMidiMessages(byteArrayOf(0x90.toByte(), 60, 90, 61, 0), 0, 5)

        assertEquals(
            listOf(IncomingMidiEvent.Note(60, 90, true), IncomingMidiEvent.Note(61, 0, false)),
            events,
        )
    }
}
