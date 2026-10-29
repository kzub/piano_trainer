package com.konstantin.pianotrainer

import org.junit.Assert.*
import org.junit.Test

class NoteRangeTest {
    private val groups = listOf(
        ExpectedGroup("a", 0, setOf(48), setOf(60), 1, endTick = 480),
        ExpectedGroup("b", 480, emptySet(), setOf(62), 1, endTick = 1200),
        ExpectedGroup("c", 960, setOf(50), setOf(64), 1, endTick = 1440),
        ExpectedGroup("d", 1920, emptySet(), setOf(65), 2, endTick = 2160),
    )

    @Test fun exactBoundsInsideOneMeasureIncludeTheLastChordAndLoop() {
        val engine = WaitingPractice(groups, fromTick = 480, toTick = 960)
        assertEquals(2, engine.current().total)
        assertEquals(480L, engine.current().currentTick)
        assertEquals(960L, engine.notePressed(62, 0).currentTick)
        assertEquals(960L, engine.notePressed(50, 100).currentTick)
        assertEquals(480L, engine.notePressed(64, 110).currentTick)
    }

    @Test fun aSingleChordLoopsOnlyAfterBothHandsAreAccepted() {
        val engine = WaitingPractice(groups, fromTick = 960, toTick = 960)
        assertEquals(1, engine.current().total)
        engine.notePressed(50, 0)
        assertEquals(setOf(50), engine.current().accepted)
        engine.notePressed(64, 10)
        assertEquals(960L, engine.current().currentTick)
        assertTrue(engine.current().accepted.isEmpty())
    }

    @Test fun noteEndUsesDurationButDoesNotReachTheNextOnset() {
        assertEquals(480L, selectionEndTick(groups, 0, true))
        assertEquals(960L, selectionEndTick(groups, 480, true)) // overlapping voice
        assertEquals(1440L, selectionEndTick(groups, 960, true)) // no extra rest
        assertEquals(2160L, selectionEndTick(groups, 1920, true)) // last score note
    }

    @Test fun wholeMeasureEndAndOpenBoundariesRemainSupported() {
        assertEquals(1920L, selectionEndTick(groups, 1919, false))
        assertNull(selectionEndTick(groups, null, true))
        assertEquals(480L, sessionStartTick(480, 480, 960))
        assertEquals(480L, sessionStartTick(0, 480, 960))
        assertEquals(480L, sessionStartTick(1920, 480, 960))
        assertEquals(960L, sessionStartTick(960, null, null))
    }

    @Test fun changingHandsPreservesExactTicksAndRejectsEmptySelection() {
        val left = groups.filter { it.leftPitches.isNotEmpty() }.map { it.copy(rightPitches = emptySet()) }
        val engine = WaitingPractice(left, fromTick = 480, toTick = 960)
        assertEquals(960L, engine.current().currentTick)
        assertEquals(setOf(50), engine.current().expected)
        assertThrows(IllegalArgumentException::class.java) { WaitingPractice(left, fromTick = 480, toTick = 480) }
    }
}
