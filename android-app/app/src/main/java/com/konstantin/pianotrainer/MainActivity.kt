package com.konstantin.pianotrainer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class PracticeHands(val label: String) {
    BOTH("Both"),
    LEFT("Left"),
    RIGHT("Right");

    fun select(group: ExpectedGroup): ExpectedGroup? {
        val selected = when (this) {
            BOTH -> group
            LEFT -> group.copy(
                rightPitches = emptySet(),
                rightScoreNoteIds = emptySet(),
                rightScoreNoteIdsByPitch = emptyMap(),
            )
            RIGHT -> group.copy(
                leftPitches = emptySet(),
                leftScoreNoteIds = emptySet(),
                leftScoreNoteIdsByPitch = emptyMap(),
            )
        }
        return selected.takeIf { it.pitches.isNotEmpty() }
    }
}

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun SystemTime() {
    var time by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = LocalTime.now()
            delay(1_000)
        }
    }
    Text(time.format(clockFormatter), color = Color.White, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun MidiButton(state: MidiConnectionState, onClick: () -> Unit) {
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        highlighted = false
        if (state == MidiConnectionState.SEARCHING) {
            while (true) {
                delay(500)
                highlighted = !highlighted
            }
        }
    }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (state == MidiConnectionState.SEARCHING && highlighted) Color(0xFF8066B5) else Color(0xFF6750A4),
        ),
    ) { Text("MIDI") }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()
        setContent {
            MaterialTheme {
                PianoTrainerApp(ScorePackageRepository(applicationContext))
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun PianoTrainerApp(repository: ScorePackageRepository) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.load(context)) }
    val strings = remember(language) { Strings(language) }
    val midiController = remember { MidiController(context.applicationContext) }
    midiController.language = language
    var scores by remember { mutableStateOf(repository.list()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var openedScore by remember { mutableStateOf<ScorePackage?>(null) }
    var scoreToDelete by remember { mutableStateOf<ScorePackage?>(null) }
    var showMidiSettings by remember { mutableStateOf(false) }
    var midiConnectionState by remember { mutableStateOf(MidiConnectionState.DISCONNECTED) }
    val bluetoothPermissionsGranted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    midiController.onConnectionState = { midiConnectionState = it }
    val bluetoothPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) midiController.reconnectLastDevice()
    }
    DisposableEffect(midiController) {
        if (BuildConfig.DEBUG) {
            DebugMidiInput.onNote = { pitch, velocity, pressed ->
                midiController.injectDebugNote(pitch, velocity, pressed)
            }
        }
        onDispose {
            if (BuildConfig.DEBUG) DebugMidiInput.onNote = null
            midiController.close()
        }
    }
    LaunchedEffect(midiController) {
        midiController.reconnectLastDevice()
        if (!bluetoothPermissionsGranted) {
            bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { repository.import(uri) }
            .onSuccess { scores = repository.list(); importError = null }
            .onFailure { importError = it.message ?: strings.text("Could not import score", "Не удалось импортировать партитуру") }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F5F0)) {
        Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
            if (showMidiSettings) {
                MidiSettingsScreen(controller = midiController, strings = strings, onBack = { showMidiSettings = false })
            } else if (openedScore != null) {
                ScoreScreen(score = openedScore!!, repository = repository, controller = midiController, strings = strings, onBack = { openedScore = null })
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF101418)).padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Piano Trainer", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                            Text("v${BuildConfig.VERSION_NAME}", color = Color(0xFFB8C7D9), style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                language = if (language == AppLanguage.ENGLISH) AppLanguage.RUSSIAN else AppLanguage.ENGLISH
                                AppLanguage.save(context, language)
                            }) { Text(language.toggleLabel) }
                            MidiButton(state = midiConnectionState, onClick = { showMidiSettings = true })
                        }
                    }
                    if (scores.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(strings.text("Your library is empty", "Библиотека пока пуста"), style = MaterialTheme.typography.headlineSmall)
                                Text(strings.text("Import a prepared .pianoscore file", "Импортируйте подготовленный файл .pianoscore"))
                                Button(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                                    Text(strings.text("Import score", "Импортировать партитуру"))
                                }
                                importError?.let { Text(it, color = Color(0xFFB3261E)) }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                            Button(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                                Text(strings.text("Import", "Импортировать"))
                            }
                            Spacer(Modifier.height(14.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(scores, key = { it.id }) { score ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(Modifier.weight(1f).clickable { openedScore = score }) {
                                                Text(score.title, style = MaterialTheme.typography.titleLarge)
                                                Text(
                                                    if (score.normalPages.isEmpty()) strings.noPages() else strings.pages(score.normalPages.size),
                                                    color = Color(0xFF4A6178),
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = { openedScore = score }) { Text(strings.text("Open", "Открыть")) }
                                                Button(onClick = { scoreToDelete = score }) { Text(strings.text("Delete", "Удалить")) }
                                            }
                                        }
                                    }
                                }
                            }
                            importError?.let { Text(it, color = Color(0xFFB3261E)) }
                        }
                    }
                }
            }
        }
    }
    scoreToDelete?.let { score ->
        AlertDialog(
            onDismissRequest = { scoreToDelete = null },
            title = { Text(strings.text("Delete score?", "Удалить партитуру?")) },
            text = { Text(strings.scoreDeleted(score.title)) },
            confirmButton = {
                Button(onClick = {
                    runCatching { repository.delete(score) }
                        .onSuccess { scores = repository.list(); importError = null }
                        .onFailure { importError = it.message ?: strings.text("Could not delete score", "Не удалось удалить партитуру") }
                    scoreToDelete = null
                }) { Text(strings.text("Delete", "Удалить")) }
            },
            dismissButton = { Button(onClick = { scoreToDelete = null }) { Text(strings.text("Cancel", "Отмена")) } },
        )
    }
}

