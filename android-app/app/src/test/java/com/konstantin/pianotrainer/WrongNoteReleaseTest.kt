package com.konstantin.pianotrainer

import org.junit.Assert.assertEquals
import org.junit.Test

class WrongNoteReleaseTest {
    @Test
    fun wrongMarkerOnlyRemainsWhileWrongPitchIsHeld() {
        val practice = WaitingPractice(
            listOf(ExpectedGroup(id = "first", tick = 0, leftPitches = setOf(61), rightPitches = emptySet(), measure = 1)),
        )

        assertEquals(setOf(60), practice.notePressed(60, nowMillis = 0).wrong)
        assertEquals(emptySet<Int>(), practice.noteReleased(60).wrong)
    }

    @Test
    fun releasingCorrectPitchClearsItsAcceptedHighlight() {
        val practice = WaitingPractice(
            listOf(ExpectedGroup(id = "first", tick = 0, leftPitches = setOf(61, 65), rightPitches = emptySet(), measure = 1)),
        )

        assertEquals(setOf(61), practice.notePressed(61, nowMillis = 0).accepted)
        val released = practice.noteReleased(61)
        assertEquals(emptySet<Int>(), released.accepted)
        assertEquals(emptySet<Int>(), released.held)
    }
}
