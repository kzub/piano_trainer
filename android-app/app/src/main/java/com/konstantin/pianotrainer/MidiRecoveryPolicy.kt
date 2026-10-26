package com.konstantin.pianotrainer

/** Monotonic time and attempt tokens keep late Android callbacks from reviving old connections. */
internal class MidiRecoveryPolicy {
    private var generation = 0L
    private var startedAt: Long? = null
    private var retryAt = 0L

    val connecting: Boolean get() = startedAt != null

    fun begin(now: Long): Long {
        generation++
        startedAt = now
        return generation
    }

    fun isCurrent(attempt: Long): Boolean = connecting && generation == attempt

    fun canAttempt(now: Long): Boolean = !connecting && now >= retryAt

    fun hasTimedOut(now: Long): Boolean = startedAt?.let { now - it >= 20_000L } ?: false

    fun invalidate(now: Long) {
        generation++
        startedAt = null
        retryAt = now + 5_000L
    }

    fun connected() {
        startedAt = null
    }
}

internal fun matchesKnownMidiBluetooth(
    address: String,
    name: String?,
    knownAddresses: Set<String>,
    knownNames: Set<String>,
): Boolean = address in knownAddresses || (!name.isNullOrBlank() && name in knownNames)
