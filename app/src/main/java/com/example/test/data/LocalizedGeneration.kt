package com.example.test.data

import java.util.Locale

/**
 * Ngôn ngữ app hiện tại, theo ngôn ngữ máy. Project này không dùng AppCompat nên không đọc
 * per-app language — nếu dự án thật cho người dùng tự chọn ngôn ngữ riêng trong app (khác
 * ngôn ngữ máy), ưu tiên đọc `AppCompatDelegate.getApplicationLocales()` trước, xem phần
 * TICH_HOP_PRODUCTION.md mục "Lấy ngôn ngữ app hiện tại" để có đoạn code đầy đủ.
 */
fun currentAppLanguageCode(): String = Locale.getDefault().language

/**
 * Ngôn ngữ đích để dịch, hoặc `null` nếu không cần dịch (app đang ở tiếng Anh).
 * Ngôn ngữ lạ không có sẵn tên hiển thị vẫn thử dịch bằng chính mã ISO đó.
 */
private fun targetLanguageOrNull(): TranslateLanguage? {
    val code = currentAppLanguageCode()
    if (code.equals("en", ignoreCase = true)) return null
    return TRANSLATE_LANGUAGES.firstOrNull { it.code.equals(code, ignoreCase = true) }
        ?: TranslateLanguage(code, code, "🏳", apiCode = code)
}

/** Dịch sang ngôn ngữ app hiện tại; không cần dịch hoặc dịch lỗi thì trả nguyên văn, không ném exception. */
private suspend fun localize(text: String): String {
    val lang = targetLanguageOrNull() ?: return text
    return runCatching { translateTo(text, lang) }.getOrDefault(text)
}

/** Truyện nhiều chương, bước 1: tạo dàn ý rồi dịch tên chương theo ngôn ngữ app hiện tại. */
suspend fun splitChapterLocalized(
    title: String,
    numChapter: String,
    topic: String,
    genre: String,
    onProgress: (String) -> Unit = {}
): NovelOutline {
    val o = splitChapter(title, numChapter, topic, genre, onProgress)
    if (o.errorCode != 0 || o.chapters.isEmpty()) return o

    val lang = targetLanguageOrNull() ?: return o
    val translatedTitles = runCatching { translateTo(o.chapters.joinToString("\n") { it.title }, lang) }
        .getOrNull()
        ?.lines()
        ?.filter { it.isNotBlank() }
        ?.takeIf { it.size == o.chapters.size }
        ?: return o   // dịch lỗi hoặc lệch số dòng -> giữ nguyên bản gốc, không chặn job

    return o.copy(chapters = o.chapters.mapIndexed { i, ch -> ch.copy(title = translatedTitles[i]) })
}

/** Truyện nhiều chương, bước 2: sinh 1 chương rồi dịch luôn nội dung theo ngôn ngữ app hiện tại. */
suspend fun generateChapterLocalized(novelId: String, index: Int): ChapterResult {
    val r = generateChapter(novelId, index)
    if (r.errorCode != 0 || r.content.isBlank()) return r
    return r.copy(content = localize(r.content))
}
