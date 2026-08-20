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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            Button(onClick = { showMidiSettings = true }) { Text("MIDI") }
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
            Button(onClick = { controller.closeConnection(); status = strings.text("MIDI disconnected", "MIDI отключено") }) { Text(strings.text("Disconnect", "Отключить")) }
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
            if (bluetoothDevices.isEmpty()) Text(strings.text("Tap “Scan Bluetooth MIDI”, then enable Bluetooth MIDI mode on the FP‑30.", "Нажмите «Искать Bluetooth MIDI», затем включите на FP‑30 режим Bluetooth MIDI."))
            bluetoothDevices.forEach { device ->
                Card(Modifier.fillMaxWidth().clickable { controller.openBluetooth(device) }) {
                    Text(device.name ?: "Bluetooth MIDI", Modifier.padding(16.dp))
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
    var page by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
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
    val initialBrowseTick = practiceData.getOrNull()?.let { t -> t.groups.firstOrNull()?.tick ?: t.measures.firstOrNull()?.startTick }
    var browseCursorTick by remember(score.id) { mutableStateOf(initialBrowseTick) }
    var practiceHands by remember { mutableStateOf(PracticeHands.BOTH) }
    var handMenuExpanded by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
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
            val untilTick = selectedToTick?.let { end -> groups.firstOrNull { it.tick > end }?.tick }
            playback.play(
                repository.sourceMidi(score),
                fromTick = startTick,
                untilTick = untilTick,
                speed = playbackSpeed,
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
    val moveCursorByMeasure: (Int) -> Unit = move@ { delta ->
        val measures = timeline?.measures.orEmpty()
        val currentIndex = measures.indexOfFirst { it.number == cursorMeasure }
        val target = measures.getOrNull(currentIndex + delta) ?: return@move
        when {
            practice != null -> practice!!.seekToMeasure(target.number)?.let { practiceState = it }
            playing -> restartPlaybackFrom(target.startTick)
            else -> browseCursorTick = target.startTick
        }
    }
    val currentMeasureIndex = timeline?.measures?.indexOfFirst { it.number == cursorMeasure } ?: -1
    val canMoveMeasure = timeline != null
    val canMoveLeft = canMoveMeasure && currentMeasureIndex > 0
    val canMoveRight = canMoveMeasure && currentMeasureIndex >= 0 && currentMeasureIndex + 1 < timeline.measures.size
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF101418)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onBack) { Text(strings.text("Library", "Библиотека")) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(score.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("v${BuildConfig.VERSION_NAME}", color = Color(0xFFB8C7D9), style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The cursor selects whole measures, so both ends snap to the
                // bounds of the measure it sits on. "]" must close the range on
                // the last tick of that measure: ending it on the measure's own
                // start tick would leave the measure out and pull in only the
                // first note of it, which then sounded past the selection.
                Button(
                    onClick = {
                        cursorMeasureInfo?.let { measure ->
                            selectedFromTick = measure.startTick
                            if (selectedToTick != null && measure.startTick > selectedToTick!!) selectedToTick = null
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedFromTick != null) Color(0xFFE0A800) else Color(0xFF6750A4)),
                ) { Text("[") }
                Button(
                    onClick = {
                        cursorMeasureInfo?.let { measure ->
                            val endTick = measure.startTick + measure.durationTicks - 1
                            selectedToTick = endTick
                            if (selectedFromTick != null && endTick < selectedFromTick!!) selectedFromTick = null
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedToTick != null) Color(0xFFE0A800) else Color(0xFF6750A4)),
                ) { Text("]") }
                Button(
                    onClick = {
                        selectedFromTick = null
                        selectedToTick = null
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
                                    practice = null
                                    practiceState = null
                                    continuousFeedback = null
                                    feedbackState = null
                                },
                            )
                        }
                    }
                }
                Box {
                    Button(
                        onClick = { speedMenuExpanded = true },
                        enabled = !playing,
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
                Button(onClick = {
                    playback.stop()
                    playing = false
                    playbackState = null
                    continuousFeedback = null
                    feedbackState = null
                    runCatching {
                        val scoreTimeline = practiceData.getOrThrow()
                        practicePpq = scoreTimeline.ppq
                        val selected = scoreTimeline.groups.asSequence()
                            .filter { group ->
                                group.tick >= (selectedFromTick ?: Long.MIN_VALUE) &&
                                    group.tick <= (selectedToTick ?: Long.MAX_VALUE)
                            }
                            .mapNotNull(practiceHands::select)
                            .toList()
                        require(selected.isNotEmpty()) { strings.text("There are no notes to practise in the selected measures", "В выбранных тактах нет нот для обучения") }
                        WaitingPractice(selected)
                    }.onSuccess { engine ->
                        practice = engine
                        practiceState = engine.current()
                        continuousFeedback = null
                        feedbackState = null
                        val initialMeasure = practiceData.getOrNull()
                            ?.measureAt(engine.current().currentTick ?: 0L)
                            ?.number ?: 1
                        page = repository.pageForMeasure(score, initialMeasure) ?: 0
                        playbackError = null
                    }
                        .onFailure { playbackError = it.message ?: strings.text("Could not start practice", "Не удалось запустить обучение") }
                }) { Text(if (practice == null) strings.text("Practise", "Учить") else strings.text("Restart", "Заново")) }
                Button(onClick = {
                    if (playing) {
                        playback.stop()
                        playing = false
                        playbackState = null
                        continuousFeedback = null
                        feedbackState = null
                    } else {
                        restartPlaybackFrom(selectedFromTick ?: 0L)
                    }
                }) { Text(if (playing) strings.text("Stop", "Стоп") else strings.text("Play", "Играть")) }
                Text(if (hasPages) strings.pageCounter(page, score.normalPages.size) else strings.text("no pages", "нет страниц"), color = Color(0xFFB8C7D9))
            }
        }
        if (hasPages) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                factory = { context -> ScoreWebView(context) },
                update = { view ->
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
                    val alignCursorToExpected = practiceState != null
                    val occurrences = emptyMap<Int, Int>()
                    val occurrenceScript = occurrences.entries.joinToString(",", prefix = "{", postfix = "}") { (pitch, occurrence) -> "\"$pitch\":$occurrence" }
                    val scoreIds = cursorGroup?.scoreNoteIds.orEmpty().joinToString(",") { "'${it}'" }
                    val scorePitches = cursorGroup?.scoreNoteIdsByPitch.orEmpty()
                        .flatMap { (pitch, ids) -> ids.map { id -> id to pitch } }
                        .groupBy({ (id, _) -> id }, { (_, pitch) -> pitch })
                        .entries
                        .joinToString(",", prefix = "{", postfix = "}") { (id, pitches) ->
                            "'$id':[${pitches.sortedDescending().joinToString(",")}]"
                        }
                    val nativePitches = fullCursorGroup?.pitches.orEmpty().joinToString(",")
                    val nativeScoreIds = fullCursorGroup?.scoreNoteIds.orEmpty().joinToString(",") { "'${it}'" }
                    val cursorScript = if (cursorMeasure == null) "document.querySelectorAll('.note').forEach(n=>colorNote(n,'black'));clearPracticeCursor()" else "highlightNotes($cursorMeasure,[$expected],[$accepted],[$wrong],$tickInMeasure,$measureTicks,$alignCursorToExpected,$occurrenceScript,[$scoreIds],$scorePitches,[$nativePitches],[$nativeScoreIds])"
                    val fromTickScript = selectedFromTick?.toString() ?: "null"
                    val toTickScript = selectedToTick?.toString() ?: "null"
                    val measureMapScript = timeline?.measures.orEmpty().joinToString(",", prefix = "[", postfix = "]") { "{n:${it.number},s:${it.startTick},d:${it.durationTicks}}" }
                    val script = "$cursorScript;showSelectedRange($fromTickScript,$toTickScript,$measureMapScript)"
                    if (view.tag != key) {
                        val inlineSvg = svg.substring(svg.indexOf("<svg").coerceAtLeast(0))
                        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes"><style>html,body{margin:0;background:#fff;overflow:auto}img{display:none}body>svg{display:block;width:100%;height:auto;margin:0 auto}</style></head><body>$inlineSvg<script>
function midiPitch(n){const b={c:0,d:2,e:4,f:5,g:7,a:9,b:11};let p=(parseInt(n.dataset.oct)+1)*12+b[n.dataset.pname];const a=n.dataset.accid||n.dataset.accidGes||'';if(a==='s'||a==='ss')p+=a==='ss'?2:1;if(a==='f'||a==='ff')p-=a==='ff'?2:1;return p}
function cropScorePage(){const root=document.body.querySelector(':scope > svg');if(!root||root.dataset.cropped==='true'||!root.viewBox)return;const footers=[...root.querySelectorAll('.pgFoot')];footers.forEach(footer=>footer.remove());if(!footers.length){root.dataset.cropped='false';return}const systems=[...root.querySelectorAll('.system')];const box=root.viewBox.baseVal;const rootRect=root.getBoundingClientRect();if(!systems.length||!box.width||!box.height||!rootRect.height)return;const originalHeight=box.height;const bottom=Math.max(...systems.map(system=>system.getBoundingClientRect().bottom));const bottomInViewBox=box.y+(bottom-rootRect.top)*originalHeight/rootRect.height;const padding=Math.max(50,box.width*0.018);const stablePageFloor=originalHeight*0.81;const croppedHeight=Math.min(originalHeight,Math.max(stablePageFloor,bottomInViewBox+padding-box.y));const inner=root.querySelector('svg.definition-scale');if(inner&&inner.viewBox&&inner.viewBox.baseVal.height){const innerBox=inner.viewBox.baseVal;const innerHeight=innerBox.height*croppedHeight/originalHeight;inner.setAttribute('viewBox',innerBox.x+' '+innerBox.y+' '+innerBox.width+' '+innerHeight)}root.dataset.originalViewBoxHeight=String(originalHeight);root.setAttribute('viewBox',box.x+' '+box.y+' '+box.width+' '+croppedHeight);root.dataset.cropped='true';root.dataset.croppedViewBoxHeight=String(croppedHeight)}
function fitScorePage(){const root=document.body.querySelector(':scope > svg');if(!root||!root.viewBox)return;const box=root.viewBox.baseVal;if(!box.width||!box.height)return;const width=innerWidth;const height=innerHeight;const renderedWidth=Math.min(width,height*box.width/box.height);root.style.width=renderedWidth+'px';root.style.height='auto';PianoTrainerBridge.reportVisualDiagnostic(JSON.stringify({layout:{viewportWidth:width,viewportHeight:height,viewBoxWidth:box.width,viewBoxHeight:box.height,renderedWidth:renderedWidth,renderedHeight:renderedWidth*box.height/box.width}}))}
function colorNote(n,c){n.style.color=c;n.setAttribute('color',c);const shapes=n.matches&&n.matches('use,path,line,rect,ellipse,polygon,polyline')?[n]:[...n.querySelectorAll('use,path,line,rect,ellipse,polygon,polyline')];shapes.forEach(e=>{e.style.fill=c;e.style.stroke=c;e.setAttribute('fill',c);e.setAttribute('stroke',c)})}
function clearPracticeCursor(){document.querySelectorAll('.practice-cursor,.wrong-note-marker').forEach(e=>e.remove())}
function svgEl(name){return document.createElementNS('http://www.w3.org/2000/svg',name)}
function visualSelection(primary,measures,expected,timedFraction,occurrences,scoreIds,scorePitches){const exact=new Set(scoreIds||[]);if(exact.size){const selected=[...document.querySelectorAll('[data-id]')].filter(n=>exact.has(n.dataset.id||'')&&n.classList.contains('Note')).map(n=>{const r=n.getBoundingClientRect();return {note:n,measure:primary,scoreId:n.dataset.id||'',pitch:null,clientX:r.left+r.width/2,clientY:r.top+r.height/2,exact:true}});const byScoreId=new Map();selected.forEach(entry=>{const entries=byScoreId.get(entry.scoreId)||[];entries.push(entry);byScoreId.set(entry.scoreId,entries)});byScoreId.forEach((entries,id)=>{const pitches=((scorePitches||{})[id]||[]).slice().sort((a,b)=>b-a);entries.sort((a,b)=>a.clientY-b.clientY).forEach((entry,index)=>{entry.pitch=pitches[index]??null})});const xs=selected.map(e=>e.clientX).sort((a,b)=>a-b);return {clientX:xs.length?xs[Math.floor(xs.length/2)]:null,notes:selected}}const entries=noteEntries(measures).map(e=>{const r=(e.note.querySelector('.notehead')||e.note).getBoundingClientRect();return {...e,clientX:r.left+r.width/2}});const measureRect=primary.getBoundingClientRect();const roughX=measureRect.left+measureRect.width*timedFraction;const candidates=entries.filter(e=>expected.includes(e.pitch));if(!candidates.length)return {clientX:null,notes:[]};const anchor=candidates.reduce((best,e)=>Math.abs(e.clientX-roughX)<Math.abs(best.clientX-roughX)?e:best);const selected=[];[...new Set(expected)].forEach(pitch=>{const matching=candidates.filter(e=>e.pitch===pitch).sort((a,b)=>a.clientX-b.clientX);if(!matching.length)return;const occurrence=occurrences&&Number.isInteger(occurrences[pitch])?occurrences[pitch]:-1;selected.push(occurrence>=0&&occurrence<matching.length?matching[occurrence]:matching.reduce((best,e)=>Math.abs(e.clientX-anchor.clientX)<Math.abs(best.clientX-anchor.clientX)?e:best))});const sorted=selected.map(e=>e.clientX).sort((a,b)=>a-b);return {clientX:sorted[Math.floor(sorted.length/2)]||anchor.clientX,notes:selected}}
function drawPracticeCursor(m,tickInMeasure,measureTicks,selection,alignExpected){clearPracticeCursor();const measureBox=m.getBBox();const parent=m.parentNode;const shade=svgEl('rect');shade.setAttribute('class','practice-cursor');shade.setAttribute('x',measureBox.x);shade.setAttribute('y',measureBox.y);shade.setAttribute('width',measureBox.width);shade.setAttribute('height',measureBox.height);shade.setAttribute('fill','#1976d2');shade.setAttribute('opacity','0.10');parent.insertBefore(shade,m);const timedFraction=Math.max(0,Math.min(1,tickInMeasure/measureTicks));const timedX=measureBox.x+measureBox.width*timedFraction;const measureRect=m.getBoundingClientRect();const alignedX=selection.clientX===null?null:screenPointIn(parent,selection.clientX,measureRect.top+measureRect.height/2).x;const x=alignExpected&&alignedX!==null?alignedX:timedX;const line=svgEl('line');line.setAttribute('class','practice-cursor');line.setAttribute('x1',x);line.setAttribute('x2',x);line.setAttribute('y1',measureBox.y);line.setAttribute('y2',measureBox.y+measureBox.height);line.setAttribute('stroke','#d81b60');line.setAttribute('stroke-width','14');line.setAttribute('opacity','0.9');parent.appendChild(line);return {x:x,parent:parent,line:line}}
function screenPointIn(parent,x,y){const svg=parent.ownerSVGElement||parent;const point=svg.createSVGPoint();point.x=x;point.y=y;return point.matrixTransform(parent.getScreenCTM().inverse())}
function staffPositionForPitch(p){const degree=[0,0,1,1,2,3,3,4,4,5,5,6][((p%12)+12)%12];return (Math.floor(p/12)-1)*7+degree}
function noteEntries(measures){return measures.flatMap(m=>[...m.querySelectorAll('.note')].filter(n=>Number.isFinite(midiPitch(n))).map(n=>({note:n,measure:m,staff:n.closest('.staff'),pitch:midiPitch(n)})))}
let lastVisualDiagnostic='';
function reportVisualDiagnostic(measure,tickInMeasure,measureTicks,expected,selection){const svg=document.querySelector('svg');const box=svg&&svg.viewBox?svg.viewBox.baseVal:null;const notes=selection.notes.map(e=>{const staff=e.note.closest('.staff');return {pitch:e.pitch,noteId:e.note.dataset.id||'',measureId:e.measure.dataset.id||'',measure:e.measure.dataset.n||'',staffId:staff&&staff.dataset?staff.dataset.id||'':''}});const diagnostic=JSON.stringify({measure:measure,tickInMeasure:tickInMeasure,measureTicks:measureTicks,expected:expected,selected:notes,viewport:{width:innerWidth,height:innerHeight},viewBox:box?{width:box.width,height:box.height}:null});if(diagnostic!==lastVisualDiagnostic){lastVisualDiagnostic=diagnostic;PianoTrainerBridge.reportVisualDiagnostic(diagnostic)}}
function median(values,fallback){if(!values.length)return fallback;const sorted=values.slice().sort((a,b)=>a-b);return sorted[Math.floor(sorted.length/2)]}
function markerGeometry(entry,pitch,cursor){const parent=entry.measure.parentNode;const noteHead=entry.note.querySelector('.notehead')||entry.note;const rect=noteHead.getBoundingClientRect();const center=screenPointIn(parent,rect.left+rect.width/2,rect.top+rect.height/2);const staff=entry.staff;const lineYs=staff?[...staff.querySelectorAll(':scope > path')].map(line=>{const r=line.getBoundingClientRect();return screenPointIn(parent,r.left+r.width/2,r.top+r.height/2).y}):[];const sortedLines=lineYs.slice().sort((a,b)=>a-b);const steps=sortedLines.slice(1).map((y,index)=>Math.abs(y-sortedLines[index]));const staffStep=median(steps,Math.max(70,rect.height*0.6));const systemBox=parent.getBBox();const rawY=center.y-(staffPositionForPitch(pitch)-staffPositionForPitch(entry.pitch))*staffStep;const y=Math.max(systemBox.y+staffStep,Math.min(systemBox.y+systemBox.height-staffStep,rawY));return {parent:parent,x:cursor.x,y:y,rx:staffStep*0.95,ry:staffStep*0.65}}
function nativeEntries(pitches,scoreIds){const ids=new Set(scoreIds||[]);if(!ids.size)return[];const root=document.querySelector('svg');const notes=[...document.querySelectorAll('[data-id]')].filter(note=>ids.has(note.dataset.id||'')&&note.classList.contains('Note'));const sortedNotes=notes.map(note=>{const r=note.getBoundingClientRect();const center=screenPointIn(root,r.left+r.width/2,r.top+r.height/2);return {note:note,x:center.x,y:center.y}}).sort((a,b)=>a.y-b.y);const sortedPitches=[...new Set(pitches||[])].sort((a,b)=>b-a);return sortedPitches.slice(0,sortedNotes.length).map((pitch,index)=>({...sortedNotes[index],pitch:pitch,root:root}))}
function nativeStaves(root){const lines=[...root.querySelectorAll('.StaffLines')].map(line=>{const r=line.getBoundingClientRect();return screenPointIn(root,r.left+r.width/2,r.top+r.height/2).y}).sort((a,b)=>a-b);if(lines.length<5)return[];const diffs=lines.slice(1).map((y,index)=>y-lines[index]).filter(value=>value>0);const step=Math.min(...diffs);const groups=[];lines.forEach(y=>{const group=groups[groups.length-1];if(!group||y-group[group.length-1]>step*1.8)groups.push([y]);else group.push(y)});return groups.filter(group=>group.length>=4).map((group,index)=>({center:group[2],step:median(group.slice(1).map((y,line)=>y-group[line]),step),clef:index%2===0?'treble':'bass'}))}
function staffForWrongPitch(staves,reference,pitch){const referenceIndex=staves.reduce((best,staff,index)=>Math.abs(staff.center-reference.y)<Math.abs(staves[best].center-reference.y)?index:best,0);const pairStart=referenceIndex-referenceIndex%2;const pair=staves.slice(pairStart,pairStart+2);if(pair.length<2)return staves[referenceIndex];return pair.find(staff=>staff.clef===(pitch<60?'bass':'treble'))||staves[referenceIndex]}
function drawNativeWrongNotes(wrong,cursor,pitches,scoreIds){const entries=nativeEntries(pitches,scoreIds);if(!entries.length)return false;const root=entries[0].root;const staves=nativeStaves(root);if(!staves.length)return false;wrong.forEach(pitch=>{const reference=entries.reduce((best,entry)=>Math.abs(entry.pitch-pitch)<Math.abs(best.pitch-pitch)?entry:best);const staff=staffForWrongPitch(staves,reference,pitch);const middleLinePitch=staff.clef==='treble'?34:22;const y=staff.center-(staffPositionForPitch(pitch)-middleLinePitch)*staff.step/2;const lineRect=cursor.line.getBoundingClientRect();const x=screenPointIn(root,lineRect.left+lineRect.width/2,lineRect.top+lineRect.height/2).x;const marker=reference.note.cloneNode(true);marker.removeAttribute('data-id');marker.removeAttribute('data-segment-id');marker.setAttribute('class','wrong-note-marker Note');const matrix=(reference.note.getAttribute('transform')||'').match(/matrix\\(([^)]+)\\)/);if(matrix){const values=matrix[1].split(/[ ,]+/).map(Number);if(values.length===6){values[4]+=x-reference.x;values[5]+=y-reference.y;marker.setAttribute('transform','matrix('+values.join(',')+')')}}else marker.setAttribute('transform',(reference.note.getAttribute('transform')||'')+' translate('+(x-reference.x)+' '+(y-reference.y)+')');marker.style.setProperty('fill','#d01818','important');marker.style.setProperty('stroke','#8b0000','important');marker.style.setProperty('color','#d01818','important');marker.style.setProperty('pointer-events','none');root.appendChild(marker)});return true}
function drawWrongNotes(measures,wrong,cursor,nativePitches,nativeScoreIds){if(!wrong.length)return;if(drawNativeWrongNotes(wrong,cursor,nativePitches,nativeScoreIds))return;const entries=noteEntries(measures);if(!entries.length)return;wrong.forEach(pitch=>{const reference=entries.reduce((best,n)=>Math.abs(n.pitch-pitch)<Math.abs(best.pitch-pitch)?n:best);const g=markerGeometry(reference,pitch,cursor);const marker=svgEl('ellipse');marker.setAttribute('class','wrong-note-marker');marker.setAttribute('cx',g.x);marker.setAttribute('cy',g.y);marker.setAttribute('rx',g.rx);marker.setAttribute('ry',g.ry);marker.setAttribute('transform','rotate(-18 '+g.x+' '+g.y+')');marker.style.setProperty('fill','#d01818','important');marker.style.setProperty('stroke','#8b0000','important');marker.style.setProperty('stroke-width','18','important');marker.style.setProperty('color','#d01818','important');marker.style.setProperty('pointer-events','none');g.parent.appendChild(marker)})}
function highlightNotes(measure,expected,accepted,wrong,tickInMeasure,measureTicks,alignExpected,occurrences,scoreIds,scorePitches,nativePitches,nativeScoreIds){document.querySelectorAll('.note').forEach(n=>colorNote(n,'black'));const measures=[...document.querySelectorAll('.measure[data-n="'+measure+'"]')];if(!measures.length){clearPracticeCursor();return}const timedFraction=Math.max(0,Math.min(1,tickInMeasure/measureTicks));const selection=visualSelection(measures[0],measures,expected,timedFraction,occurrences,scoreIds,scorePitches);reportVisualDiagnostic(measure,tickInMeasure,measureTicks,expected,selection);const cursor=drawPracticeCursor(measures[0],tickInMeasure,measureTicks,selection,alignExpected);selection.notes.forEach(e=>colorNote(e.note,accepted.includes(e.pitch)?'#16833b':'#1565c0'));drawWrongNotes(measures,wrong,cursor,nativePitches,nativeScoreIds)}
function showSelectedRange(fromTick,toTick,measureMap){document.querySelectorAll('.range-highlight').forEach(e=>e.remove());if(fromTick===null&&toTick===null)return;const from=fromTick===null?0:fromTick;const to=toTick===null?Number.MAX_SAFE_INTEGER:toTick;document.querySelectorAll('.measure[data-n]').forEach(m=>{const info=(measureMap||[]).find(x=>x.n===parseInt(m.dataset.n));if(!info)return;const start=info.s,end=start+info.d,left=Math.max(start,from),right=Math.min(end,to);if(right<left||right<start||left>end)return;const box=m.getBBox(),parent=m.parentNode;const x1=box.x+box.width*Math.max(0,(left-start)/info.d);const x2=box.x+box.width*Math.min(1,(right-start)/info.d);const shade=svgEl('rect');shade.setAttribute('class','range-highlight');shade.setAttribute('x',x1);shade.setAttribute('y',box.y);shade.setAttribute('width',Math.max(18,x2-x1));shade.setAttribute('height',box.height);shade.setAttribute('fill','#687078');shade.setAttribute('opacity','0.16');parent.insertBefore(shade,m)})}
addEventListener('resize',fitScorePage);cropScorePage();fitScorePage();PianoTrainerBridge.pageReady('$key');
</script></body></html>"""
                        view.tag = key
                        view.loadScorePage(key, html, script)
                    } else {
                        view.applyPracticeScript(script)
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF7F5F0)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { page-- }, enabled = page > 0) { Text(strings.text("Previous", "Назад")) }
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
                                strings.clefStatus(strings.text("Bass", "Басовый"), state.expectedLeft, state.accepted),
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
                                        strings.mistake(state.wrong.sorted().joinToString(" ", transform = ::midiSolfegeName))
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
                                strings.clefStatus(strings.text("Treble", "Скрипичный"), state.expectedRight, state.accepted),
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

internal fun midiSolfegeName(note: Int): String {
    val names = arrayOf("Do", "Do♯", "Re", "Re♯", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "La♯", "Si")
    return "${names[note % 12]}${note / 12 - 1}"
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
