package com.example.test.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Endpoint dịch miễn phí, không chính thức của Google (không cần key, không cần tải model).
// Cần User-Agent giống trình duyệt, nếu không dễ bị trả 429/trang "Sorry...".
private const val TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
private const val MAX_CHUNK = 3000

/** Dịch bằng chatbot `api/ask` (true) hay endpoint Google không chính thức (false). */
private const val USE_CHATBOT_TRANSLATE = true

// Chia văn bản thành các đoạn để model dịch chắc tay từng đoạn, nhưng TOÀN BỘ các đoạn được gộp
// vào một mảng JSON và gửi trong MỘT lần gọi `ask` duy nhất (xem translateViaChat), thay vì mỗi
// đoạn một lần gọi riêng như trước. Nếu model không trả đúng mảng, rơi về cách gọi từng đoạn song
// song (translateChunksFallback) để không mất bản dịch.
private const val CHAT_SEGMENT_CHUNK = 1200
private const val CHAT_PARALLEL = 3

/** Dịch [text] (tự nhận ngôn ngữ nguồn) sang [language]. Ném exception nếu lỗi mạng/bị giới hạn. */
suspend fun translateTo(text: String, language: TranslateLanguage): String =
    if (USE_CHATBOT_TRANSLATE) translateViaChat(text, language)
    else translate(text, "auto", language.apiCode)

/**
 * Dịch bằng chatbot `api/ask`: gộp toàn bộ các đoạn thành MỘT mảng JSON, yêu cầu model trả về
 * đúng một mảng JSON cùng số phần tử, cùng thứ tự — chỉ MỘT lần gọi mạng cho cả văn bản dài,
 * thay vì một lần gọi cho mỗi đoạn. Lấy theo cách project tham khảo AIStoryApp dịch outline của họ
 * (gộp cả outline thành 1 JSON, dịch 1 lần) — tôi chưa tự đo lại tốc độ với server thật.
 *
 * Chưa kiểm chứng: model có luôn tuân thủ trả đúng định dạng/số phần tử không, đặc biệt với văn bản
 * rất dài (nhiều đoạn). Khi parse thất bại hoặc số phần tử lệch, tự rơi về [translateChunksFallback].
 */
private suspend fun translateViaChat(text: String, language: TranslateLanguage): String {
    if (text.isBlank()) return text
    val segments = chunkByLines(text, CHAT_SEGMENT_CHUNK)
    if (segments.size == 1) return askTranslate(segments[0], language)

    val requestArray = JSONArray().apply { segments.forEach { put(it) } }
    val prompt = """
        Translate each element of the following JSON array into ${language.name}. Keep paragraph breaks inside each element. Preserve the exact array length and order.
        $requestArray

        Output ONLY a single JSON array of ${segments.size} strings, in the same order, with the translated text in each position. No explanation, no markdown, no extra text.
    """.trimIndent()

    val translated = runCatching { parseTranslatedArray(ask(prompt), segments.size) }.getOrNull()
    return (translated ?: translateChunksFallback(segments, language)).joinToString("\n")
}

private suspend fun askTranslate(text: String, language: TranslateLanguage): String =
    ask(
        "Translate the following text into ${language.name}. " +
            "Keep the paragraph breaks. Output ONLY the translation, with no explanation or notes.\n\n$text"
    )

/** Cách cũ: mỗi đoạn một lần gọi, chạy song song tối đa [CHAT_PARALLEL] đoạn. Chỉ dùng khi dịch gộp thất bại. */
private suspend fun translateChunksFallback(segments: List<String>, language: TranslateLanguage): List<String> =
    coroutineScope {
        val gate = Semaphore(CHAT_PARALLEL)
        segments.map { chunk -> async { gate.withPermit { askTranslate(chunk, language) } } }.awaitAll()
    }

/** Bóc mảng JSON đầu tiên trong [raw] và kiểm tra đúng [expectedSize] phần tử; sai thì trả null. */
private fun parseTranslatedArray(raw: String, expectedSize: Int): List<String>? {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start == -1 || end <= start) return null
    val arr = runCatching {
        JSONTokener(sanitizeJsonStrings(raw.substring(start, end + 1))).nextValue() as? JSONArray
    }.getOrNull() ?: return null
    if (arr.length() != expectedSize) return null
    return (0 until arr.length()).map { arr.optString(it, "") }
}

/** Model hay chèn xuống dòng/tab thật bên trong chuỗi JSON, làm parser lỗi — escape lại trước khi parse. */
private fun sanitizeJsonStrings(text: String): String {
    val out = StringBuilder(text.length + 32)
    var inString = false
    var escaped = false
    for (ch in text) {
        if (ch == '\\' && !escaped) {
            escaped = true
            out.append(ch)
            continue
        }
        when {
            ch == '"' && !escaped -> { inString = !inString; out.append(ch) }
            inString && ch == '\n' -> out.append("\\n")
            inString && ch == '\r' -> {}
            inString && ch == '\t' -> out.append("\\t")
            else -> out.append(ch)
        }
        escaped = false
    }
    return out.toString()
}

private fun translateChunk(text: String, from: String, to: String): String {
    val body = "client=gtx&sl=$from&tl=$to&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
    val conn = (URL(TRANSLATE_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 30_000
        readTimeout = 30_000
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        setRequestProperty("User-Agent", USER_AGENT)
    }
    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body); it.flush() }
    check(conn.responseCode in 200..299) { "Dịch thất bại (HTTP ${conn.responseCode})" }
    val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val segments: JSONArray = JSONArray(json).getJSONArray(0)
    return buildString {
        for (i in 0 until segments.length()) append(segments.getJSONArray(i).getString(0))
    }
}

/** Gom các dòng thành chunk <= [maxChunk] ký tự, giữ nguyên xuống dòng. */
private fun chunkByLines(text: String, maxChunk: Int = MAX_CHUNK): List<String> {
    val chunks = mutableListOf<String>()
    val cur = StringBuilder()
    for (line in text.split("\n")) {
        if (cur.isNotEmpty() && cur.length + line.length + 1 > maxChunk) {
            chunks += cur.toString(); cur.clear()
        }
        if (cur.isNotEmpty()) cur.append('\n')
        cur.append(line)
    }
    if (cur.isNotEmpty()) chunks += cur.toString()
    return chunks
}

/** Dịch text dài bằng endpoint Google: dịch lần lượt từng chunk. */
private suspend fun translate(text: String, from: String, to: String): String {
    if (text.isBlank()) return text
    return withContext(Dispatchers.IO) {
        chunkByLines(text).joinToString("\n") { translateChunk(it, from, to) }
    }
}