@Composable
private fun MidiSettingsScreen(controller: MidiController, strings: Strings, onBack: () -> Unit) {
    var status by remember(strings) { mutableStateOf(strings.text("MIDI not connected", "MIDI не подключено")) }
    var endpoints by remember { mutableStateOf(controller.systemDevices()) }
    var bluetoothDevices by remember { mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList()) }
    controller.onStatus = { status = it }
    controller.onBluetoothDevice = { device ->
        if (bluetoothDevices.none { it.address == device.address }) bluetoothDevices = bluetoothDevices + device
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            endpoints = controller.systemDevices()
            status = strings.text("Permissions granted. Select USB MIDI or start a Bluetooth MIDI scan.", "Разрешения получены. Выберите USB MIDI или начните поиск Bluetooth MIDI.")
            controller.reconnectLastDevice()
        } else {
            status = strings.text("Bluetooth MIDI requires Nearby devices permissions", "Для Bluetooth MIDI нужны разрешения «Устройства поблизости»")
        }
    }
    val hasBluetoothPermissions = contextHasBluetoothPermissions()
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF101418)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onBack) { Text(strings.text("Back", "Назад")) }
            Text("MIDI", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Button(onClick = { controller.closeConnection() }) { Text(strings.text("Reconnect", "Переподключить")) }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (hasBluetoothPermissions) {
                        bluetoothDevices = emptyList()
                        controller.scanBluetooth()
                    } else {
                        permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    }
                }) { Text(if (hasBluetoothPermissions) strings.text("Scan Bluetooth MIDI", "Искать Bluetooth MIDI") else strings.text("Allow Bluetooth", "Разрешить Bluetooth")) }
                Button(onClick = { endpoints = controller.systemDevices() }) { Text(strings.text("Refresh USB MIDI", "Обновить USB MIDI")) }
            }
            Text("Bluetooth MIDI", style = MaterialTheme.typography.titleMedium)
            if (bluetoothDevices.isEmpty()) Text(strings.text("Searching automatically. Enable Bluetooth MIDI on the piano. Select a new piano once to remember it.", "Поиск идёт автоматически. Включите Bluetooth MIDI на пианино. Новое пианино нужно выбрать один раз."))
            bluetoothDevices.forEach { device ->
                Card(Modifier.fillMaxWidth().clickable { controller.openBluetooth(device) }) {
                    Text(device.midiDisplayName() ?: "Bluetooth MIDI", Modifier.padding(16.dp))
                }
            }
            Text(strings.text("System USB / MIDI devices", "Системные USB / MIDI-устройства"), style = MaterialTheme.typography.titleMedium)
            if (endpoints.isEmpty()) Text(strings.text("No USB MIDI devices found", "USB MIDI-устройства не обнаружены"))
            endpoints.forEach { endpoint ->
                Card(Modifier.fillMaxWidth().clickable { controller.open(endpoint) }) {
                    Text(endpoint.label, Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun contextHasBluetoothPermissions(): Boolean {
    val context = LocalContext.current
    return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun ScoreScreen(score: ScorePackage, repository: ScorePackageRepository, controller: MidiController, strings: Strings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scoreRenderingHelpers = remember(context) {
        context.assets.open("score-rendering.js").bufferedReader().use { it.readText() }
    }
    var page by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var playbackCountdown by remember { mutableStateOf<Int?>(null) }
    var pendingPlaybackStartTick by remember { mutableStateOf<Long?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var practice by remember { mutableStateOf<WaitingPractice?>(null) }
    var practiceState by remember { mutableStateOf<PracticeState?>(null) }
    var continuousFeedback by remember { mutableStateOf<ContinuousFeedback?>(null) }
    var feedbackState by remember { mutableStateOf<PracticeState?>(null) }
    var playbackState by remember { mutableStateOf<MidiPlaybackState?>(null) }
    val practiceData = remember(score.id) {
        runCatching { repository.practiceTimeline(score) }
    }
    val initialPpq = practiceData.getOrNull()?.ppq ?: 480
    var practicePpq by remember { mutableStateOf(initialPpq) }
    var selectedFromTick by remember { mutableStateOf<Long?>(null) }
    var selectedToTick by remember { mutableStateOf<Long?>(null) }
    var selectedFromIsNote by remember { mutableStateOf(false) }
    var selectedToIsNote by remember { mutableStateOf(false) }
    var exactCursor by remember { mutableStateOf(false) }
    val scoreSelectionHelpers = remember(context) {
        context.assets.open("score-selection.js").bufferedReader().use { it.readText() }
    }
    val selectionMap = remember(practiceData) {
        org.json.JSONArray().apply {
            practiceData.getOrNull()?.groups?.forEach { group ->
                put(org.json.JSONObject().put("tick", group.tick).put("measure", group.measure)
                    .put("ids", org.json.JSONArray(group.scoreNoteIds.toList())))
            }
        }.toString()
    }
    val initialBrowseTick = practiceData.getOrNull()?.let { t -> t.groups.firstOrNull()?.tick ?: t.measures.firstOrNull()?.startTick }
    var browseCursorTick by remember(score.id) { mutableStateOf(initialBrowseTick) }
    var practiceHands by remember { mutableStateOf(PracticeHands.BOTH) }
    var handMenuExpanded by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var playbackVolume by remember { mutableStateOf(1f) }
    var volumeMenuExpanded by remember { mutableStateOf(false) }
    val playback = remember(score.id) { MidiPlayback(controller) }
    DisposableEffect(playback) { onDispose { playback.stop() } }
    controller.onNote = { pitch, _, pressed ->
        practice?.let { engine ->
            practiceState = if (pressed) engine.notePressed(pitch, SystemClock.uptimeMillis()) else engine.noteReleased(pitch)
        }
        if (practice == null && playing && pressed) {
            continuousFeedback?.let { engine ->
                feedbackState = engine.notePressed(pitch, playbackState?.tick ?: 0L)
            }
        }
    }
    LaunchedEffect(practice) {
        while (practice != null) {
            delay(50L)
            practice?.expireAttemptWindow(SystemClock.uptimeMillis())?.let { practiceState = it }
        }
    }
    val timeline = practiceData.getOrNull()
    val cursorTick = practiceState?.currentTick ?: playbackState?.tick ?: browseCursorTick
    val fullCursorGroup = when {
        practiceState?.currentTick != null -> timeline?.groups
            ?.firstOrNull { it.tick == practiceState?.currentTick }
        playbackState != null -> timeline?.groups
            ?.asSequence()
            ?.filter { it.tick <= playbackState!!.tick }
            ?.lastOrNull()
        // While browsing, the cursor points at the note that would sound next
        // from here, so it lands inside the measure it moved to instead of on
        // the trailing group of the previous one.
        browseCursorTick != null -> timeline?.groups?.firstOrNull { it.tick >= browseCursorTick!! }
        else -> null
    }
    val cursorGroup = fullCursorGroup?.let(practiceHands::select)
    val cursorMeasure = cursorGroup?.measure ?: cursorTick?.let { tick -> timeline?.measureAt(tick)?.number }
    val cursorMeasureInfo = cursorMeasure?.let { number -> timeline?.measures?.firstOrNull { it.number == number } }
    LaunchedEffect(cursorMeasure) {
        cursorMeasure?.let { measure -> repository.pageForMeasure(score, measure)?.let { page = it } }
    }
    LaunchedEffect(cursorTick) {
        // Keep the browse cursor trailing the live practice/playback position, so
        // once a session ends the cursor rests where it actually stopped instead
        // of snapping back to a stale spot from before the session started.
        if (practiceState != null || playbackState != null) cursorTick?.let { browseCursorTick = it }
    }
    val visibleLearningState = practiceState ?: feedbackState
    val isContinuousFeedback = practiceState == null && feedbackState != null
    val hasPages = score.normalPages.isNotEmpty()
    fun restartPlaybackFrom(requestedTick: Long) {
        runCatching {
            practice = null
            practiceState = null
            playbackState = null
            val scoreTimeline = practiceData.getOrThrow()
            practicePpq = scoreTimeline.ppq
            val startTick = maxOf(requestedTick, selectedFromTick ?: 0L)
            browseCursorTick = startTick
            val groups = scoreTimeline.groups
            val selectedGroups = groups.asSequence()
                .filter { group ->
                    group.tick >= startTick &&
                        group.tick <= (selectedToTick ?: Long.MAX_VALUE)
                }
                .mapNotNull(practiceHands::select)
                .toList()
            require(selectedGroups.isNotEmpty()) {
                strings.text("There are no notes to assess from this measure", "В выбранном такте нет нот для проверки")
            }
            continuousFeedback = ContinuousFeedback(selectedGroups, toleranceTicks = practicePpq / 2L)
            feedbackState = continuousFeedback!!.playbackTick(startTick)
            val untilTick = selectionEndTick(groups, selectedToTick, selectedToIsNote)
            playback.play(
                repository.sourceMidi(score),
                fromTick = startTick,
                untilTick = untilTick,
                speed = playbackSpeed,
                volume = playbackVolume,
                onProgress = { state ->
                    if (playbackState == null || state.tick >= playbackState!!.tick) {
                        playbackState = state
                        continuousFeedback?.let { feedbackState = it.playbackTick(state.tick) }
                    }
                },
                onFinished = {
                    continuousFeedback?.let { feedbackState = it.playbackTick(Long.MAX_VALUE) }
                    // Playback is a loop, not a terminal exercise, mirroring
                    // waiting practice's own restart at the segment's first note.
                    restartPlaybackFrom(selectedFromTick ?: 0L)
                },
            )
        }.onSuccess {
            playing = true
            playbackError = null
        }.onFailure { playbackError = it.message ?: strings.text("Could not start MIDI playback", "Не удалось запустить MIDI") }
    }
    fun cursorStartTick(): Long = sessionStartTick(
        if (exactCursor) cursorTick else cursorMeasureInfo?.startTick ?: browseCursorTick,
        selectedFromTick, selectedToTick,
    )
    LaunchedEffect(pendingPlaybackStartTick) {
        val startTick = pendingPlaybackStartTick ?: return@LaunchedEffect
        for (remaining in 2 downTo 1) {
            playbackCountdown = remaining
            delay(1_000L)
        }
        playbackCountdown = null
        pendingPlaybackStartTick = null
        restartPlaybackFrom(startTick)
    }
    /**
     * Builds a fresh waiting-practice session over the selected range. A null
     * [requestedTick] starts the range over; otherwise practice continues at the
     * first expected note from that tick.
     */
    fun restartPracticeFrom(requestedTick: Long?) {
        pendingPlaybackStartTick = null
        playbackCountdown = null
        playback.stop()
        playing = false
        playbackState = null
        continuousFeedback = null
        feedbackState = null
        runCatching {
            val scoreTimeline = practiceData.getOrThrow()
            practicePpq = scoreTimeline.ppq
            val selected = scoreTimeline.groups.asSequence()
                .mapNotNull(practiceHands::select)
                .toList()
            val fromTick = selectedFromTick ?: Long.MIN_VALUE
            val toTick = selectedToTick ?: Long.MAX_VALUE
            require(selected.any { it.tick in fromTick..toTick }) { strings.text("There are no notes to practise in the selected measures", "В выбранных тактах нет нот для обучения") }
            WaitingPractice(selected, fromTick = fromTick, toTick = toTick)
        }.onSuccess { engine ->
            practice = engine
            practiceState = requestedTick?.let(engine::seekToTick) ?: engine.current()
            val initialMeasure = practiceData.getOrNull()
                ?.measureAt(practiceState?.currentTick ?: 0L)
                ?.number ?: 1
            page = repository.pageForMeasure(score, initialMeasure) ?: 0
            playbackError = null
        }.onFailure {
            // A stale engine must never survive a failed rebuild: it would keep
            // expecting notes from the range that was in force when it was built.
            practice = null
            practiceState = null
            playbackError = it.message ?: strings.text("Could not start practice", "Не удалось запустить обучение")
        }
    }
    /**
     * A live session holds its own snapshot of the expected notes, so anything
     * that changes that set — the range ends, the practised hand — has to rebuild
     * it. Otherwise practice keeps waiting for notes outside the selected range.
     */
    fun rebuildActiveSession() {
        when {
            practice != null -> restartPracticeFrom(cursorStartTick())
            playing -> restartPlaybackFrom(cursorStartTick())
            pendingPlaybackStartTick != null -> pendingPlaybackStartTick = cursorStartTick()
            else -> {
                continuousFeedback = null
                feedbackState = null
            }
        }
    }
    val moveCursorToMeasure: (Int) -> Unit = move@ { measureNumber ->
        // A tap should land on audible material rather than the beginning of a
        // leading rest.  This also makes the cursor line up with the first
        // notes in the tapped measure.
        val target = timeline?.measures?.firstOrNull { it.number == measureNumber } ?: return@move
        exactCursor = false
        val firstNoteTick = timeline.groups.firstOrNull { it.measure == target.number }?.tick
        when {
            practice != null -> practice!!.seekToMeasure(target.number)?.let { practiceState = it }
            playing -> restartPlaybackFrom(target.startTick)
            pendingPlaybackStartTick != null -> {
                browseCursorTick = firstNoteTick ?: target.startTick
                pendingPlaybackStartTick = target.startTick
            }
            else -> browseCursorTick = firstNoteTick ?: target.startTick
        }
    }
    val moveCursorToNote: (Long) -> Unit = move@ { tick ->
        val target = timeline?.groups?.firstOrNull { it.tick == tick } ?: return@move
        exactCursor = true
        browseCursorTick = target.tick
        when {
            practice != null -> {
                // A boundary may be on the other hand. Keep the exact browse
                // position rather than silently snapping to a different note.
                if (practiceHands.select(target) == null) {
                    practice = null
                    practiceState = null
                } else practice!!.seekToTick(target.tick)?.let { practiceState = it }
            }
            playing -> {
                if (tick >= (selectedFromTick ?: 0L) && tick <= (selectedToTick ?: Long.MAX_VALUE)) {
                    restartPlaybackFrom(tick)
                } else {
                    playback.stop()
                    playing = false
                    playbackState = null
                    continuousFeedback = null
                    feedbackState = null
                }
            }
            pendingPlaybackStartTick != null -> pendingPlaybackStartTick = sessionStartTick(tick, selectedFromTick, selectedToTick)
        }
        target.measure?.let { repository.pageForMeasure(score, it)?.let { targetPage -> page = targetPage } }
    }
    val moveCursorByMeasure: (Int) -> Unit = move@ { delta ->
        val measures = timeline?.measures.orEmpty()
        val currentIndex = measures.indexOfFirst { it.number == cursorMeasure }
        val target = measures.getOrNull(currentIndex + delta) ?: return@move
        moveCursorToMeasure(target.number)
    }
    val currentMeasureIndex = timeline?.measures?.indexOfFirst { it.number == cursorMeasure } ?: -1
    val canMoveLeft = currentMeasureIndex > 0
    val canMoveRight = currentMeasureIndex >= 0 && currentMeasureIndex + 1 < timeline!!.measures.size
    val playModeActive = playing || pendingPlaybackStartTick != null
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF101418)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onBack) { Text(strings.text("Library", "Библиотека")) }
                SystemTime()
            }
            Text(score.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        cursorMeasureInfo?.let { measure ->
                            val tick = if (exactCursor) cursorTick ?: measure.startTick else measure.startTick
                            selectedFromTick = tick
                            selectedFromIsNote = exactCursor
                            if (selectedToTick != null && tick > selectedToTick!!) selectedToTick = null
                            rebuildActiveSession()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedFromTick != null) Color(0xFFE0A800) else Color(0xFF6750A4)),
                ) { Text("[") }
                Button(
                    onClick = {
                        cursorMeasureInfo?.let { measure ->
                            val endTick = if (exactCursor) cursorTick ?: measure.startTick else measure.startTick + measure.durationTicks - 1
                            selectedToTick = endTick
                            selectedToIsNote = exactCursor
                            if (selectedFromTick != null && endTick < selectedFromTick!!) selectedFromTick = null
                            rebuildActiveSession()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedToTick != null) Color(0xFFE0A800) else Color(0xFF6750A4)),
                ) { Text("]") }
                Button(
                    onClick = {
                        selectedFromTick = null
                        selectedToTick = null
                        selectedFromIsNote = false
                        selectedToIsNote = false
                        pendingPlaybackStartTick = null
                        playbackCountdown = null
                        playback.stop()
                        playing = false
                        playbackState = null
                        practice = null
                        practiceState = null
                        continuousFeedback = null
                        feedbackState = null
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    enabled = selectedFromTick != null || selectedToTick != null,
                ) { Text(strings.text("Reset", "Сброс")) }
                Box {
                    Button(
                        onClick = { handMenuExpanded = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(if (practiceHands == PracticeHands.BOTH) strings.text("Both", "Обе") else if (practiceHands == PracticeHands.LEFT) strings.text("Left", "Левая") else strings.text("Right", "Правая")) }
                    DropdownMenu(expanded = handMenuExpanded, onDismissRequest = { handMenuExpanded = false }) {
                        PracticeHands.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(when (mode) {
                                    PracticeHands.BOTH -> strings.text("Both hands", "Обе руки")
                                    PracticeHands.LEFT -> strings.text("Left hand", "Левая рука")
                                    PracticeHands.RIGHT -> strings.text("Right hand", "Правая рука")
                                }) },
                                onClick = {
                                    practiceHands = mode
                                    handMenuExpanded = false
                                    rebuildActiveSession()
                                },
                            )
                        }
                    }
                }
                Box {
                    Button(
                        onClick = { speedMenuExpanded = true },
                        enabled = !playModeActive,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                    ) { Text(playbackSpeed.formatPlaybackSpeed()) }
                    DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                        listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f).forEach { speed ->
                            DropdownMenuItem(
                                text = { Text(speed.formatPlaybackSpeed()) },
                                onClick = {
                                    playbackSpeed = speed
                                    speedMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Box {
                    // Softer playback lets the player hear the recording underneath their own
                    // playing instead of being drowned out by it.
                    Button(
                        onClick = { volumeMenuExpanded = true },
                        enabled = !playModeActive,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                    ) { Text(playbackVolume.formatPlaybackVolume()) }
                    DropdownMenu(expanded = volumeMenuExpanded, onDismissRequest = { volumeMenuExpanded = false }) {
                        listOf(0.25f, 0.4f, 0.6f, 0.8f, 1f).forEach { volume ->
                            DropdownMenuItem(
                                text = { Text(volume.formatPlaybackVolume()) },
                                onClick = {
                                    playbackVolume = volume
                                    volumeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Button(onClick = {
                    // "Restart" starts the whole segment over, while "Practise"
                    // resumes at the cursor, so scrolling to a measure and then
                    // practising continues from there.
                    restartPracticeFrom(if (practice == null) cursorStartTick() else null)
                }) { Text(if (practice == null) strings.text("Practise", "Учить") else strings.text("Restart", "Заново")) }
                Button(onClick = {
                    if (playModeActive) {
                        pendingPlaybackStartTick = null
                        playbackCountdown = null
                        playback.stop()
                        playing = false
                        playbackState = null
                        continuousFeedback = null
                        feedbackState = null
                    } else {
                        // Playback starts where the measure cursor stands, so
                        // moving it left/right picks the passage to hear instead
                        // of always replaying from the segment's first note.
                        val startTick = cursorStartTick()
                        practice = null
                        practiceState = null
                        continuousFeedback = null
                        feedbackState = null
                        browseCursorTick = startTick
                        pendingPlaybackStartTick = startTick
                    }
                }) { Text(if (playModeActive) strings.text("Stop", "Стоп") else strings.text("Play", "Играть")) }
                Text(if (hasPages) strings.pageCounter(page, score.normalPages.size) else strings.text("no pages", "нет страниц"), color = Color(0xFFB8C7D9))
            }
        }
        if (hasPages) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> ScoreWebView(context) },
                    update = { view ->
                        view.onMeasureSelected = moveCursorToMeasure
                        view.onNoteSelected = moveCursorToNote
                        val svg = runCatching { repository.pageSvg(score, page) }.getOrElse { "<svg xmlns='http://www.w3.org/2000/svg'><text x='30' y='50'>${it.message}</text></svg>" }
                        val key = "${score.id}:$page"
                        // ContinuousFeedback intentionally points to the next group
                        // within its tolerance window. The score cursor must instead
                        // stay with the group sounding now; otherwise text, colours
                        // and the vertical line describe different notes.
                        val displayedNotes = if (practiceState != null) {
                            practiceState!!.expected
                        } else {
                            cursorGroup?.pitches.orEmpty()
                        }
                        val acceptedNotes = if (practiceState != null) {
                            practiceState!!.held.intersect(practiceState!!.expected)
                        } else {
                            playbackState?.activePitches.orEmpty()
                        }
                        val expected = displayedNotes.joinToString(",")
                        val accepted = acceptedNotes.joinToString(",")
                        // Only currently held mistakes get synthetic red noteheads.
                        // Expected notes are already coloured on the original score.
                        val wrong = visibleLearningState?.wrong?.joinToString(",").orEmpty()
                        val tickInMeasure = cursorTick?.let { tick -> cursorMeasureInfo?.let { tick - it.startTick } } ?: 0L
                        val measureTicks = cursorMeasureInfo?.durationTicks ?: practicePpq * 4L
                        // In both learning modes, place the line on the engraved
                        // note itself. A proportional position across the whole
                        // measure starts too far left when the measure begins with
                        // accidentals or other notation that takes horizontal space.
                        val alignCursorToExpected = exactCursor || practiceState != null || playbackState != null
                        val occurrences = emptyMap<Int, Int>()
                        val occurrenceScript = occurrences.entries.joinToString(",", prefix = "{", postfix = "}") { (pitch, occurrence) -> "\"$pitch\":$occurrence" }
                        // Resolve every glyph in the original chord before filtering
                        // to one hand: MuseScore can give both staves the same id.
                        val scoreIds = fullCursorGroup?.scoreNoteIds.orEmpty().joinToString(",") { "'${it}'" }
                        val scorePitchPairs = buildList {
                            fullCursorGroup?.leftScoreNoteIdsByPitch.orEmpty()
                                .forEach { (pitch, ids) -> ids.forEach { id -> add(Triple(id, pitch, "left")) } }
                            fullCursorGroup?.rightScoreNoteIdsByPitch.orEmpty()
                                .forEach { (pitch, ids) -> ids.forEach { id -> add(Triple(id, pitch, "right")) } }
                        }
                        val scorePitches = scorePitchPairs
                            .groupBy({ (id, _, _) -> id }, { (_, pitch, hand) -> pitch to hand })
                            .entries
                            .joinToString(",", prefix = "{", postfix = "}") { (id, pitches) ->
                                "'$id':[${pitches.joinToString(",") { (pitch, hand) -> "{pitch:$pitch,hand:'$hand'}" }}]"
                            }
                        val keyFifths = timeline?.keyFifthsAt(cursorMeasure) ?: 0
                        val cursorScript = if (cursorMeasure == null) "document.querySelectorAll('.note,.Note').forEach(n=>colorNote(n,'black'));clearPracticeCursor()" else "highlightNotes($cursorMeasure,[$expected],[$accepted],[$wrong],$tickInMeasure,$measureTicks,$alignCursorToExpected,$occurrenceScript,[$scoreIds],$scorePitches,$keyFifths)"
                        val fromTickScript = selectedFromTick?.toString() ?: "null"
                        val toTickScript = selectedToTick?.toString() ?: "null"
                        val measureMapScript = timeline?.measures.orEmpty().joinToString(",", prefix = "[", postfix = "]") { "{n:${it.number},s:${it.startTick},d:${it.durationTicks}}" }
                        val script = "$cursorScript;PianoTrainerScoreSelection.showRange($fromTickScript,$toTickScript,$selectedFromIsNote,$selectedToIsNote,$measureMapScript)"
                        if (view.tag != key) {
                            val inlineSvg = svg.substring(svg.indexOf("<svg").coerceAtLeast(0))
                            val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes"><style>html,body{margin:0;background:#fff;overflow:auto}img{display:none}body>svg{display:block;width:100%;height:auto;margin:0 auto}</style></head><body>$inlineSvg<script>$scoreRenderingHelpers
$scoreSelectionHelpers
function midiPitch(n){const b={c:0,d:2,e:4,f:5,g:7,a:9,b:11};let p=(parseInt(n.dataset.oct)+1)*12+b[n.dataset.pname];const a=n.dataset.accid||n.dataset.accidGes||'';if(a==='s'||a==='ss')p+=a==='ss'?2:1;if(a==='f'||a==='ff')p-=a==='ff'?2:1;return p}
function cropScorePage(){const root=document.body.querySelector(':scope > svg');if(!root||root.dataset.cropped==='true'||!root.viewBox)return;const footers=[...root.querySelectorAll('.pgFoot')];footers.forEach(footer=>footer.remove());if(!footers.length){root.dataset.cropped='false';return}const systems=[...root.querySelectorAll('.system')];const box=root.viewBox.baseVal;const rootRect=root.getBoundingClientRect();if(!systems.length||!box.width||!box.height||!rootRect.height)return;const originalHeight=box.height;const bottom=Math.max(...systems.map(system=>system.getBoundingClientRect().bottom));const bottomInViewBox=box.y+(bottom-rootRect.top)*originalHeight/rootRect.height;const padding=Math.max(50,box.width*0.018);const stablePageFloor=originalHeight*0.81;const croppedHeight=Math.min(originalHeight,Math.max(stablePageFloor,bottomInViewBox+padding-box.y));const inner=root.querySelector('svg.definition-scale');if(inner&&inner.viewBox&&inner.viewBox.baseVal.height){const innerBox=inner.viewBox.baseVal;const innerHeight=innerBox.height*croppedHeight/originalHeight;inner.setAttribute('viewBox',innerBox.x+' '+innerBox.y+' '+innerBox.width+' '+innerHeight)}root.dataset.originalViewBoxHeight=String(originalHeight);root.setAttribute('viewBox',box.x+' '+box.y+' '+box.width+' '+croppedHeight);root.dataset.cropped='true';root.dataset.croppedViewBoxHeight=String(croppedHeight)}
function fitScorePage(){const root=document.body.querySelector(':scope > svg');if(!root||!root.viewBox)return;const box=root.viewBox.baseVal;if(!box.width||!box.height)return;const width=innerWidth;const height=innerHeight;const renderedWidth=Math.min(width,height*box.width/box.height);root.style.width=renderedWidth+'px';root.style.height='auto';PianoTrainerBridge.reportVisualDiagnostic(JSON.stringify({layout:{viewportWidth:width,viewportHeight:height,viewBoxWidth:box.width,viewBoxHeight:box.height,renderedWidth:renderedWidth,renderedHeight:renderedWidth*box.height/box.width}}))}
function colorNote(n,c){n.style.color=c;n.setAttribute('color',c);const shapes=n.matches&&n.matches('use,path,line,rect,ellipse,polygon,polyline')?[n]:[...n.querySelectorAll('use,path,line,rect,ellipse,polygon,polyline')];shapes.forEach(e=>{e.style.fill=c;e.style.stroke=c;e.setAttribute('fill',c);e.setAttribute('stroke',c)})}
function clearPracticeCursor(){document.querySelectorAll('.practice-cursor,.wrong-note-marker').forEach(e=>e.remove())}
function svgEl(name){return document.createElementNS('http://www.w3.org/2000/svg',name)}
function pointInsideMeasures(x,y,measures){return measures.some(m=>{const r=m.getBoundingClientRect();return x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom})}
function visualSelection(primary,measures,expected,timedFraction,occurrences,scoreIds,scorePitches){const exact=new Set(scoreIds||[]);if(exact.size){const root=document.querySelector('svg');const staves=nativeStaves(root);const glyphs=[...document.querySelectorAll('[data-id]')].filter(n=>exact.has(n.dataset.id||'')&&n.classList.contains('Note')).map(n=>{const r=n.getBoundingClientRect();const clientX=r.left+r.width/2,clientY=r.top+r.height/2;const rootY=screenPointIn(root,clientX,clientY).y;const staff=PianoTrainerScoreRendering.closestStaffToReference(staves,{y:rootY});return {note:n,id:n.dataset.id||'',y:clientY,clientX:clientX,clientY:clientY,staff:staff&&staff.clef}});const selected=PianoTrainerScoreRendering.assignGlyphPitches(glyphs,scorePitches).filter(e=>expected.includes(e.pitch)).map(e=>({...e,measure:primary,exact:true}));if(selected.length){const xs=selected.map(e=>e.clientX).sort((a,b)=>a-b);return {clientX:xs[Math.floor(xs.length/2)],notes:selected}}}const entries=noteEntries(measures).map(e=>{const r=(e.note.querySelector('.notehead')||e.note).getBoundingClientRect();return {...e,clientX:r.left+r.width/2,clientY:r.top+r.height/2}});const measureRect=primary.getBoundingClientRect();const roughX=measureRect.left+measureRect.width*timedFraction;const candidates=entries.filter(e=>expected.includes(e.pitch));if(!candidates.length)return {clientX:null,notes:[]};const anchor=candidates.reduce((best,e)=>Math.abs(e.clientX-roughX)<Math.abs(best.clientX-roughX)?e:best);const selected=[];[...new Set(expected)].forEach(pitch=>{const matching=candidates.filter(e=>e.pitch===pitch).sort((a,b)=>a.clientX-b.clientX);if(!matching.length)return;const occurrence=occurrences&&Number.isInteger(occurrences[pitch])?occurrences[pitch]:-1;selected.push(occurrence>=0&&occurrence<matching.length?matching[occurrence]:matching.reduce((best,e)=>Math.abs(e.clientX-anchor.clientX)<Math.abs(best.clientX-anchor.clientX)?e:best))});const sorted=selected.map(e=>e.clientX).sort((a,b)=>a-b);return {clientX:sorted[Math.floor(sorted.length/2)]||anchor.clientX,notes:selected}}
function drawPracticeCursor(m,tickInMeasure,measureTicks,selection,alignExpected){clearPracticeCursor();const measureBox=m.getBBox();const parent=m.parentNode;const shade=svgEl('rect');shade.setAttribute('class','practice-cursor');shade.setAttribute('x',measureBox.x);shade.setAttribute('y',measureBox.y);shade.setAttribute('width',measureBox.width);shade.setAttribute('height',measureBox.height);shade.setAttribute('fill','#1976d2');shade.setAttribute('opacity','0.10');shade.style.pointerEvents='none';parent.insertBefore(shade,m);const timedFraction=Math.max(0,Math.min(1,tickInMeasure/measureTicks));const timedX=measureBox.x+measureBox.width*timedFraction;const measureRect=m.getBoundingClientRect();const alignedX=selection.clientX===null?null:screenPointIn(parent,selection.clientX,measureRect.top+measureRect.height/2).x;const x=alignExpected&&alignedX!==null?alignedX:timedX;const line=svgEl('line');line.setAttribute('class','practice-cursor');line.setAttribute('x1',x);line.setAttribute('x2',x);line.setAttribute('y1',measureBox.y);line.setAttribute('y2',measureBox.y+measureBox.height);line.setAttribute('stroke','#d81b60');line.setAttribute('stroke-width','14');line.setAttribute('opacity','0.9');line.style.pointerEvents='none';parent.appendChild(line);return {x:x,parent:parent,line:line}}
function screenPointIn(parent,x,y){const svg=parent.ownerSVGElement||parent;const point=svg.createSVGPoint();point.x=x;point.y=y;return point.matrixTransform(parent.getScreenCTM().inverse())}
function staffPositionForPitch(p,fifths){return PianoTrainerScoreRendering.staffPositionForPitch(p,fifths)}
function noteEntries(measures){const root=document.querySelector('svg');return [...document.querySelectorAll('.note')].filter(n=>Number.isFinite(midiPitch(n))).map(n=>{const r=n.getBoundingClientRect();const measure=measures.find(m=>pointInsideMeasures(r.left+r.width/2,r.top+r.height/2,[m]));return {note:n,measure:measure,staff:n.closest('.staff'),pitch:midiPitch(n)}}).filter(e=>e.measure)}
let lastVisualDiagnostic='';
function reportVisualDiagnostic(measure,tickInMeasure,measureTicks,expected,selection,keyFifths){const svg=document.querySelector('svg');const box=svg&&svg.viewBox?svg.viewBox.baseVal:null;const notes=selection.notes.map(e=>{const staff=e.note.closest('.staff');return {pitch:e.pitch,noteId:e.note.dataset.id||'',measureId:e.measure.dataset.id||'',measure:e.measure.dataset.n||'',staffId:staff&&staff.dataset?staff.dataset.id||'':'',clientX:e.clientX,clientY:e.clientY}});const diagnostic=JSON.stringify({measure:measure,tickInMeasure:tickInMeasure,measureTicks:measureTicks,expected:expected,keyFifths:keyFifths,selected:notes,viewport:{width:innerWidth,height:innerHeight},viewBox:box?{width:box.width,height:box.height}:null});if(diagnostic!==lastVisualDiagnostic){lastVisualDiagnostic=diagnostic;PianoTrainerBridge.reportVisualDiagnostic(diagnostic)}}
function median(values,fallback){if(!values.length)return fallback;const sorted=values.slice().sort((a,b)=>a-b);return sorted[Math.floor(sorted.length/2)]}
function markerGeometry(entry,pitch,cursor,keyFifths){const parent=entry.measure.parentNode;const noteHead=entry.note.querySelector('.notehead')||entry.note;const rect=noteHead.getBoundingClientRect();const center=screenPointIn(parent,rect.left+rect.width/2,rect.top+rect.height/2);const staff=entry.staff;const lineYs=staff?[...staff.querySelectorAll(':scope > path')].map(line=>{const r=line.getBoundingClientRect();return screenPointIn(parent,r.left+r.width/2,r.top+r.height/2).y}):[];const sortedLines=lineYs.slice().sort((a,b)=>a-b);const steps=sortedLines.slice(1).map((y,index)=>Math.abs(y-sortedLines[index]));const staffStep=median(steps,Math.max(70,rect.height*0.6));const systemBox=parent.getBBox();const rawY=center.y-(staffPositionForPitch(pitch,keyFifths)-staffPositionForPitch(entry.pitch,keyFifths))*staffStep/2;const y=Math.max(systemBox.y+staffStep,Math.min(systemBox.y+systemBox.height-staffStep,rawY));return {parent:parent,x:cursor.x,y:y,rx:staffStep*0.95,ry:staffStep*0.65,staffStep:staffStep}}
function nativeEntries(selection){const root=document.querySelector('svg');return (selection.notes||[]).map(entry=>{const center=screenPointIn(root,entry.clientX,entry.clientY);return {note:entry.note,x:center.x,y:center.y,pitch:entry.pitch,measure:entry.measure,root:root}})}
function nativeStaves(root){const lines=[...root.querySelectorAll('.StaffLines')].map(line=>{const r=line.getBoundingClientRect();return screenPointIn(root,r.left+r.width/2,r.top+r.height/2).y}).sort((a,b)=>a-b);if(lines.length<5)return[];const diffs=lines.slice(1).map((y,index)=>y-lines[index]).filter(value=>value>0);const step=Math.min(...diffs);const groups=[];lines.forEach(y=>{const group=groups[groups.length-1];if(!group||y-group[group.length-1]>step*1.8)groups.push([y]);else group.push(y)});return groups.filter(group=>group.length>=4).map((group,index)=>({center:group[2],step:median(group.slice(1).map((y,line)=>y-group[line]),step),clef:index%2===0?'treble':'bass'}))}
function drawWrongAccidental(parent,x,y,pitch,keyFifths,staffStep){const accidental=PianoTrainerScoreRendering.accidentalForPitch(pitch,keyFifths);if(!accidental)return;const label=svgEl('text');label.setAttribute('class','wrong-note-marker wrong-note-accidental');label.setAttribute('x',x-staffStep*1.15);label.setAttribute('y',y);label.setAttribute('text-anchor','end');label.setAttribute('dominant-baseline','central');label.setAttribute('font-family','Noto Music, Noto Sans Symbols, serif');label.setAttribute('font-size',staffStep*1.35);label.textContent=accidental;label.style.setProperty('fill','#d01818','important');label.style.setProperty('stroke','none','important');label.style.setProperty('pointer-events','none');parent.appendChild(label)}
function drawNativeWrongNotes(wrong,cursor,selection,keyFifths){const entries=nativeEntries(selection);if(!entries.length)return false;const root=entries[0].root;const staves=nativeStaves(root);if(!staves.length)return false;wrong.forEach(pitch=>PianoTrainerScoreRendering.closestExpectedEntries(entries,pitch).forEach(reference=>{const staff=PianoTrainerScoreRendering.closestStaffToReference(staves,reference);if(!staff)return;const middleLinePitch=staff.clef==='treble'?34:22;const y=staff.center-(staffPositionForPitch(pitch,keyFifths)-middleLinePitch)*staff.step/2;const lineRect=cursor.line.getBoundingClientRect();const point=screenPointIn(root,lineRect.left+lineRect.width/2,lineRect.top+lineRect.height/2);const x=point.x;const dx=x-reference.x,dy=y-reference.y;const marker=reference.note.cloneNode(true);marker.removeAttribute('data-id');marker.removeAttribute('data-segment-id');marker.setAttribute('class','wrong-note-marker Note');marker.style.setProperty('fill','#d01818','important');marker.style.setProperty('stroke','#8b0000','important');marker.style.setProperty('color','#d01818','important');marker.style.setProperty('pointer-events','none');const rootScreenMatrix=root.getScreenCTM&&root.getScreenCTM();const noteScreenMatrix=reference.note.getScreenCTM&&reference.note.getScreenCTM();const ctm=rootScreenMatrix&&noteScreenMatrix?PianoTrainerScoreRendering.matrixRelativeToRoot(rootScreenMatrix,noteScreenMatrix):null;if(ctm){marker.setAttribute('transform',PianoTrainerScoreRendering.matrixText(PianoTrainerScoreRendering.matrixAtOffset(ctm,dx,dy)));root.appendChild(marker)}else{const wrapper=svgEl('g');wrapper.setAttribute('class','wrong-note-marker');wrapper.setAttribute('transform','translate('+dx+' '+dy+')');wrapper.appendChild(marker);root.appendChild(wrapper)}drawWrongAccidental(root,x,y,pitch,keyFifths,staff.step)}));return true}
function drawWrongNotes(measures,wrong,cursor,selection,keyFifths){if(!wrong.length)return;if(drawNativeWrongNotes(wrong,cursor,selection,keyFifths))return;const expectedEntries=(selection.notes||[]).filter(e=>Number.isFinite(e.pitch));const entries=expectedEntries.length?expectedEntries:noteEntries(measures);if(!entries.length)return;wrong.forEach(pitch=>PianoTrainerScoreRendering.closestExpectedEntries(entries,pitch).forEach(reference=>{const g=markerGeometry(reference,pitch,cursor,keyFifths);const marker=svgEl('ellipse');marker.setAttribute('class','wrong-note-marker');marker.setAttribute('cx',g.x);marker.setAttribute('cy',g.y);marker.setAttribute('rx',g.rx);marker.setAttribute('ry',g.ry);marker.setAttribute('transform','rotate(-18 '+g.x+' '+g.y+')');marker.style.setProperty('fill','#d01818','important');marker.style.setProperty('stroke','#8b0000','important');marker.style.setProperty('stroke-width','18','important');marker.style.setProperty('color','#d01818','important');marker.style.setProperty('pointer-events','none');g.parent.appendChild(marker);drawWrongAccidental(g.parent,g.x,g.y,pitch,keyFifths,g.staffStep)}))}
function highlightNotes(measure,expected,accepted,wrong,tickInMeasure,measureTicks,alignExpected,occurrences,scoreIds,scorePitches,keyFifths){document.querySelectorAll('.note,.Note').forEach(n=>colorNote(n,'black'));const measures=[...document.querySelectorAll('.measure[data-n="'+measure+'"]')];if(!measures.length){clearPracticeCursor();return}const timedFraction=Math.max(0,Math.min(1,tickInMeasure/measureTicks));const selection=visualSelection(measures[0],measures,expected,timedFraction,occurrences,scoreIds,scorePitches);reportVisualDiagnostic(measure,tickInMeasure,measureTicks,expected,selection,keyFifths);const cursor=drawPracticeCursor(measures[0],tickInMeasure,measureTicks,selection,alignExpected);selection.notes.forEach(e=>colorNote(e.note,accepted.includes(e.pitch)?'#16833b':'#1565c0'));drawWrongNotes(measures,wrong,cursor,selection,keyFifths)}

function measureAtPoint(x,y){return [...document.querySelectorAll('.measure[data-n]')].find(m=>{const r=m.getBoundingClientRect();return x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom})}
PianoTrainerScoreSelection.setGroups($selectionMap);
PianoTrainerScoreSelection.install(PianoTrainerBridge,measureAtPoint,'$key');
addEventListener('resize',fitScorePage);cropScorePage();fitScorePage();PianoTrainerBridge.pageReady('$key');
</script></body></html>"""
                        view.tag = key
                        view.loadScorePage(key, html, script)
                    } else {
                        view.applyPracticeScript(script)
                    }
                    },
                )
                playbackCountdown?.let { remaining ->
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0x66000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            remaining.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.displayLarge,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF7F5F0)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { page-- }, enabled = page > 0) { Text(strings.text("Previous", "Назад")) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    page = 0
                    timeline?.measures?.firstOrNull()?.let { moveCursorToMeasure(it.number) }
                }) { Text(strings.text("Begin", "Начало")) }
                Box(
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val state = visibleLearningState
                    if (state != null && state.finished) {
                        val result = if (isContinuousFeedback) {
                            strings.practiceResult(state.correctGroups, state.missedGroups)
                        } else {
                            strings.completed(state.total)
                        }
                        Text(result, textAlign = TextAlign.Center, color = Color(0xFF173A61))
                    } else if (state != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                strings.clefStatus(strings.text("Bass", "Басовый"), state.expectedLeft, state.accepted, timeline?.keyFifthsAt(cursorMeasure) ?: 0),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color(0xFF173A61),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { moveCursorByMeasure(-1) },
                                    enabled = canMoveLeft,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) { Text("←") }
                                Text(
                                    if (state.wrong.isEmpty()) {
                                        if (isContinuousFeedback) strings.score(state.correctGroups, state.total) else "${state.completed + 1}/${state.total}"
                                    } else {
                                        strings.mistake(state.wrong.sorted().joinToString(" ") { midiSolfegeName(it, timeline?.keyFifthsAt(cursorMeasure) ?: 0) })
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    maxLines = 1,
                                    color = if (state.wrong.isEmpty()) Color(0xFF4A6178) else Color(0xFFB3261E),
                                )
                                Button(
                                    onClick = { moveCursorByMeasure(1) },
                                    enabled = canMoveRight,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) { Text("→") }
                            }
                            Text(
                                strings.clefStatus(strings.text("Treble", "Скрипичный"), state.expectedRight, state.accepted, timeline?.keyFifthsAt(cursorMeasure) ?: 0),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                color = Color(0xFF173A61),
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { moveCursorByMeasure(-1) },
                                enabled = canMoveLeft,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) { Text("←") }
                            Text(
                                cursorMeasure?.let(strings::measureLabel) ?: strings.noPosition(),
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = Color(0xFF173A61),
                            )
                            Button(
                                onClick = { moveCursorByMeasure(1) },
                                enabled = canMoveRight,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) { Text("→") }
                        }
                    }
                }
                Button(onClick = { page++ }, enabled = page + 1 < score.normalPages.size) { Text(strings.text("Next", "Далее")) }
            }
            playbackError?.let { Text(it, color = Color(0xFFB3261E), modifier = Modifier.padding(horizontal = 12.dp)) }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.text("This package has no rendered SVG pages. Rebuild it with --pages-dir.", "В этом пакете нет готовых SVG-страниц. Пересоберите его с --pages-dir."))
            }
        }
    }
}

private class ScoreWebView(context: Context) : WebView(context) {
    private var pendingPracticeScript = ""
    private var requestedPageKey = ""
    private var loadedPageKey = ""
    var onMeasureSelected: ((Int) -> Unit)? = null
    var onNoteSelected: ((Long) -> Unit)? = null

    private inner class SelectionBridge {
        @JavascriptInterface
        fun pageReady(pageKey: String) {
            post {
                loadedPageKey = pageKey
                if (loadedPageKey == requestedPageKey) applyPracticeScript(pendingPracticeScript)
            }
        }

        @JavascriptInterface
        fun reportVisualDiagnostic(payload: String) {
            if (BuildConfig.DEBUG) Log.d("PianoTrainerVisual", payload.take(4_000))
        }

        @JavascriptInterface
        fun noteSelected(pageKey: String, tick: Long) {
            post { if (pageKey == requestedPageKey) onNoteSelected?.invoke(tick) }
        }

        @JavascriptInterface
        fun measureSelected(measure: Int) {
            post { onMeasureSelected?.invoke(measure) }
        }
    }

    init {
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.javaScriptEnabled = true
        addJavascriptInterface(SelectionBridge(), "PianoTrainerBridge")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // The embedded page reports its own key through pageReady().
                // onPageFinished alone may belong to a stale load.
            }
        }
    }

    fun loadScorePage(pageKey: String, html: String, practiceScript: String) {
        requestedPageKey = pageKey
        loadedPageKey = ""
        pendingPracticeScript = practiceScript
        loadDataWithBaseURL("https://piano-trainer.local/", html, "text/html", "UTF-8", null)
    }

    fun applyPracticeScript(script: String) {
        pendingPracticeScript = script
        // pageReady() is emitted by the document after the SVG and all helper
        // functions have been installed. WebView's progress may still be below
        // 100 at that instant, so waiting for it here can discard the first
        // cursor update after an automatic page turn. The next MIDI event then
        // makes the cursor appear one group too late.
        if (loadedPageKey == requestedPageKey && script.isNotBlank()) evaluateJavascript(script, null)
    }
}

internal fun midiSolfegeName(note: Int, keyFifths: Int = 0): String {
    val names = arrayOf("Do", "Re", "Mi", "Fa", "Sol", "La", "Si")
    val naturalSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val order = if (keyFifths < 0) intArrayOf(6, 2, 5, 1, 4, 0, 3) else intArrayOf(3, 0, 4, 1, 5, 2, 6)
    val keyAlterations = IntArray(7)
    order.take(kotlin.math.abs(keyFifths.coerceIn(-7, 7))).forEach { keyAlterations[it] = if (keyFifths < 0) -1 else 1 }
    data class Spelling(val name: Int, val alteration: Int, val octave: Int, val cost: Double)
    val pitchClass = Math.floorMod(note, 12)
    val candidates = names.indices.flatMap { name ->
        (-1..1).mapNotNull { alteration ->
            if (Math.floorMod(naturalSemitones[name] + alteration, 12) != pitchClass) return@mapNotNull null
            val signatureCost = when {
                alteration == keyAlterations[name] -> 0.0
                alteration == 0 -> 1.0
                else -> 2.0
            }
            val directionCost = if (alteration == 0 || (alteration < 0) == (keyFifths < 0)) 0.0 else 0.25
            Spelling(name, alteration, (note - naturalSemitones[name] - alteration) / 12 - 1, signatureCost + directionCost)
        }
    }
    val spelled = candidates.minWithOrNull(compareBy<Spelling> { it.cost }.thenBy { it.name }) ?: return note.toString()
    val sign = when {
        spelled.alteration == 0 && keyAlterations[spelled.name] != 0 -> "♮"
        spelled.alteration < 0 -> "♭"
        spelled.alteration > 0 -> "♯"
        else -> ""
    }
    return "${names[spelled.name]}$sign${spelled.octave}"
}

private fun Float.formatPlaybackSpeed(): String = when (this) {
    0.25f -> "0,25×"
    0.5f -> "0,5×"
    0.75f -> "0,75×"
    1f -> "1×"
    1.25f -> "1,25×"
    1.5f -> "1,5×"
    else -> "${this}×"
}

private fun Float.formatPlaybackVolume(): String = "${(this * 100).roundToInt()}%"
