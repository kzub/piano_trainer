package com.konstantin.pianotrainer

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ScorePackage(
    val id: String,
    val title: String,
    val file: File,
    val normalPages: List<String>,
)

data class ScoreMeasure(
    val number: Int,
    val startTick: Long,
    val durationTicks: Long,
)

data class PracticeTimeline(
    val ppq: Int,
    val measures: List<ScoreMeasure>,
    val groups: List<ExpectedGroup>,
) {
    fun measureAt(tick: Long): ScoreMeasure? = measures.lastOrNull { it.startTick <= tick }
}

class ScorePackageRepository(private val context: Context) {
    private val scoreDirectory = File(context.filesDir, "scores").apply { mkdirs() }

    fun list(): List<ScorePackage> = scoreDirectory.listFiles { file -> file.extension == "pianoscore" }
        ?.mapNotNull(::readPackage)
        ?.sortedBy { it.title.lowercase() }
        .orEmpty()

    fun import(uri: Uri): ScorePackage {
        val temporary = File.createTempFile("import-", ".pianoscore", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use(input::copyTo)
            } ?: error("Не удалось открыть выбранный файл")

            val packageInfo = readPackage(temporary) ?: error("Файл не является корректным .pianoscore")
            val target = File(scoreDirectory, "${packageInfo.id}.pianoscore")
            temporary.copyTo(target, overwrite = true)
            return packageInfo.copy(file = target)
        } finally {
            temporary.delete()
        }
    }

    fun delete(score: ScorePackage) {
        require(score.file.parentFile?.canonicalFile == scoreDirectory.canonicalFile) { "Некорректный путь партитуры" }
        check(!score.file.exists() || score.file.delete()) { "Не удалось удалить партитуру" }
    }

    fun pageSvg(score: ScorePackage, page: Int): String {
        val pageName = score.normalPages.getOrNull(page) ?: error("Страница не найдена")
        return ZipFile(score.file).use { archive ->
            val entry = archive.getEntry(pageName) ?: error("В пакете нет $pageName")
            archive.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }

    fun pageForMeasure(score: ScorePackage, measure: Int): Int? {
        val marker = "data-n=\"$measure\""
        return score.normalPages.indices.firstOrNull { pageSvg(score, it).contains(marker) }
    }

    fun sourceMidi(score: ScorePackage): ByteArray = ZipFile(score.file).use { archive ->
        archive.getEntry("source.mid")?.let(archive::getInputStream)?.use { it.readBytes() }
            ?: error("В пакете нет исходного MIDI")
    }

    fun practiceTimeline(score: ScorePackage): PracticeTimeline = ZipFile(score.file).use { archive ->
        val entry = archive.getEntry("mapping.json") ?: error("В пакете нет MIDI mapping")
        val mapping = archive.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
        val events = mapping.getJSONArray("events")
        val eventList = List(events.length()) { index -> events.getJSONObject(index) }
        val sourceHands = if (mapping.optString("kind") == "timeline-only") {
            inferDominantTimelineHands(eventList.mapNotNull { event ->
                if (!event.has("track") || !event.has("channel")) return@mapNotNull null
                TimelineHandObservation(
                    source = MidiSource(event.getInt("track"), event.getInt("channel")),
                    declaredHand = event.optString("hand"),
                )
            })
        } else {
            emptyMap()
        }
        val measures = mapping.optJSONArray("measures")?.let { values ->
            List(values.length()) { index ->
                values.getJSONObject(index).let { measure ->
                    ScoreMeasure(
                        number = measure.getInt("number"),
                        startTick = measure.getLong("startTick"),
                        durationTicks = measure.getLong("durationTicks"),
                    )
                }
            }
        }.orEmpty()
        val groups = linkedMapOf<String, PracticeGroupBuilder>()
        eventList.forEach { event ->
            if (event.optString("hand") == "excluded") return@forEach
            val id = event.getString("expectedGroupId")
            val tick = event.getLong("onTick")
            val group = groups.getOrPut(id) {
                PracticeGroupBuilder(tick = tick, measure = event.optInt("measure").takeIf { it > 0 })
            }
            require(group.tick == tick) { "Ожидаемая группа $id содержит разные моменты времени" }
            val pitch = event.getInt("pitch")
            val source = if (event.has("track") && event.has("channel")) {
                MidiSource(event.getInt("track"), event.getInt("channel"))
            } else {
                null
            }
            val hand = source?.let(sourceHands::get) ?: event.optString("hand")
            val scoreIds = event.optJSONArray("scoreNoteIds")?.let { ids ->
                (0 until ids.length()).mapTo(linkedSetOf()) { ids.getString(it) }
            }.orEmpty()
            if (hand == "left") {
                group.left += pitch
                group.leftScoreNoteIds += scoreIds
                group.leftScoreNoteIdsByPitch.getOrPut(pitch) { linkedSetOf() } += scoreIds
            } else {
                group.right += pitch
                group.rightScoreNoteIds += scoreIds
                group.rightScoreNoteIdsByPitch.getOrPut(pitch) { linkedSetOf() } += scoreIds
            }
        }
        PracticeTimeline(
            ppq = mapping.optInt("ppq", 480),
            measures = measures,
            groups = groups.map { (id, value) ->
                ExpectedGroup(
                    id, value.tick, value.left, value.right, value.measure,
                    value.leftScoreNoteIds, value.rightScoreNoteIds,
                    value.leftScoreNoteIdsByPitch, value.rightScoreNoteIdsByPitch,
                )
            }.sortedWith(compareBy<ExpectedGroup> { it.tick }.thenBy { it.id }),
        )
    }

    fun practiceGroups(score: ScorePackage): List<ExpectedGroup> = practiceTimeline(score).groups

    fun practicePpq(score: ScorePackage): Int = practiceTimeline(score).ppq

    private fun readPackage(file: File): ScorePackage? = runCatching {
        ZipFile(file).use { archive ->
            val manifestEntry = archive.getEntry("manifest.json") ?: error("В пакете нет manifest.json")
            val manifest = archive.getInputStream(manifestEntry).bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.optInt("schemaVersion") in 1..3) { "Неподдерживаемая версия пакета" }
            val id = manifest.getString("id")
            val title = manifest.getString("title")
            val requiredFiles = listOf(
                manifest.getString("sourceMidi"),
                manifest.getString("score"),
                manifest.getString("mapping"),
            )
            val checksums = manifest.optJSONObject("sha256") ?: error("В пакете нет контрольных сумм")
            val normalPages = manifest.optJSONObject("pages")
                ?.optJSONArray("normal")
                ?.let { pages -> List(pages.length()) { index -> pages.getString(index) } }
                .orEmpty()
            require(normalPages.isNotEmpty()) { "В пакете нет страниц партитуры" }
            (requiredFiles + normalPages).distinct().forEach { name ->
                val entry = archive.getEntry(name) ?: error("В пакете нет $name")
                val expected = checksums.getString(name)
                val actual = archive.getInputStream(entry).use(::sha256)
                require(expected.equals(actual, ignoreCase = true)) { "Контрольная сумма $name не совпадает" }
            }
            ScorePackage(id = id, title = title, file = file, normalPages = normalPages)
        }
    }.getOrNull()

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

private data class PracticeGroupBuilder(
    val tick: Long,
    val measure: Int?,
    val left: MutableSet<Int> = linkedSetOf(),
    val right: MutableSet<Int> = linkedSetOf(),
    val leftScoreNoteIds: MutableSet<String> = linkedSetOf(),
    val rightScoreNoteIds: MutableSet<String> = linkedSetOf(),
    val leftScoreNoteIdsByPitch: MutableMap<Int, MutableSet<String>> = linkedMapOf(),
    val rightScoreNoteIdsByPitch: MutableMap<Int, MutableSet<String>> = linkedMapOf(),
)
