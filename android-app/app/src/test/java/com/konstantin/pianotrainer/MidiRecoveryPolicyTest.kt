package com.konstantin.pianotrainer

import org.junit.Assert.*
import org.junit.Test

class MidiRecoveryPolicyTest {
    @Test fun `failed or disconnected connection retries indefinitely with a cooldown`() {
        val policy = MidiRecoveryPolicy()
        var now = 0L
        repeat(100) {
            assertTrue(policy.canAttempt(now))
            val attempt = policy.begin(now)
            assertTrue(policy.isCurrent(attempt))
            assertFalse(policy.canAttempt(now))
            policy.invalidate(now)
            assertFalse(policy.isCurrent(attempt))
            assertFalse(policy.canAttempt(now + 4_999))
            now += 5_000
        }
        assertTrue(policy.canAttempt(now))
    }

    @Test fun `hung open expires and its late callback cannot replace a new connection`() {
        val policy = MidiRecoveryPolicy()
        val old = policy.begin(0)
        assertFalse(policy.hasTimedOut(19_999))
        assertTrue(policy.hasTimedOut(20_000))
        policy.invalidate(20_000)
        val current = policy.begin(25_000)
        assertFalse(policy.isCurrent(old))
        assertTrue(policy.isCurrent(current))
        policy.connected()
        assertFalse(policy.connecting)
        assertFalse(policy.hasTimedOut(100_000))
        assertFalse(policy.isCurrent(current))
    }

    @Test fun `manual switch invalidates the previous open callback`() {
        val policy = MidiRecoveryPolicy()
        val first = policy.begin(0)
        val second = policy.begin(1)
        assertFalse(policy.isCurrent(first))
        assertTrue(policy.isCurrent(second))
    }

    @Test fun `known Bluetooth address survives migration and name permits a changed address`() {
        assertTrue(matchesKnownMidiBluetooth("old-address", null, setOf("old-address"), emptySet()))
        assertTrue(matchesKnownMidiBluetooth("new-address", "FP-30", setOf("old-address"), setOf("FP-30")))
        assertFalse(matchesKnownMidiBluetooth("unknown", "Other piano", setOf("old-address"), setOf("FP-30")))
        assertFalse(matchesKnownMidiBluetooth("unknown", "", emptySet(), setOf("")))
        assertFalse(matchesKnownMidiBluetooth("unknown", null, emptySet(), emptySet()))
    }
}
