package com.konstantin.pianotrainer

import android.content.Context

enum class AppLanguage(val code: String, val toggleLabel: String) {
    ENGLISH("en", "EN"),
    RUSSIAN("ru", "RU");

    val isRussian get() = this == RUSSIAN

    companion object {
        fun load(context: Context): AppLanguage = when (
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", ENGLISH.code)
        ) {
            RUSSIAN.code -> RUSSIAN
            else -> ENGLISH
        }

        fun save(context: Context, language: AppLanguage) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString("language", language.code).apply()
        }
    }
}

/** Small, explicit catalog for the Compose-only UI. English is the product default. */
class Strings(private val language: AppLanguage) {
    private val ru get() = language.isRussian

    fun text(english: String, russian: String) = if (ru) russian else english
    fun pages(count: Int) = text("$count pages", "$count стр.")
    fun noPages() = text("No SVG pages available", "Нужны SVG-страницы")
    fun pageCounter(page: Int, count: Int) = text("${page + 1}/$count", "${page + 1}/$count")
    fun scoreDeleted(title: String) = text(
        "“$title” will be deleted from this tablet. The source file on your Mac will not change.",
        "«$title» будет удалена с планшета. Исходный файл на Mac не изменится.",
    )
    fun practiceResult(correct: Int, missed: Int) = text("Result: $correct correct, $missed missed", "Итог: верно $correct, пропущено $missed")
    fun completed(total: Int) = text("Complete: $total positions played", "Готово: $total позиций сыграно")
    fun score(correct: Int, total: Int) = text("Score $correct/$total", "Оценка $correct/$total")
    fun mistake(notes: String) = text("Mistake: $notes", "Ошибка: $notes")
    fun clefStatus(label: String, expected: Set<Int>, accepted: Set<Int>): String {
        if (expected.isEmpty()) return "$label: —"
        val correct = expected.intersect(accepted).sorted()
        val remaining = expected.minus(accepted).sorted()
        return buildString {
            append("$label: ")
            if (correct.isNotEmpty()) append(text("correct ", "верно ") + correct.joinToString(" ", transform = ::midiSolfegeName))
            if (correct.isNotEmpty() && remaining.isNotEmpty()) append("; ")
            if (remaining.isNotEmpty()) append(text("waiting for ", "ожидается ") + remaining.joinToString(" ", transform = ::midiSolfegeName))
            if (remaining.isEmpty()) append(text("done", "готово"))
        }
    }
}
