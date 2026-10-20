package com.example.test.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

const val MAX_CHAPTERS = 15

/** Gợi ý genre (ô genre vẫn nhập tự do). */
val GENRE_SUGGESTIONS = listOf(
    "Fantasy", "Romance", "Sci-fi", "Adventure",
    "Horror", "Mystery", "Story for kid", "Mythology"
)

/** 4 field người dùng nhập (nội dung nhập gửi vào `prompt`, `topic` để rỗng); các field còn lại rỗng/mặc định. */
fun buildNovelFields(title: String, numChapter: String, prompt: String, genre: String): Map<String, String> =
    linkedMapOf(
        "title" to title,
        "num_chapter" to (numChapter.toIntOrNull() ?: 1).coerceIn(1, MAX_CHAPTERS).toString(),
        "topic" to "",
        "genre" to genre,
        "writing_style" to "",
        "characters" to "",
        "world" to "",
        "plot" to "",
        "prompt" to prompt,
        "width" to "512",
        "height" to "768",
        "negative_prompt" to ""
    )

/**
 * Truyện nhiều chương — `POST api/split_chapter` (dàn ý + ảnh bìa, có thể mất tới 300s)
 * rồi `POST api/gen_chapter` cho từng chương.
 */
suspend fun splitChapter(params: Map<String, String>): NovelOutline = withContext(Dispatchers.IO) {
    val requested = params["num_chapter"]?.toIntOrNull() ?: 0
    val obj = postEncrypted("$BASE_NOVEL/api/split_chapter", params, KEY_NOVEL, TIMEOUT_SPLIT_MS)
    val code = readErrorCode(obj)
    if (code != 0) {
        return@withContext NovelOutline(code, readErrorMsg(obj).ifBlank { "unknown" }, "", emptyList(), null, obj.toString(2), requested)
    }
    val userId = obj.optString("userId").ifBlank { obj.optString("user_id") }
    val coverPath = obj.optString("url")
    val cover = if (coverPath.isNotBlank()) downloadImage(absoluteUrl(coverPath)) else null
    NovelOutline(0, "", userId, parseChapterTitles(obj.opt("data")), cover, obj.toString(2), requested)
}

/** Sinh nội dung 1 chương; trả về `data.content`. */
suspend fun genChapter(userId: String, chapterTitle: String): ChapterResult = withContext(Dispatchers.IO) {
    val obj = postEncrypted(
        "$BASE_NOVEL/api/gen_chapter",
        linkedMapOf("user_id" to userId, "title" to chapterTitle),
        KEY_NOVEL
    )
    val code = readErrorCode(obj)
    if (code != 0) return@withContext ChapterResult(code, readErrorMsg(obj).ifBlank { "unknown" }, "")
    ChapterResult(0, "", obj.optJSONObject("data")?.optString("content", "")?.trim().orEmpty())
}

private fun absoluteUrl(path: String) = if (path.startsWith("http")) path else BASE_NOVEL + path

private val TITLE_KEYS = listOf("title", "chapter_title", "name", "chapter")
private val LIST_KEYS = listOf("chapters", "outline", "chapter_list", "list")

/**
 * Cấu trúc `data` của split_chapter chưa được tài liệu mô tả rõ, nên parse "mềm":
 * mảng chuỗi / mảng object có title / object chứa mảng ở các khóa thường gặp.
 * Nếu không nhận ra thì trả rỗng — UI vẫn hiển thị JSON gốc để đối chiếu.
 */
internal fun parseChapterTitles(data: Any?): List<String> = when (data) {
    is JSONArray -> (0 until data.length()).map { titleOf(data.get(it)) ?: "Chapter ${it + 1}" }
    is JSONObject -> {
        val arr = LIST_KEYS.firstNotNullOfOrNull { data.optJSONArray(it) }
            ?: data.keys().asSequence().firstNotNullOfOrNull { data.optJSONArray(it) }
        if (arr != null) parseChapterTitles(arr) else emptyList()
    }
    is String -> {
        val t = data.trim()
        val parsed = if (t.startsWith("[") || t.startsWith("{")) {
            runCatching { parseChapterTitles(org.json.JSONTokener(t).nextValue()) }.getOrDefault(emptyList())
        } else emptyList()
        parsed.ifEmpty { t.lines().map { it.trim() }.filter { it.isNotEmpty() } }
    }
    else -> emptyList()
}

private fun titleOf(item: Any?): String? = when (item) {
    is String -> item.takeIf { it.isNotBlank() }
    is JSONObject -> TITLE_KEYS.firstNotNullOfOrNull { k -> item.optString(k).takeIf { it.isNotBlank() } }
        ?: item.keys().asSequence().firstNotNullOfOrNull { k -> (item.opt(k) as? String)?.takeIf { it.isNotBlank() } }
    else -> null
}
