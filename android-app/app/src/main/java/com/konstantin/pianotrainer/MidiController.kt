package com.konstantin.pianotrainer

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

data class MidiEndpoint(val info: MidiDeviceInfo, val label: String)

internal sealed interface IncomingMidiEvent {
    data class Note(val pitch: Int, val velocity: Int, val pressed: Boolean) : IncomingMidiEvent
    data class SustainPedal(val pressed: Boolean) : IncomingMidiEvent
}

/**
 * Android may batch several MIDI messages into one MidiReceiver callback.
 * Decode the complete byte range; reading just its first message loses Note Off
 * events and leaves a key incorrectly marked as held in the practice engine.
 */
internal fun decodeMidiMessages(message: ByteArray, offset: Int, count: Int): List<IncomingMidiEvent> {
    val events = mutableListOf<IncomingMidiEvent>()
    var cursor = offset.coerceIn(0, message.size)
    val end = (offset + count).coerceIn(cursor, message.size)
    var runningStatus = -1
    while (cursor < end) {
        val first = message[cursor].toInt() and 0xFF
        val status = if (first >= 0x80) {
            cursor++
            if (first >= 0xF8) continue // realtime messages have no data bytes
            if (first >= 0xF0) {
                runningStatus = -1
                continue // system messages are irrelevant to note practice
            }
            runningStatus = first
            first
        } else {
            if (runningStatus < 0) break
            runningStatus
        }
        val command = status and 0xF0
        val dataCount = if (command == 0xC0 || command == 0xD0) 1 else 2
        if (cursor + dataCount > end) break
        val data1 = message[cursor++].toInt() and 0x7F
        val data2 = if (dataCount == 2) message[cursor++].toInt() and 0x7F else 0
        when {
            command == 0x90 && data2 > 0 -> events += IncomingMidiEvent.Note(data1, data2, pressed = true)
            command == 0x80 || (command == 0x90 && data2 == 0) -> events += IncomingMidiEvent.Note(data1, data2, pressed = false)
            command == 0xB0 && data1 == 64 -> events += IncomingMidiEvent.SustainPedal(data2 >= 64)
        }
    }
    return events
}

class MidiController(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanner = bluetoothAdapter?.bluetoothLeScanner
    private var device: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null
    private var inputPort: MidiInputPort? = null
    private var scanCallback: ScanCallback? = null

    var onStatus: (String) -> Unit = {}
    var onBluetoothDevice: (android.bluetooth.BluetoothDevice) -> Unit = {}
    var onNote: (pitch: Int, velocity: Int, pressed: Boolean) -> Unit = { _, _, _ -> }

    private val receiver = object : MidiReceiver() {
        override fun onSend(message: ByteArray, offset: Int, count: Int, timestamp: Long) {
            decodeMidiMessages(message, offset, count).forEach { event ->
                when (event) {
                    is IncomingMidiEvent.Note -> {
                        val text = if (event.pressed) {
                            "Получена нота ${event.pitch} (velocity ${event.velocity})"
                        } else {
                            "Отпущена нота ${event.pitch}"
                        }
                        mainHandler.post { onStatus(text); onNote(event.pitch, event.velocity, event.pressed) }
                    }
                    is IncomingMidiEvent.SustainPedal -> {
                        mainHandler.post { onStatus(if (event.pressed) "Педаль нажата" else "Педаль отпущена") }
                    }
                }
            }
        }
    }

    fun systemDevices(): List<MidiEndpoint> = midiManager.devices.map { info ->
        val properties = info.properties
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
            ?: "MIDI-устройство ${info.id}"
        MidiEndpoint(info, name)
    }

    fun open(endpoint: MidiEndpoint) {
        closeConnection()
        onStatus("Подключение к ${endpoint.label}…")
        midiManager.openDevice(endpoint.info, { opened ->
            if (opened == null) {
                onStatus("Не удалось открыть ${endpoint.label}")
            } else {
                attach(opened, endpoint.label)
            }
        }, mainHandler)
    }

    fun scanBluetooth() {
        val activeScanner = scanner
        if (activeScanner == null || bluetoothAdapter?.isEnabled != true) {
            onStatus("Включите Bluetooth на планшете")
            return
        }
        stopScan()
        onStatus("Поиск Bluetooth MIDI…")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_BLE_UUID)).build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onBluetoothDevice(result.device)
                onStatus("Найдено: ${result.device.name ?: "Bluetooth MIDI"}")
            }

            override fun onScanFailed(errorCode: Int) {
                onStatus("Ошибка поиска Bluetooth MIDI: $errorCode")
            }
        }
        activeScanner.startScan(listOf(filter), ScanSettings.Builder().build(), scanCallback)
    }

    fun openBluetooth(bluetoothDevice: android.bluetooth.BluetoothDevice) {
        stopScan()
        closeConnection()
        onStatus("Подключение к ${bluetoothDevice.name ?: "FP-30"}…")
        midiManager.openBluetoothDevice(bluetoothDevice, { opened ->
            if (opened == null) onStatus("Не удалось открыть Bluetooth MIDI")
            else attach(opened, bluetoothDevice.name ?: "Bluetooth MIDI")
        }, mainHandler)
    }

    fun stopScan() {
        scanCallback?.let { callback -> runCatching { scanner?.stopScan(callback) } }
        scanCallback = null
    }

    fun closeConnection() {
        outputPort?.close()
        outputPort = null
        inputPort?.close()
        inputPort = null
        device?.close()
        device = null
    }

    fun close() {
        stopScan()
        closeConnection()
    }

    private fun attach(opened: MidiDevice, name: String) {
        device = opened
        outputPort = opened.openOutputPort(0)
        inputPort = opened.openInputPort(0)
        if (outputPort == null) {
            onStatus("$name не имеет входящего MIDI-порта")
            return
        }
        outputPort?.connect(receiver)
        onStatus("Подключено: $name. Нажмите клавишу на FP-30.")
    }

    fun send(message: ByteArray) {
        val port = inputPort ?: run {
            onStatus("Для воспроизведения подключите MIDI-устройство")
            return
        }
        runCatching { port.send(message, 0, message.size) }
            .onFailure { onStatus("Ошибка отправки MIDI: ${it.message}") }
    }

    private companion object {
        val MIDI_BLE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    }
}
