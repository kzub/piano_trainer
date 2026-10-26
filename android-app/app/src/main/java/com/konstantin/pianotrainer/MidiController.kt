package com.konstantin.pianotrainer

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.IntentCompat
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.pm.PackageManager
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

enum class MidiConnectionState {
    DISCONNECTED,
    SEARCHING,
    CONNECTING,
    CONNECTED,
}

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
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val midiManager = appContext.getSystemService(MidiManager::class.java)
    private val bluetoothAdapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var device: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null
    private var inputPort: MidiInputPort? = null
    private var scanCallback: ScanCallback? = null
    private val recovery = MidiRecoveryPolicy()
    private var closed = false
    private var monitoring = false
    private var activeBluetoothAddress: String? = null
    private var pendingSystemId: Int? = null
    private var scanStartedAt = 0L
    private var scanRetryAt = 0L
    private val heldNotes = mutableSetOf<Int>()

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (monitoring) maintainConnection()
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id || pendingSystemId == info.id) {
                connectionLost()
            }
        }
    }

    private val bluetoothEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                        stopScan()
                        if (activeBluetoothAddress != null) connectionLost()
                    }
                    if (monitoring) maintainConnection()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val disconnected = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    // A piano may expose both audio and MIDI. Ignore classic audio disconnects.
                    val transport = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
                    } else BluetoothDevice.TRANSPORT_AUTO
                    if (disconnected?.address == activeBluetoothAddress && transport != BluetoothDevice.TRANSPORT_BREDR) {
                        connectionLost()
                    }
                }
            }
        }
    }

    private val maintenance = object : Runnable {
        override fun run() {
            if (closed || !monitoring) return
            maintainConnection()
            mainHandler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    init {
        midiManager.registerDeviceCallback(deviceCallback, mainHandler)
        ContextCompat.registerReceiver(
            appContext, bluetoothEvents,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }, ContextCompat.RECEIVER_EXPORTED,
        )
    }

    var onStatus: (String) -> Unit = {}
    var onConnectionState: (MidiConnectionState) -> Unit = {}
    var language: AppLanguage = AppLanguage.ENGLISH
    var onBluetoothDevice: (android.bluetooth.BluetoothDevice) -> Unit = {}
    var onNote: (pitch: Int, velocity: Int, pressed: Boolean) -> Unit = { _, _, _ -> }

    /** Sends a synthetic event through the same UI callback as a physical MIDI key. */
    fun injectDebugNote(pitch: Int, velocity: Int, pressed: Boolean) {
        val safePitch = pitch.coerceIn(0, 127)
        val safeVelocity = velocity.coerceIn(0, 127)
        mainHandler.post {
            onStatus(if (pressed) status("Test note $safePitch", "Тестовая нота $safePitch") else status("Test note $safePitch released", "Тестовая нота $safePitch отпущена"))
            deliverNote(safePitch, safeVelocity, pressed)
        }
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(message: ByteArray, offset: Int, count: Int, timestamp: Long) {
            decodeMidiMessages(message, offset, count).forEach { event ->
                when (event) {
                    is IncomingMidiEvent.Note -> {
                        val text = if (event.pressed) {
                            status("Received note ${event.pitch} (velocity ${event.velocity})", "Получена нота ${event.pitch} (velocity ${event.velocity})")
                        } else {
                            status("Released note ${event.pitch}", "Отпущена нота ${event.pitch}")
                        }
                        mainHandler.post { onStatus(text); deliverNote(event.pitch, event.velocity, event.pressed) }
                    }
                    is IncomingMidiEvent.SustainPedal -> {
                        mainHandler.post { onStatus(if (event.pressed) status("Pedal pressed", "Педаль нажата") else status("Pedal released", "Педаль отпущена")) }
                    }
                }
            }
        }
    }

    private fun deliverNote(pitch: Int, velocity: Int, pressed: Boolean) {
        if (pressed) heldNotes += pitch else heldNotes -= pitch
        onNote(pitch, velocity, pressed)
    }

    fun systemDevices(): List<MidiEndpoint> = midiManager.devices.map { info ->
        val properties = info.properties
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
            ?: status("MIDI device ${info.id}", "MIDI-устройство ${info.id}")
        MidiEndpoint(info, name)
    }

    fun open(endpoint: MidiEndpoint) {
        if (closed) return
        stopScan()
        releaseConnection()
        val attempt = recovery.begin(SystemClock.elapsedRealtime())
        pendingSystemId = endpoint.info.id
        setConnectionState(MidiConnectionState.CONNECTING)
        onStatus(status("Connecting to ${endpoint.label}…", "Подключение к ${endpoint.label}…"))
        runCatching {
            midiManager.openDevice(endpoint.info, { opened ->
                completeOpen(attempt, opened, endpoint.label) { rememberSystemEndpoint(endpoint) }
            }, mainHandler)
        }.onFailure { connectionLost() }
    }

    fun scanBluetooth() {
        if (closed || device != null || recovery.connecting || scanCallback != null || SystemClock.elapsedRealtime() < scanRetryAt) return
        if (!hasBluetoothPermissions()) {
            setConnectionState(MidiConnectionState.DISCONNECTED)
            onStatus(status("Allow Nearby devices to search for Bluetooth MIDI", "Разрешите доступ к устройствам поблизости для поиска Bluetooth MIDI"))
            return
        }
        val activeScanner = runCatching {
            if (bluetoothAdapter?.isEnabled == true) bluetoothAdapter.bluetoothLeScanner else null
        }.getOrNull()
        if (activeScanner == null) {
            setConnectionState(MidiConnectionState.DISCONNECTED)
            onStatus(status("Turn on Bluetooth on this tablet", "Включите Bluetooth на планшете"))
            return
        }
        scanner = activeScanner
        setConnectionState(MidiConnectionState.SEARCHING)
        onStatus(status("Scanning for Bluetooth MIDI…", "Поиск Bluetooth MIDI…"))
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_BLE_UUID)).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                mainHandler.post {
                    if (scanCallback !== this || closed || !hasBluetoothPermissions()) return@post
                    val found = result.device
                    onBluetoothDevice(found)
                    val name = result.scanRecord?.deviceName ?: found.midiDisplayName()
                    if (recovery.canAttempt(SystemClock.elapsedRealtime()) && isKnownBluetooth(found.address, name)) {
                        openBluetooth(found)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                mainHandler.post {
                    if (scanCallback !== this) return@post
                    stopScan()
                    scanRetryAt = SystemClock.elapsedRealtime() + 30_000L
                    setConnectionState(MidiConnectionState.DISCONNECTED)
                    onStatus(status("Bluetooth MIDI scan error: $errorCode", "Ошибка поиска Bluetooth MIDI: $errorCode"))
                }
            }
        }
        scanCallback = callback
        scanStartedAt = SystemClock.elapsedRealtime()
        runCatching {
            activeScanner.startScan(listOf(filter), ScanSettings.Builder().build(), callback)
        }.onFailure {
            stopScan()
            scanRetryAt = SystemClock.elapsedRealtime() + 30_000L
            setConnectionState(MidiConnectionState.DISCONNECTED)
        }
    }

    fun openBluetooth(bluetoothDevice: BluetoothDevice) {
        if (closed || !hasBluetoothPermissions()) return
        stopScan()
        releaseConnection()
        val attempt = recovery.begin(SystemClock.elapsedRealtime())
        activeBluetoothAddress = bluetoothDevice.address
        val name = bluetoothDevice.midiDisplayName() ?: "Bluetooth MIDI"
        setConnectionState(MidiConnectionState.CONNECTING)
        onStatus(status("Connecting to $name…", "Подключение к $name…"))
        runCatching {
            midiManager.openBluetoothDevice(bluetoothDevice, { opened ->
                completeOpen(attempt, opened, name) { rememberBluetoothDevice(bluetoothDevice) }
            }, mainHandler)
        }.onFailure { connectionLost() }
    }

    fun stopScan() {
        val callback = scanCallback
        scanCallback = null
        try {
            if (callback != null) scanner?.stopScan(callback)
        } catch (_: SecurityException) {
            // Permission may be revoked while a scan is running.
        } catch (_: IllegalStateException) {
            // Bluetooth may have been disabled before this callback is processed.
        }
        scanner = null
    }

    /** A manual disconnect also returns to automatic discovery. */
    fun closeConnection() {
        connectionLost()
    }

    private fun releaseConnection() {
        recovery.invalidate(SystemClock.elapsedRealtime())
        pendingSystemId = null
        activeBluetoothAddress = null
        val released = heldNotes.toList()
        heldNotes.clear()
        released.forEach { onNote(it, 0, false) }
        runCatching { outputPort?.close() }
        outputPort = null
        runCatching { inputPort?.close() }
        inputPort = null
        val previous = device
        device = null
        runCatching { previous?.close() }
    }

    private fun connectionLost() {
        releaseConnection()
        setConnectionState(MidiConnectionState.DISCONNECTED)
        if (monitoring && !closed) {
            // Start discovery now; failed connection attempts have a retry cooldown.
            scanBluetooth()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        monitoring = false
        mainHandler.removeCallbacks(maintenance)
        midiManager.unregisterDeviceCallback(deviceCallback)
        appContext.unregisterReceiver(bluetoothEvents)
        stopScan()
        releaseConnection()
    }

    /** Keep discovery alive on every screen, including after a failed first connection. */
    fun reconnectLastDevice() {
        if (closed) return
        monitoring = true
        mainHandler.removeCallbacks(maintenance)
        maintenance.run()
    }

    private fun maintainConnection() {
        val now = SystemClock.elapsedRealtime()
        if (recovery.hasTimedOut(now)) connectionLost()
        if (device != null || recovery.connecting) return
        if (recovery.canAttempt(now)) {
            // Only sources that can deliver notes qualify; do not select synthesizer sinks.
            val endpoints = systemDevices().filter { it.info.type != MidiDeviceInfo.TYPE_BLUETOOTH && it.info.outputPortCount > 0 }
            val id = preferences.getInt(LAST_SYSTEM_ID, NO_DEVICE_ID)
            val label = preferences.getString(LAST_SYSTEM_LABEL, null)
            val endpoint = endpoints.firstOrNull { it.label == label }
                ?: endpoints.firstOrNull { it.info.id == id }
                ?: endpoints.firstOrNull()
            if (endpoint != null) {
                open(endpoint)
                return
            }
        }
        // Refresh infrequently: Android throttles applications that start scans too often.
        if (scanCallback != null && now - scanStartedAt >= SCAN_REFRESH_MS) stopScan()
        scanBluetooth()
    }

    private fun completeOpen(attempt: Long, opened: MidiDevice?, name: String, remember: () -> Unit) {
        if (closed || !recovery.isCurrent(attempt)) {
            runCatching { opened?.close() }
            return
        }
        if (opened == null) {
            connectionLost()
            return
        }
        device = opened
        val attached = runCatching {
            outputPort = opened.openOutputPort(0)
            check(outputPort != null) { "$name has no MIDI output port" }
            inputPort = opened.openInputPort(0)
            outputPort!!.connect(receiver)
        }
        if (attached.isFailure) {
            connectionLost()
            return
        }
        recovery.connected()
        pendingSystemId = null
        stopScan()
        remember()
        setConnectionState(MidiConnectionState.CONNECTED)
        onStatus(status("Connected: $name. Press a key on the piano.", "Подключено: $name. Нажмите клавишу на пианино."))
    }

    private fun isKnownBluetooth(address: String, name: String?): Boolean = matchesKnownMidiBluetooth(
        address, name,
        preferences.getStringSet(KNOWN_BLUETOOTH_ADDRESSES, emptySet()).orEmpty() +
            listOfNotNull(preferences.getString(LAST_BLUETOOTH_ADDRESS, null)),
        preferences.getStringSet(KNOWN_BLUETOOTH_NAMES, emptySet()).orEmpty(),
    )

    fun send(message: ByteArray) {
        val port = inputPort ?: run {
            onStatus(status("Connect a MIDI device for playback", "Для воспроизведения подключите MIDI-устройство"))
            return
        }
        runCatching { port.send(message, 0, message.size) }
            .onFailure { connectionLost() }
    }

    private fun status(english: String, russian: String) = if (language.isRussian) russian else english

    private fun hasBluetoothPermissions(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun rememberSystemEndpoint(endpoint: MidiEndpoint) {
        preferences.edit()
            .putString(LAST_DEVICE_TYPE, DEVICE_TYPE_SYSTEM)
            .putInt(LAST_SYSTEM_ID, endpoint.info.id)
            .putString(LAST_SYSTEM_LABEL, endpoint.label)
            .apply()
    }

    private fun rememberBluetoothDevice(bluetoothDevice: BluetoothDevice) {
        val addresses = preferences.getStringSet(KNOWN_BLUETOOTH_ADDRESSES, emptySet()).orEmpty() +
            listOfNotNull(preferences.getString(LAST_BLUETOOTH_ADDRESS, null), bluetoothDevice.address)
        val names = preferences.getStringSet(KNOWN_BLUETOOTH_NAMES, emptySet()).orEmpty() +
            listOfNotNull(bluetoothDevice.midiDisplayName()?.takeIf { it.isNotBlank() })
        preferences.edit()
            .putStringSet(KNOWN_BLUETOOTH_ADDRESSES, addresses)
            .putStringSet(KNOWN_BLUETOOTH_NAMES, names)
            .putString(LAST_DEVICE_TYPE, DEVICE_TYPE_BLUETOOTH)
            .putString(LAST_BLUETOOTH_ADDRESS, bluetoothDevice.address)
            .apply()
    }

    private fun setConnectionState(state: MidiConnectionState) {
        Log.i("PianoMidi", "Connection: $state")
        mainHandler.post { if (!closed) onConnectionState(state) }
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 5_000L
        const val SCAN_REFRESH_MS = 120_000L
        const val KNOWN_BLUETOOTH_ADDRESSES = "known_bluetooth_addresses"
        const val KNOWN_BLUETOOTH_NAMES = "known_bluetooth_names"
        const val PREFERENCES_NAME = "midi_connection"
        const val LAST_DEVICE_TYPE = "last_device_type"
        const val LAST_SYSTEM_ID = "last_system_id"
        const val LAST_SYSTEM_LABEL = "last_system_label"
        const val LAST_BLUETOOTH_ADDRESS = "last_bluetooth_address"
        const val DEVICE_TYPE_SYSTEM = "system"
        const val DEVICE_TYPE_BLUETOOTH = "bluetooth"
        const val NO_DEVICE_ID = -1
        val MIDI_BLE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    }
}

/** Device names require a revocable permission even while composing a settings row. */
internal fun BluetoothDevice.midiDisplayName(): String? = try {
    name
} catch (_: SecurityException) {
    null
}
