package com.konstantin.pianotrainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-only manifest registration lets ADB test the full MIDI-to-score path. */
object DebugMidiInput {
    @Volatile
    var onNote: ((pitch: Int, velocity: Int, pressed: Boolean) -> Unit)? = null
}

class DebugMidiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        val pitch = intent.getIntExtra(EXTRA_PITCH, 60).coerceIn(0, 127)
        val velocity = intent.getIntExtra(EXTRA_VELOCITY, 100).coerceIn(0, 127)
        val pressed = intent.getBooleanExtra(EXTRA_PRESSED, true)
        DebugMidiInput.onNote?.invoke(pitch, velocity, pressed)
    }

    companion object {
        const val ACTION = "com.konstantin.pianotrainer.DEBUG_MIDI_NOTE"
        const val EXTRA_PITCH = "pitch"
        const val EXTRA_VELOCITY = "velocity"
        const val EXTRA_PRESSED = "pressed"
    }
}
