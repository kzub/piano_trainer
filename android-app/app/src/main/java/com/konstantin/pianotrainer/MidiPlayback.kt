package com.konstantin.pianotrainer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

data class MidiPlaybackState(
    val tick: Long,
    val activePitches: Set<Int>,
)

class MidiPlayback(private val controller: MidiController) {
    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = mutableListOf<Runnable>()
    private val activeNotes = linkedSetOf<Int>()

    fun play(
        midi: ByteArray,
        fromTick: Long = 0L,
        untilTick: Long? = null,
        onProgress: (MidiPlaybackState) -> Unit,
        onFinished: () -> Unit,
    ) {
        stop()
        val allEvents = MidiFile.parse(midi)
        val events = allEvents.filter { it.tick >= fromTick && (untilTick == null || it.tick < untilTick) }
        require(events.isNotEmpty()) { "В выбранном интервале нет MIDI-событий" }
        val startMillis = events.first().millis
        val startedAt = SystemClock.uptimeMillis()
        events.forEach { event ->
            val task = Runnable {
                controller.send(event.message)
                updateActiveNotes(event.message)
            }
            scheduled += task
            handler.postDelayed(task, event.millis - startMillis)
        }
        val duration = (events.maxOfOrNull { it.millis } ?: startMillis) - startMillis
        val ticker = object : Runnable {
            override fun run() {
                val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceIn(0L, duration)
                onProgress(MidiPlaybackState(tickAt(events, elapsed + startMillis), activePitches()))
                if (elapsed < duration) handler.postDelayed(this, 50L)
            }
        }
        scheduled += ticker
        handler.post(ticker)
        val finalTask = Runnable {
            scheduled.forEach(handler::removeCallbacks)
            scheduled.clear()
            activeNotes.clear()
            repeat(16) { channel -> controller.send(byteArrayOf((0xB0 + channel).toByte(), 123, 0)) }
            onFinished()
        }
        scheduled += finalTask
        handler.postDelayed(finalTask, duration + 100L)
    }

    fun stop() {
        scheduled.forEach(handler::removeCallbacks)
        scheduled.clear()
        activeNotes.clear()
        // All notes off on every MIDI channel, so stopping never leaves sustained notes.
        repeat(16) { channel -> controller.send(byteArrayOf((0xB0 + channel).toByte(), 123, 0)) }
    }

    private fun updateActiveNotes(message: ByteArray) {
        if (message.size < 3) return
        val status = message[0].toInt() and 0xFF
        val command = status and 0xF0
        val channel = status and 0x0F
        val pitch = message[1].toInt() and 0x7F
        val key = channel * 128 + pitch
        when {
            command == 0x90 && (message[2].toInt() and 0x7F) > 0 -> activeNotes += key
            command == 0x80 || command == 0x90 -> activeNotes -= key
            command == 0xB0 && pitch == 123 -> activeNotes.removeAll { it / 128 == channel }
        }
    }

    private fun activePitches(): Set<Int> = activeNotes.mapTo(linkedSetOf()) { it % 128 }

    private fun tickAt(events: List<TimedMidiEvent>, millis: Long): Long {
        if (events.isEmpty()) return 0L
        val previousIndex = events.indexOfLast { it.millis <= millis }.coerceAtLeast(0)
        val previous = events[previousIndex]
        val next = events.drop(previousIndex + 1).firstOrNull { it.millis > previous.millis } ?: return previous.tick
        val fraction = (millis - previous.millis).toDouble() / (next.millis - previous.millis)
        return previous.tick + ((next.tick - previous.tick) * fraction).toLong()
    }
}

data class TimedMidiEvent(val millis: Long, val tick: Long, val message: ByteArray)

private object MidiFile {
    private data class RawEvent(val tick: Long, val message: ByteArray?, val tempo: Int?)

    fun parse(data: ByteArray): List<TimedMidiEvent> {
        require(data.size >= 14 && data.copyOfRange(0, 4).contentEquals("MThd".encodeToByteArray())) { "Некорректный MIDI" }
        require(data.copyOfRange(4, 8).contentEquals(byteArrayOf(0, 0, 0, 6))) { "Некорректный заголовок MIDI" }
        val tracks = u16(data, 10)
        val ppq = u16(data, 12)
        require(ppq and 0x8000 == 0) { "SMPTE MIDI пока не поддерживается" }
        var offset = 14
        val raw = mutableListOf<RawEvent>()
        repeat(tracks) {
            require(data.copyOfRange(offset, offset + 4).contentEquals("MTrk".encodeToByteArray())) { "Некорректная MIDI-дорожка" }
            val end = offset + 8 + u32(data, offset + 4)
            var cursor = offset + 8
            var tick = 0L
            var runningStatus = -1
            while (cursor < end) {
                val (delta, afterDelta) = vlq(data, cursor)
                tick += delta
                cursor = afterDelta
                var status = data[cursor].toInt() and 0xFF
                if (status < 0x80) {
                    require(runningStatus >= 0) { "Некорректный running status" }
                    status = runningStatus
                } else {
                    cursor++
                    if (status < 0xF0) runningStatus = status
                }
                when (status) {
                    0xFF -> {
                        val type = data[cursor++].toInt() and 0xFF
                        val (length, afterLength) = vlq(data, cursor)
                        cursor = afterLength
                        if (type == 0x51 && length == 3L) {
                            raw += RawEvent(tick, null, ((data[cursor].toInt() and 0xFF) shl 16) or ((data[cursor + 1].toInt() and 0xFF) shl 8) or (data[cursor + 2].toInt() and 0xFF))
                        }
                        cursor += length.toInt()
                    }
                    0xF0, 0xF7 -> {
                        val (length, afterLength) = vlq(data, cursor)
                        cursor = afterLength + length.toInt()
                    }
                    else -> {
                        val command = status and 0xF0
                        val dataLength = if (command == 0xC0 || command == 0xD0) 1 else 2
                        val first = data[cursor++]
                        val message = if (dataLength == 1) byteArrayOf(status.toByte(), first) else byteArrayOf(status.toByte(), first, data[cursor++])
                        raw += RawEvent(tick, message, null)
                    }
                }
            }
            offset = end
        }
        var tempo = 500_000
        var previousTick = 0L
        var micros = 0.0
        return raw.sortedBy { it.tick }.mapNotNull { event ->
            micros += (event.tick - previousTick) * tempo.toDouble() / ppq
            previousTick = event.tick
            if (event.tempo != null) {
                tempo = event.tempo
                null
            } else event.message?.let { TimedMidiEvent((micros / 1000.0).toLong(), event.tick, it) }
        }
    }

    private fun u16(data: ByteArray, offset: Int) = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    private fun u32(data: ByteArray, offset: Int) = ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
    private fun vlq(data: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var cursor = start
        while (true) {
            val byte = data[cursor++].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) break
        }
        return value to cursor
    }
}
