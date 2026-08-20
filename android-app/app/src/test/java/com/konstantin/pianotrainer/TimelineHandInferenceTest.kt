package com.konstantin.pianotrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TimelineHandInferenceTest {
    @Test
    fun melodyCrossingBelowMiddleCStaysInRightHand() {
        val melody = MidiSource(track = 0, channel = 0)
        val bass = MidiSource(track = 1, channel = 2)
        val observations = buildList {
            repeat(384) { add(TimelineHandObservation(melody, "right")) }
            repeat(22) { add(TimelineHandObservation(melody, "left")) }
            repeat(510) { add(TimelineHandObservation(bass, "left")) }
            repeat(2) { add(TimelineHandObservation(bass, "right")) }
        }

        val inferred = inferDominantTimelineHands(observations)

        assertEquals("right", inferred[melody])
        assertEquals("left", inferred[bass])
    }

    @Test
    fun ambiguousSourceKeepsPerNoteHandAssignments() {
        val source = MidiSource(track = 0, channel = 1)
        val observations = buildList {
            repeat(8) { add(TimelineHandObservation(source, "right")) }
            repeat(4) { add(TimelineHandObservation(source, "left")) }
        }

        assertFalse(source in inferDominantTimelineHands(observations))
    }

    @Test
    fun repeatedPitchUsesItsOccurrenceInsideMeasure() {
        fun group(tick: Long, pitch: Int) = ExpectedGroup(
            id = "tick-$tick",
            tick = tick,
            leftPitches = emptySet(),
            rightPitches = setOf(pitch),
        )
        val groups = listOf(
            group(28_560, 69), // A4 tied across the barline
            group(30_240, 62), // first D4
            group(30_360, 64), // E4
            group(30_480, 62), // second D4
            group(30_600, 60), // C4
        )

        assertEquals(0, pitchOccurrencesBefore(groups, 30_240, 1_920)[62])
        assertEquals(0, pitchOccurrencesBefore(groups, 30_360, 1_920)[64])
        assertEquals(1, pitchOccurrencesBefore(groups, 30_480, 1_920)[62])
    }

    @Test
    fun waitingPracticeLoopsBackToTheFirstGroupAfterTheLastOne() {
        val groups = listOf(
            ExpectedGroup("first", 120, emptySet(), setOf(60)),
            ExpectedGroup("last", 240, emptySet(), setOf(62)),
        )
        val practice = WaitingPractice(groups)

        practice.notePressed(60, 0)
        val restarted = practice.notePressed(62, 100)

        assertEquals(0, restarted.completed)
        assertEquals(setOf(60), restarted.expected)
        assertEquals(120L, restarted.currentTick)
        assertFalse(restarted.finished)
    }

    @Test
    fun releasedWrongNotesDoNotAccumulateAcrossAttemptWindows() {
        val practice = WaitingPractice(
            listOf(ExpectedGroup("expected", 0, emptySet(), setOf(60))),
            attemptWindowMillis = 250,
        )

        val pressed = practice.notePressed(61, 0)
        assertEquals(setOf(61), pressed.wrong)
        val releasedImmediately = practice.noteReleased(61)
        assertEquals(emptySet<Int>(), releasedImmediately.wrong)
        val cleared = practice.expireAttemptWindow(250)

        assertEquals(null, cleared)

        practice.notePressed(61, 300)
        val held = practice.expireAttemptWindow(550)
        assertEquals(setOf(61), held!!.wrong)
        assertEquals(emptySet<Int>(), practice.noteReleased(61).wrong)
        assertEquals(null, practice.expireAttemptWindow(800))
    }

    @Test
    fun chordRequiresEveryCorrectKeyToStillBeHeld() {
        val practice = WaitingPractice(
            listOf(
                ExpectedGroup("chord", 0, emptySet(), setOf(60, 64)),
                ExpectedGroup("next", 240, emptySet(), setOf(65)),
            ),
        )

        val pressed = practice.notePressed(60, 0)
        assertEquals(setOf(60), pressed.held)
        assertEquals(setOf(60), pressed.accepted)

        val released = practice.noteReleased(60)
        assertEquals(emptySet<Int>(), released.held)
        assertEquals(emptySet<Int>(), released.accepted)

        val secondKey = practice.notePressed(64, 100)
        assertEquals(0, secondKey.completed)
        assertEquals(setOf(60, 64), secondKey.expected)

        val completed = practice.notePressed(60, 150)
        assertEquals(1, completed.completed)
        assertEquals(setOf(65), completed.expected)
    }

    @Test
    fun chordKeysMustBePressedWithinTheAttemptWindow() {
        val practice = WaitingPractice(
            listOf(
                ExpectedGroup("chord", 0, emptySet(), setOf(60, 64)),
                ExpectedGroup("next", 240, emptySet(), setOf(65)),
            ),
            attemptWindowMillis = 250,
        )

        practice.notePressed(60, 0)
        val lateSecondKey = practice.notePressed(64, 300)

        assertEquals(0, lateSecondKey.completed)
        assertEquals(setOf(64), lateSecondKey.accepted)
    }

    @Test
    fun waitingPracticeCanSeekToTheFirstGroupInAMeasure() {
        val practice = WaitingPractice(
            listOf(
                ExpectedGroup("first", 0, emptySet(), setOf(60), measure = 1),
                ExpectedGroup("second", 240, emptySet(), setOf(62), measure = 2),
                ExpectedGroup("third", 360, emptySet(), setOf(64), measure = 2),
            ),
        )

        val sought = practice.seekToMeasure(2)

        assertEquals(1, sought!!.completed)
        assertEquals(setOf(62), sought.expected)
        assertEquals(240L, sought.currentTick)
    }
}
