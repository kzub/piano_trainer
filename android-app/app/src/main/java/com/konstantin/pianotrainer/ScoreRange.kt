package com.konstantin.pianotrainer

/** Inclusive onset selection; MIDI playback uses an exclusive time boundary. */
internal fun selectionEndTick(groups: List<ExpectedGroup>, toTick: Long?, exactNote: Boolean): Long? {
    if (toTick == null) return null
    if (!exactNote) return toTick + 1
    val last = groups.filter { it.tick == toTick }.maxOfOrNull { it.endTick } ?: return toTick + 1
    val next = groups.firstOrNull { it.tick > toTick }?.tick
    // Let the last chord sound, but never start the following group. At a tie or
    // overlapping voice, cut the held notes when the next onset would begin.
    return minOf(last, next ?: Long.MAX_VALUE).coerceAtLeast(toTick + 1)
}

internal fun sessionStartTick(cursor: Long?, from: Long?, to: Long?): Long {
    val start = cursor ?: return from ?: 0L
    return if (start >= (from ?: 0L) && start <= (to ?: Long.MAX_VALUE)) start else from ?: 0L
}
