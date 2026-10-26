package com.konstantin.pianotrainer

import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeRangeNavigationTest {
    private val groups = (1..5).map { measure ->
        ExpectedGroup("note-$measure", (measure - 1) * 480L, emptySet(), setOf(60 + measure), measure)
    }

    @Test
    fun selectedRangeStartsAndRepeatsAtItsOwnBounds() {
        val practice = WaitingPractice(groups, fromTick = 480, toTick = 1439)

        assertEquals(480L, practice.current().currentTick)
        assertEquals(2, practice.current().total)
        assertEquals(960L, practice.notePressed(62, 0).currentTick)
        assertEquals(480L, practice.notePressed(63, 100).currentTick)
        assertEquals(0, practice.current().completed)
    }

    @Test
    fun manualNavigationCanCrossBothSelectedBoundsWithoutChangingTheLoop() {
        val practice = WaitingPractice(groups, fromTick = 480, toTick = 1439)

        assertEquals(0L, practice.seekToMeasure(1)!!.currentTick)
        assertEquals(1440L, practice.seekToMeasure(4)!!.currentTick)
        assertEquals(1920L, practice.seekToMeasure(5)!!.currentTick)
        assertEquals(2, practice.current().total)

        // Playing outside the upper bound returns to the selected loop.
        assertEquals(480L, practice.notePressed(65, 0).currentTick)
        assertEquals(960L, practice.notePressed(62, 100).currentTick)
        assertEquals(480L, practice.notePressed(63, 200).currentTick)
    }

    @Test
    fun rangeCanBeExpandedAndNarrowedAtTheNavigatedMeasure() {
        val original = WaitingPractice(groups, fromTick = 480, toTick = 1439)
        val cursor = original.seekToMeasure(4)!!.currentTick!!
        val expanded = WaitingPractice(groups, fromTick = 480, toTick = 1919)
        assertEquals(cursor, expanded.seekToTick(cursor)!!.currentTick)
        assertEquals(3, expanded.current().total)
        assertEquals(480L, expanded.notePressed(64, 0).currentTick)

        val narrowed = WaitingPractice(groups, fromTick = 960, toTick = 1439)
        assertEquals(1, narrowed.current().total)
        assertEquals(960L, narrowed.notePressed(63, 0).currentTick)
        assertEquals(0L, narrowed.seekToMeasure(1)!!.currentTick)
        assertEquals(1920L, narrowed.seekToMeasure(5)!!.currentTick)
    }
}
