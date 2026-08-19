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

    fun practiceGroups(score: ScorePackage): List<ExpectedGroup> = ZipFile(score.file).use { archive ->
        val entry = archive.getEntry("mapping.json") ?: error("В пакете нет MIDI mapping")
        val events = archive.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()).getJSONArray("events") }
        val groups = linkedMapOf<String, PracticeGroupBuilder>()
        repeat(events.length()) { index ->
            val event = events.getJSONObject(index)
            if (event.optString("hand") == "excluded") return@repeat
            val id = event.getString("expectedGroupId")
            val group = groups.getOrPut(id) { PracticeGroupBuilder(event.getLong("onTick")) }
            val pitch = event.getInt("pitch")
            if (event.optString("hand") == "left") group.left += pitch else group.right += pitch
        }
        groups.map { (id, value) -> ExpectedGroup(id, value.tick, value.left, value.right) }
    }

    fun practicePpq(score: ScorePackage): Int = ZipFile(score.file).use { archive ->
        val entry = archive.getEntry("mapping.json") ?: error("В пакете нет MIDI mapping")
        archive.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()).optInt("ppq", 480) }
    }

    private fun readPackage(file: File): ScorePackage? = runCatching {
        ZipFile(file).use { archive ->
            val manifestEntry = archive.getEntry("manifest.json") ?: error("В пакете нет manifest.json")
            val manifest = archive.getInputStream(manifestEntry).bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.optInt("schemaVersion") == 1) { "Неподдерживаемая версия пакета" }
            val id = manifest.getString("id")
            val title = manifest.getString("title")
            val requiredFiles = listOf(
                manifest.getString("sourceMidi"),
                manifest.getString("score"),
                manifest.getString("mapping"),
            )
            val checksums = manifest.optJSONObject("sha256") ?: error("В пакете нет контрольных сумм")
            requiredFiles.forEach { name ->
                val entry = archive.getEntry(name) ?: error("В пакете нет $name")
                val expected = checksums.getString(name)
                val actual = archive.getInputStream(entry).use(::sha256)
                require(expected.equals(actual, ignoreCase = true)) { "Контрольная сумма $name не совпадает" }
            }
            val normalPages = manifest.optJSONObject("pages")
                ?.optJSONArray("normal")
                ?.let { pages -> List(pages.length()) { index -> pages.getString(index) } }
                .orEmpty()
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
    val left: MutableSet<Int> = linkedSetOf(),
    val right: MutableSet<Int> = linkedSetOf(),
)
