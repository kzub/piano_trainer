package com.konstantin.pianotrainer

data class ExpectedGroup(
    val id: String,
    val tick: Long,
    val leftPitches: Set<Int>,
    val rightPitches: Set<Int>,
    val measure: Int? = null,
    val leftScoreNoteIds: Set<String> = emptySet(),
    val rightScoreNoteIds: Set<String> = emptySet(),
) {
    val pitches: Set<Int> = leftPitches + rightPitches
    val scoreNoteIds: Set<String> = leftScoreNoteIds + rightScoreNoteIds
}

internal data class MidiSource(val track: Int, val channel: Int)

internal data class TimelineHandObservation(
    val source: MidiSource,
    val declaredHand: String,
)

/**
 * Timeline-only mappings initially classify notes around middle C. When one MIDI
 * source is overwhelmingly one hand, keep the whole source in that hand so a
 * melody crossing C4 does not disappear from single-hand practice.
 */
internal fun inferDominantTimelineHands(
    observations: List<TimelineHandObservation>,
    minimumDominance: Double = 0.90,
): Map<MidiSource, String> = observations
    .filter { it.declaredHand == "left" || it.declaredHand == "right" }
    .groupBy(TimelineHandObservation::source)
    .mapNotNull { (source, sourceObservations) ->
        val counts = sourceObservations.groupingBy(TimelineHandObservation::declaredHand).eachCount()
        val dominant = counts.maxByOrNull { it.value } ?: return@mapNotNull null
        val dominance = dominant.value.toDouble() / sourceObservations.size
        if (dominance >= minimumDominance) source to dominant.key else null
    }
    .toMap()

/** Zero-based occurrence of each current pitch inside its measure. */
internal fun pitchOccurrencesBefore(
    groups: List<ExpectedGroup>,
    currentTick: Long,
    measureTicks: Long,
): Map<Int, Int> {
    val measureStart = currentTick / measureTicks * measureTicks
    val previousCounts = groups.asSequence()
        .filter { it.tick >= measureStart && it.tick < currentTick }
        .flatMap { it.pitches.asSequence() }
        .groupingBy { it }
        .eachCount()
    val currentPitches = groups.asSequence()
        .filter { it.tick == currentTick }
        .flatMap { it.pitches.asSequence() }
        .toSet()
    return currentPitches.associateWith { pitch -> previousCounts[pitch] ?: 0 }
}

data class PracticeState(
    val completed: Int,
    val total: Int,
    val expected: Set<Int>,
    val expectedLeft: Set<Int> = emptySet(),
    val expectedRight: Set<Int> = emptySet(),
    val accepted: Set<Int> = emptySet(),
    val attempted: Set<Int> = emptySet(),
    val wrong: Set<Int> = emptySet(),
    val finished: Boolean = false,
    val currentTick: Long? = null,
    val correctGroups: Int = 0,
    val missedGroups: Int = 0,
)

class WaitingPractice(
    private val groups: List<ExpectedGroup>,
    private val attemptWindowMillis: Long = 250L,
) {
    private var index = 0
    private val accepted = linkedSetOf<Int>()
    private val attempted = linkedSetOf<Int>()
    private val held = linkedSetOf<Int>()
    private var attemptStartedAt: Long? = null

    fun current(): PracticeState = state()

    fun notePressed(pitch: Int, nowMillis: Long): PracticeState {
        if (index >= groups.size) return state()
        // Bluetooth/USB transports may repeat Note On while a key is held.
        // A repeated message must never advance a following same-pitch group.
        if (!held.add(pitch)) return state()
        expireAttemptWindow(nowMillis)
        if (attemptStartedAt == null) attemptStartedAt = nowMillis
        attempted += pitch
        val expected = groups[index].pitches
        if (pitch in expected) accepted += pitch
        if (accepted.containsAll(expected)) {
            index++
            accepted.clear()
            attempted.clear()
            attemptStartedAt = null
            // Waiting practice is a loop, not a terminal exercise. This also
            // naturally restarts a user-selected segment at its own first note.
            if (index == groups.size) index = 0
        }
        return state()
    }

    fun noteReleased(pitch: Int): PracticeState {
        held -= pitch
        // Wrong notes are visualized only for as long as their keys are held.
        // Correct notes remain accepted so a chord may still be entered one key
        // at a time within the attempt window.
        if (pitch !in groups.getOrNull(index)?.pitches.orEmpty()) {
            attempted -= pitch
            if (attempted.isEmpty()) attemptStartedAt = null
        }
        return state()
    }

    fun expireAttemptWindow(nowMillis: Long): PracticeState? {
        val startedAt = attemptStartedAt ?: return null
        if (nowMillis - startedAt < attemptWindowMillis) return null
        // Keep an error marker only while that key is still down. The old
        // implementation kept every key that had ever appeared in an attempt
        // until *all* keys were released, so normal playing filled the score
        // with an ever-growing column of red markers.
        attempted.retainAll(held)
        attemptStartedAt = if (attempted.isEmpty()) null else nowMillis
        return state()
    }

    private fun state(): PracticeState {
        val finished = index >= groups.size
        val group = groups.getOrNull(index)
        val expected = group?.pitches.orEmpty()
        return PracticeState(
            completed = index,
            total = groups.size,
            expected = expected,
            expectedLeft = group?.leftPitches.orEmpty(),
            expectedRight = group?.rightPitches.orEmpty(),
            accepted = accepted.toSet(),
            attempted = attempted.toSet(),
            wrong = attempted.minus(expected),
            finished = finished,
            currentTick = if (finished) null else groups[index].tick,
        )
    }
}

/**
 * Non-blocking practice for Play: playback advances independently, while incoming
 * keyboard events are compared with the current MIDI group inside a tolerance window.
 */
class ContinuousFeedback(
    private val groups: List<ExpectedGroup>,
    private val toleranceTicks: Long,
) {
    private var index = 0
    private val accepted = linkedSetOf<Int>()
    private val attempted = linkedSetOf<Int>()
    private val wrong = linkedSetOf<Int>()
    private var correctGroups = 0
    private var missedGroups = 0

    fun playbackTick(tick: Long): PracticeState {
        advancePast(tick)
        return state()
    }

    fun notePressed(pitch: Int, tick: Long): PracticeState {
        advancePast(tick)
        val group = groups.getOrNull(index) ?: return state()
        if (kotlin.math.abs(tick - group.tick) <= toleranceTicks) {
            attempted += pitch
            if (pitch in group.pitches) accepted += pitch else wrong += pitch
        } else {
            attempted += pitch
            wrong += pitch
        }
        return state()
    }

    private fun advancePast(tick: Long) {
        while (index < groups.size && tick > groups[index].tick + toleranceTicks) {
            if (accepted.containsAll(groups[index].pitches)) correctGroups++ else missedGroups++
            index++
            accepted.clear()
            attempted.clear()
            wrong.clear()
        }
    }

    private fun state(): PracticeState {
        val group = groups.getOrNull(index)
        return PracticeState(
            completed = index,
            total = groups.size,
            expected = group?.pitches.orEmpty(),
            expectedLeft = group?.leftPitches.orEmpty(),
            expectedRight = group?.rightPitches.orEmpty(),
            accepted = accepted.toSet(),
            attempted = attempted.toSet(),
            wrong = wrong.toSet(),
            finished = group == null,
            currentTick = group?.tick,
            correctGroups = correctGroups,
            missedGroups = missedGroups,
        )
    }
}
