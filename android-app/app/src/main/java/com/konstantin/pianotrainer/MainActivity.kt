package com.konstantin.pianotrainer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
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
import kotlin.math.abs
import kotlin.math.roundToLong

private enum class RangeSelectionMode { FROM, TO }

private enum class PracticeHands(val label: String) {
    BOTH("Обе"),
    LEFT("Левая"),
    RIGHT("Правая");

    fun select(group: ExpectedGroup): ExpectedGroup? {
        val selected = when (this) {
            BOTH -> group
            LEFT -> group.copy(rightPitches = emptySet())
            RIGHT -> group.copy(leftPitches = emptySet())
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
    val midiController = remember { MidiController(context.applicationContext) }
    var scores by remember { mutableStateOf(repository.list()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var openedScore by remember { mutableStateOf<ScorePackage?>(null) }
    var scoreToDelete by remember { mutableStateOf<ScorePackage?>(null) }
    var showMidiSettings by remember { mutableStateOf(false) }
    DisposableEffect(midiController) { onDispose { midiController.close() } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { repository.import(uri) }
            .onSuccess { scores = repository.list(); importError = null }
            .onFailure { importError = it.message ?: "Не удалось импортировать партитуру" }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F5F0)) {
        Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
            if (showMidiSettings) {
                MidiSettingsScreen(controller = midiController, onBack = { showMidiSettings = false })
            } else if (openedScore != null) {
                ScoreScreen(score = openedScore!!, repository = repository, controller = midiController, onBack = { openedScore = null })
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
                        Button(onClick = { showMidiSettings = true }) { Text("MIDI") }
                    }
                    if (scores.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Библиотека пока пуста", style = MaterialTheme.typography.headlineSmall)
                                Text("Импортируйте подготовленный файл .pianoscore")
                                Button(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                                    Text("Импортировать партитуру")
                                }
                                importError?.let { Text(it, color = Color(0xFFB3261E)) }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                            Button(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                                Text("Импортировать")
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
                                                    if (score.normalPages.isEmpty()) "Нужны SVG-страницы" else "${score.normalPages.size} стр.",
                                                    color = Color(0xFF4A6178),
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = { openedScore = score }) { Text("Открыть") }
                                                Button(onClick = { scoreToDelete = score }) { Text("Удалить") }
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
            title = { Text("Удалить партитуру?") },
            text = { Text("«${score.title}» будет удалена с планшета. Исходный файл на Mac не изменится.") },
            confirmButton = {
                Button(onClick = {
                    runCatching { repository.delete(score) }
                        .onSuccess { scores = repository.list(); importError = null }
                        .onFailure { importError = it.message ?: "Не удалось удалить партитуру" }
                    scoreToDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { Button(onClick = { scoreToDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun MidiSettingsScreen(controller: MidiController, onBack: () -> Unit) {
    var status by remember { mutableStateOf("MIDI не подключено") }
    var endpoints by remember { mutableStateOf(controller.systemDevices()) }
    var bluetoothDevices by remember { mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList()) }
    controller.onStatus = { status = it }
    controller.onBluetoothDevice = { device ->
        if (bluetoothDevices.none { it.address == device.address }) bluetoothDevices = bluetoothDevices + device
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            endpoints = controller.systemDevices()
            status = "Разрешения получены. Выберите USB MIDI или начните поиск Bluetooth MIDI."
        } else {
            status = "Для Bluetooth MIDI нужны разрешения «Устройства поблизости»"
        }
    }
    val hasBluetoothPermissions = contextHasBluetoothPermissions()
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF101418)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onBack) { Text("Назад") }
            Text("MIDI", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Button(onClick = { controller.closeConnection(); status = "MIDI отключено" }) { Text("Отключить") }
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
                }) { Text(if (hasBluetoothPermissions) "Искать Bluetooth MIDI" else "Разрешить Bluetooth") }
                Button(onClick = { endpoints = controller.systemDevices() }) { Text("Обновить USB MIDI") }
            }
            Text("Bluetooth MIDI", style = MaterialTheme.typography.titleMedium)
            if (bluetoothDevices.isEmpty()) Text("Нажмите «Искать Bluetooth MIDI», затем включите на FP‑30 режим Bluetooth MIDI.")
            bluetoothDevices.forEach { device ->
                Card(Modifier.fillMaxWidth().clickable { controller.openBluetooth(device) }) {
                    Text(device.name ?: "Bluetooth MIDI", Modifier.padding(16.dp))
                }
            }
            Text("Системные USB / MIDI-устройства", style = MaterialTheme.typography.titleMedium)
            if (endpoints.isEmpty()) Text("USB MIDI-устройства не обнаружены")
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
private fun ScoreScreen(score: ScorePackage, repository: ScorePackageRepository, controller: MidiController, onBack: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var practice by remember { mutableStateOf<WaitingPractice?>(null) }
    var practiceState by remember { mutableStateOf<PracticeState?>(null) }
    var continuousFeedback by remember { mutableStateOf<ContinuousFeedback?>(null) }
    var feedbackState by remember { mutableStateOf<PracticeState?>(null) }
    var playbackState by remember { mutableStateOf<MidiPlaybackState?>(null) }
    val practiceData = remember(score.id) {
        runCatching { repository.practicePpq(score) to repository.practiceGroups(score) }
    }
    val initialPpq = practiceData.getOrNull()?.first ?: 480
    var practicePpq by remember { mutableStateOf(initialPpq) }
    var selectedFromTick by remember { mutableStateOf<Long?>(null) }
    var selectedToTick by remember { mutableStateOf<Long?>(null) }
    var rangeSelectionMode by remember { mutableStateOf<RangeSelectionMode?>(null) }
    var practiceHands by remember { mutableStateOf(PracticeHands.BOTH) }
    var handMenuExpanded by remember { mutableStateOf(false) }
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
    val cursorTick = practiceState?.currentTick ?: playbackState?.tick
    val cursorMeasure = cursorTick?.let { tick -> (tick / (practicePpq * 4L)).toInt() + 1 }
    LaunchedEffect(cursorMeasure) {
        cursorMeasure?.let { measure -> repository.pageForMeasure(score, measure)?.let { page = it } }
    }
    val visibleLearningState = practiceState ?: feedbackState
    val isContinuousFeedback = practiceState == null && feedbackState != null
    val hasPages = score.normalPages.isNotEmpty()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF101418)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onBack) { Text("Библиотека") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(score.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("v${BuildConfig.VERSION_NAME}", color = Color(0xFFB8C7D9), style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { rangeSelectionMode = if (rangeSelectionMode == RangeSelectionMode.FROM) null else RangeSelectionMode.FROM },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (rangeSelectionMode == RangeSelectionMode.FROM) Color(0xFFE0A800) else Color(0xFF6750A4)),
                ) { Text("От") }
                Button(
                    onClick = { rangeSelectionMode = if (rangeSelectionMode == RangeSelectionMode.TO) null else RangeSelectionMode.TO },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (rangeSelectionMode == RangeSelectionMode.TO) Color(0xFFE0A800) else Color(0xFF6750A4)),
                ) { Text("До") }
                Button(
                    onClick = {
                        selectedFromTick = null
                        selectedToTick = null
                        rangeSelectionMode = null
                        practice = null
                        practiceState = null
                        continuousFeedback = null
                        feedbackState = null
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    enabled = selectedFromTick != null || selectedToTick != null,
                ) { Text("Сброс") }
                Box {
                    Button(
                        onClick = { handMenuExpanded = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(practiceHands.label) }
                    DropdownMenu(expanded = handMenuExpanded, onDismissRequest = { handMenuExpanded = false }) {
                        PracticeHands.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(if (mode == PracticeHands.BOTH) "Обе руки" else "${mode.label} рука") },
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
                Button(onClick = {
                    playback.stop()
                    playing = false
                    playbackState = null
                    continuousFeedback = null
                    feedbackState = null
                    runCatching {
                        val (ppq, groups) = practiceData.getOrThrow()
                        practicePpq = ppq
                        val selected = groups.asSequence()
                            .filter { group ->
                                group.tick >= (selectedFromTick ?: Long.MIN_VALUE) &&
                                    group.tick <= (selectedToTick ?: Long.MAX_VALUE)
                            }
                            .mapNotNull(practiceHands::select)
                            .toList()
                        require(selected.isNotEmpty()) { "В выбранных тактах нет нот для обучения" }
                        WaitingPractice(selected)
                    }.onSuccess { engine ->
                        practice = engine
                        practiceState = engine.current()
                        continuousFeedback = null
                        feedbackState = null
                        val initialMeasure = ((engine.current().currentTick ?: 0L) / (practicePpq * 4L)).toInt() + 1
                        page = repository.pageForMeasure(score, initialMeasure) ?: 0
                        playbackError = null
                    }
                        .onFailure { playbackError = it.message ?: "Не удалось запустить обучение" }
                }) { Text(if (practice == null) "Учить" else "Заново") }
                Button(onClick = {
                    if (playing) {
                        playback.stop()
                        playing = false
                        playbackState = null
                        continuousFeedback = null
                        feedbackState = null
                    } else {
                        runCatching {
                            practice = null
                            practiceState = null
                            val (ppq, groups) = practiceData.getOrThrow()
                            practicePpq = ppq
                            val selectedGroups = groups.asSequence()
                                .filter { group ->
                                    group.tick >= (selectedFromTick ?: Long.MIN_VALUE) &&
                                        group.tick <= (selectedToTick ?: Long.MAX_VALUE)
                                }
                                .mapNotNull(practiceHands::select)
                                .toList()
                            require(selectedGroups.isNotEmpty()) { "В выбранном интервале нет нот для оценки" }
                            continuousFeedback = ContinuousFeedback(selectedGroups, toleranceTicks = ppq / 2L)
                            feedbackState = continuousFeedback!!.playbackTick(selectedFromTick ?: 0L)
                            val untilTick = selectedToTick?.let { end -> groups.firstOrNull { it.tick > end }?.tick }
                            playback.play(
                                repository.sourceMidi(score),
                                fromTick = selectedFromTick ?: 0L,
                                untilTick = untilTick,
                                onProgress = { state ->
                                    playbackState = state
                                    continuousFeedback?.let { feedbackState = it.playbackTick(state.tick) }
                                },
                                onFinished = {
                                    continuousFeedback?.let { feedbackState = it.playbackTick(Long.MAX_VALUE) }
                                    playing = false
                                    playbackState = null
                                    continuousFeedback = null
                                },
                            )
                        }.onSuccess { playing = true; playbackError = null }
                            .onFailure { playbackError = it.message ?: "Не удалось запустить MIDI" }
                    }
                }) { Text(if (playing) "Стоп" else "Play") }
                Text(if (hasPages) "${page + 1}/${score.normalPages.size}" else "нет страниц", color = Color(0xFFB8C7D9))
            }
        }
        visibleLearningState?.let { state ->
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFE7EEF7)).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.finished) {
                    val result = if (isContinuousFeedback) {
                        "Итог: верно ${state.correctGroups}, пропущено ${state.missedGroups}"
                    } else {
                        "Готово: ${state.total} позиций сыграно"
                    }
                    Text(result, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Color(0xFF173A61))
                } else {
                    Text(
                        clefStatus("Басовый", state.expectedLeft, state.accepted),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFF173A61),
                    )
                    Text(
                        if (state.wrong.isEmpty()) {
                            if (isContinuousFeedback) "Оценка ${state.correctGroups}/${state.total}" else "${state.completed + 1}/${state.total}"
                        } else {
                            "Ошибка: ${state.wrong.sorted().joinToString(" ", transform = ::midiSolfegeName)}"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        maxLines = 1,
                        color = if (state.wrong.isEmpty()) Color(0xFF4A6178) else Color(0xFFB3261E),
                    )
                    Text(
                        clefStatus("Скрипичный", state.expectedRight, state.accepted),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        color = Color(0xFF173A61),
                    )
                }
            }
        }
        if (hasPages) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                factory = { context -> ScoreWebView(context) },
                update = { view ->
                    view.onPositionSelected = selection@{ measure, fraction, clickedPitch ->
                        val (ppq, groups) = practiceData.getOrNull() ?: return@selection
                        val measureTicks = ppq * 4L
                        val rawTick = (measure - 1L) * measureTicks + (fraction * measureTicks).roundToLong()
                        val inMeasure = groups.filter { it.tick / measureTicks + 1L == measure.toLong() }
                        val matchingPitch = if (clickedPitch >= 0) inMeasure.filter { clickedPitch in it.pitches } else emptyList()
                        val selectedTick = (matchingPitch.ifEmpty { inMeasure }).minByOrNull { abs(it.tick - rawTick) }?.tick ?: rawTick
                        when (rangeSelectionMode) {
                            RangeSelectionMode.FROM -> {
                                selectedFromTick = selectedTick
                                if (selectedToTick != null && selectedTick > selectedToTick!!) selectedToTick = null
                            }
                            RangeSelectionMode.TO -> {
                                selectedToTick = selectedTick
                                if (selectedFromTick != null && selectedTick < selectedFromTick!!) selectedFromTick = null
                            }
                            null -> Unit
                        }
                        if (rangeSelectionMode != null) {
                            rangeSelectionMode = null
                            playback.stop()
                            playing = false
                            playbackState = null
                            practice = null
                            practiceState = null
                            continuousFeedback = null
                            feedbackState = null
                        }
                    }
                    val svg = runCatching { repository.pageSvg(score, page) }.getOrElse { "<svg xmlns='http://www.w3.org/2000/svg'><text x='30' y='50'>${it.message}</text></svg>" }
                    val key = "${score.id}:$page"
                    val displayedNotes = visibleLearningState?.expected ?: playbackState?.activePitches.orEmpty()
                    val acceptedNotes = visibleLearningState?.accepted ?: playbackState?.activePitches.orEmpty()
                    val expected = displayedNotes.joinToString(",")
                    val accepted = acceptedNotes.joinToString(",")
                    val attempted = visibleLearningState?.attempted?.joinToString(",").orEmpty()
                    val tickInMeasure = cursorTick?.rem(practicePpq * 4L) ?: 0L
                    val alignCursorToExpected = practiceState != null
                    val cursorScript = if (cursorMeasure == null) "document.querySelectorAll('.note').forEach(n=>colorNote(n,'black'));clearPracticeCursor()" else "highlightNotes($cursorMeasure,[$expected],[$accepted],[$attempted],$tickInMeasure,${practicePpq * 4L},$alignCursorToExpected)"
                    val fromTickScript = selectedFromTick?.toString() ?: "null"
                    val toTickScript = selectedToTick?.toString() ?: "null"
                    val modeScript = rangeSelectionMode?.name?.lowercase() ?: ""
                    val script = "$cursorScript;showSelectedRange($fromTickScript,$toTickScript,${practicePpq * 4L});setRangeSelectionMode('$modeScript')"
                    if (view.tag != key) {
                        val inlineSvg = svg.substring(svg.indexOf("<svg").coerceAtLeast(0))
                        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes"><style>html,body{margin:0;background:#fff;overflow:auto}img{display:none}svg{display:block;width:100%;height:auto}</style></head><body>$inlineSvg<script>
function midiPitch(n){const b={c:0,d:2,e:4,f:5,g:7,a:9,b:11};let p=(parseInt(n.dataset.oct)+1)*12+b[n.dataset.pname];const a=n.dataset.accid||n.dataset.accidGes||'';if(a==='s'||a==='ss')p+=a==='ss'?2:1;if(a==='f'||a==='ff')p-=a==='ff'?2:1;return p}
function colorNote(n,c){n.style.color=c;n.setAttribute('color',c);n.querySelectorAll('use,path,line,rect,ellipse,polygon,polyline').forEach(e=>{e.style.fill=c;e.style.stroke=c;e.setAttribute('fill',c);e.setAttribute('stroke',c)})}
function clearPracticeCursor(){document.querySelectorAll('.practice-cursor,.wrong-note-marker').forEach(e=>e.remove())}
function svgEl(name){return document.createElementNS('http://www.w3.org/2000/svg',name)}
function visualSelection(primary,measures,expected,timedFraction){const measureRect=primary.getBoundingClientRect();const roughX=measureRect.left+measureRect.width*timedFraction;const entries=noteEntries(measures).map(e=>{const r=(e.note.querySelector('.notehead')||e.note).getBoundingClientRect();return {...e,clientX:r.left+r.width/2}});const candidates=entries.filter(e=>expected.includes(e.pitch));if(!candidates.length)return {clientX:null,notes:[]};const anchor=candidates.reduce((best,e)=>Math.abs(e.clientX-roughX)<Math.abs(best.clientX-roughX)?e:best);const selected=[];[...new Set(expected)].forEach(pitch=>{const matching=candidates.filter(e=>e.pitch===pitch);if(matching.length)selected.push(matching.reduce((best,e)=>Math.abs(e.clientX-anchor.clientX)<Math.abs(best.clientX-anchor.clientX)?e:best))});const sorted=selected.map(e=>e.clientX).sort((a,b)=>a-b);return {clientX:sorted[Math.floor(sorted.length/2)]||anchor.clientX,notes:selected}}
function drawPracticeCursor(m,tickInMeasure,measureTicks,selection,alignExpected){clearPracticeCursor();const measureBox=m.getBBox();const parent=m.parentNode;const shade=svgEl('rect');shade.setAttribute('class','practice-cursor');shade.setAttribute('x',measureBox.x);shade.setAttribute('y',measureBox.y);shade.setAttribute('width',measureBox.width);shade.setAttribute('height',measureBox.height);shade.setAttribute('fill','#1976d2');shade.setAttribute('opacity','0.10');parent.insertBefore(shade,m);const timedFraction=Math.max(0,Math.min(1,tickInMeasure/measureTicks));const timedX=measureBox.x+measureBox.width*timedFraction;const measureRect=m.getBoundingClientRect();const alignedX=selection.clientX===null?null:screenPointIn(parent,selection.clientX,measureRect.top+measureRect.height/2).x;const x=alignExpected&&alignedX!==null?alignedX:timedX;const line=svgEl('line');line.setAttribute('class','practice-cursor');line.setAttribute('x1',x);line.setAttribute('x2',x);line.setAttribute('y1',measureBox.y);line.setAttribute('y2',measureBox.y+measureBox.height);line.setAttribute('stroke','#d81b60');line.setAttribute('stroke-width','14');line.setAttribute('opacity','0.9');parent.appendChild(line);return {x:x,parent:parent,line:line}}
function screenPointIn(parent,x,y){const svg=parent.ownerSVGElement;const point=svg.createSVGPoint();point.x=x;point.y=y;return point.matrixTransform(parent.getScreenCTM().inverse())}
function staffPositionForPitch(p){const degree=[0,0,1,1,2,3,3,4,4,5,5,6][((p%12)+12)%12];return (Math.floor(p/12)-1)*7+degree}
function noteEntries(measures){return measures.flatMap(m=>[...m.querySelectorAll('.note')].filter(n=>Number.isFinite(midiPitch(n))).map(n=>({note:n,measure:m,pitch:midiPitch(n)})))}
function markerGeometry(entry,pitch,clientX){const parent=entry.measure.parentNode;const rect=(entry.note.querySelector('.notehead')||entry.note).getBoundingClientRect();const center=screenPointIn(parent,rect.left+rect.width/2,rect.top+rect.height/2);const xEdge=screenPointIn(parent,rect.left+rect.width,rect.top+rect.height/2);const yEdge=screenPointIn(parent,rect.left+rect.width/2,rect.top+rect.height);const entries=[...entry.measure.querySelectorAll('.note')].filter(n=>Number.isFinite(midiPitch(n))).map(n=>{const r=(n.querySelector('.notehead')||n).getBoundingClientRect();return {pitch:midiPitch(n),position:staffPositionForPitch(midiPitch(n)),y:screenPointIn(parent,r.left+r.width/2,r.top+r.height/2).y}});const ratios=[];entries.forEach(a=>entries.forEach(b=>{const steps=Math.abs(staffPositionForPitch(a.pitch)-staffPositionForPitch(b.pitch));if(steps>0)ratios.push(Math.abs(a.y-b.y)/steps)}));ratios.sort((a,b)=>a-b);const staffStep=ratios.length?ratios[Math.floor(ratios.length/2)]:Math.max(70,Math.abs(yEdge.y-center.y)*1.4);return {parent:parent,x:screenPointIn(parent,clientX,rect.top+rect.height/2).x,y:center.y+(staffPositionForPitch(entry.pitch)-staffPositionForPitch(pitch))*staffStep,rx:Math.max(85,Math.abs(xEdge.x-center.x)*1.1),ry:Math.max(60,Math.abs(yEdge.y-center.y)*0.8)}}
function drawAttemptNotes(measures,attempted,expected,cursor){if(!attempted.length)return;const entries=noteEntries(measures);if(!entries.length)return;attempted.forEach(pitch=>{const reference=entries.reduce((best,n)=>Math.abs(n.pitch-pitch)<Math.abs(best.pitch-pitch)?n:best);const g=markerGeometry(reference,pitch,cursor.clientX);const correct=expected.includes(pitch);const marker=svgEl('ellipse');marker.setAttribute('class','wrong-note-marker');marker.setAttribute('cx',g.x);marker.setAttribute('cy',g.y);marker.setAttribute('rx',g.rx);marker.setAttribute('ry',g.ry);marker.setAttribute('fill',correct?'#16833b':'#d01818');marker.setAttribute('stroke',correct?'#0f5f29':'#8b0000');marker.setAttribute('stroke-width','12');marker.setAttribute('transform','rotate(-18 '+g.x+' '+g.y+')');g.parent.appendChild(marker)})}
function highlightNotes(measure,expected,accepted,attempted,tickInMeasure,measureTicks,alignExpected){document.querySelectorAll('.note').forEach(n=>colorNote(n,'black'));const measures=[...document.querySelectorAll('.measure[data-n="'+measure+'"]')];if(!measures.length){clearPracticeCursor();return}const timedFraction=Math.max(0,Math.min(1,tickInMeasure/measureTicks));const selection=visualSelection(measures[0],measures,expected,timedFraction);const cursor=drawPracticeCursor(measures[0],tickInMeasure,measureTicks,selection,alignExpected);const lineRect=cursor.line.getBoundingClientRect();cursor.clientX=lineRect.left+lineRect.width/2;selection.notes.forEach(e=>colorNote(e.note,accepted.includes(e.pitch)?'#16833b':'#1565c0'));drawAttemptNotes(measures,attempted,expected,cursor)}
let rangeSelectionMode='';
function setRangeSelectionMode(mode){rangeSelectionMode=mode;document.body.style.cursor=mode?'crosshair':'default'}
function showSelectedRange(fromTick,toTick,measureTicks){document.querySelectorAll('.range-highlight').forEach(e=>e.remove());if(fromTick===null&&toTick===null)return;const from=fromTick===null?0:fromTick;const to=toTick===null?Number.MAX_SAFE_INTEGER:toTick;document.querySelectorAll('.measure[data-n]').forEach(m=>{const number=parseInt(m.dataset.n);const start=(number-1)*measureTicks,end=number*measureTicks;const left=Math.max(start,from),right=Math.min(end,to);if(right<left||right<start||left>end)return;const box=m.getBBox(),parent=m.parentNode;const x1=box.x+box.width*Math.max(0,(left-start)/measureTicks);const x2=box.x+box.width*Math.min(1,(right-start)/measureTicks);const shade=svgEl('rect');shade.setAttribute('class','range-highlight');shade.setAttribute('x',x1);shade.setAttribute('y',box.y);shade.setAttribute('width',Math.max(18,x2-x1));shade.setAttribute('height',box.height);shade.setAttribute('fill','#687078');shade.setAttribute('opacity','0.16');parent.insertBefore(shade,m)})}
document.addEventListener('click',event=>{if(!rangeSelectionMode)return;const target=event.target;const note=target.closest?target.closest('.note'):null;const measure=(note||(target.closest?target.closest('.measure'):null));const owner=measure&&measure.classList.contains('measure')?measure:(measure&&measure.closest?measure.closest('.measure'):null);if(!owner)return;const rect=owner.getBoundingClientRect();const fraction=Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width));const pitch=note?midiPitch(note):-1;PianoTrainerBridge.selectPosition(parseInt(owner.dataset.n),fraction,Number.isFinite(pitch)?pitch:-1);event.preventDefault();event.stopPropagation()},true);
</script></body></html>"""
                        view.tag = key
                        view.loadScorePage(html, script)
                    } else {
                        view.applyPracticeScript(script)
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF7F5F0)).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(onClick = { page-- }, enabled = page > 0) { Text("Назад") }
                Button(onClick = { page++ }, enabled = page + 1 < score.normalPages.size) { Text("Далее") }
            }
            playbackError?.let { Text(it, color = Color(0xFFB3261E), modifier = Modifier.padding(horizontal = 12.dp)) }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("В этом пакете нет готовых SVG-страниц. Пересоберите его с --pages-dir.")
            }
        }
    }
}

private class ScoreWebView(context: Context) : WebView(context) {
    private var pendingPracticeScript = ""
    var onPositionSelected: (measure: Int, fraction: Double, pitch: Int) -> Unit = { _, _, _ -> }

    private inner class SelectionBridge {
        @JavascriptInterface
        fun selectPosition(measure: Int, fraction: Double, pitch: Int) {
            post { onPositionSelected(measure, fraction.coerceIn(0.0, 1.0), pitch) }
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
                applyPracticeScript(pendingPracticeScript)
            }
        }
    }

    fun loadScorePage(html: String, practiceScript: String) {
        pendingPracticeScript = practiceScript
        loadDataWithBaseURL("https://piano-trainer.local/", html, "text/html", "UTF-8", null)
    }

    fun applyPracticeScript(script: String) {
        pendingPracticeScript = script
        if (progress == 100 && script.isNotBlank()) evaluateJavascript(script, null)
    }
}

private fun clefStatus(label: String, expected: Set<Int>, accepted: Set<Int>): String {
    if (expected.isEmpty()) return "$label: —"
    val correct = expected.intersect(accepted).sorted()
    val remaining = expected.minus(accepted).sorted()
    return buildString {
        append("$label: ")
        if (correct.isNotEmpty()) append("верно ${correct.joinToString(" ", transform = ::midiSolfegeName)}")
        if (correct.isNotEmpty() && remaining.isNotEmpty()) append("; ")
        if (remaining.isNotEmpty()) append("ожидается ${remaining.joinToString(" ", transform = ::midiSolfegeName)}")
        if (remaining.isEmpty()) append("готово")
    }
}

private fun midiSolfegeName(note: Int): String {
    val names = arrayOf("Do", "Do♯", "Re", "Re♯", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "La♯", "Si")
    return "${names[note % 12]}${note / 12 - 1}"
}
