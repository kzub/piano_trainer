package com.konstantin.pianotrainer

data class ExpectedGroup(
    val id: String,
    val tick: Long,
    val leftPitches: Set<Int>,
    val rightPitches: Set<Int>,
) {
    val pitches: Set<Int> = leftPitches + rightPitches
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
        expireAttemptWindow(nowMillis)
        if (attemptStartedAt == null) attemptStartedAt = nowMillis
        held += pitch
        attempted += pitch
        val expected = groups[index].pitches
        if (pitch in expected) accepted += pitch
        if (accepted.containsAll(expected)) {
            index++
            accepted.clear()
            attempted.clear()
            attemptStartedAt = null
        }
        return state()
    }

    fun noteReleased(pitch: Int): PracticeState {
        held -= pitch
        return state()
    }

    fun expireAttemptWindow(nowMillis: Long): PracticeState? {
        val startedAt = attemptStartedAt ?: return null
        if (nowMillis - startedAt < attemptWindowMillis || held.any { it in attempted }) return null
        attempted.clear()
        attemptStartedAt = null
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
