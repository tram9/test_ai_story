package com.example.test.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

// Chia khối nhỏ + gọi song song để rút ngắn thời gian. Chưa biết `ask` có giới hạn lượt/đồng thời không;
// nếu lỗi hoặc bị chặn thì giảm CHAT_PARALLEL về 1.
private const val CHAT_CHUNK = 1500
private const val CHAT_PARALLEL = 3

/** Dịch [text] (tự nhận ngôn ngữ nguồn) sang [language]. Ném exception nếu lỗi mạng/bị giới hạn. */
suspend fun translateTo(text: String, language: TranslateLanguage): String =
    if (USE_CHATBOT_TRANSLATE) translateViaChat(text, language)
    else translate(text, "auto", language.apiCode)

/**
 * Nhờ chatbot dịch bằng prompt. Chưa kiểm chứng: chất lượng, độ dài tối đa của `text`,
 * và việc bot có chèn lời dẫn ("Sure, here is…") hay không — prompt đã yêu cầu chỉ trả bản dịch.
 */
private suspend fun translateViaChat(text: String, language: TranslateLanguage): String {
    if (text.isBlank()) return text
    val gate = Semaphore(CHAT_PARALLEL)
    return coroutineScope {
        chunkByLines(text, CHAT_CHUNK).map { chunk ->
            async {
                gate.withPermit {
                    ask(
                        "Translate the following text into ${language.name}. " +
                            "Keep the paragraph breaks. Output ONLY the translation, with no explanation or notes.\n\n$chunk"
                    )
                }
            }
        }.awaitAll().joinToString("\n")   // awaitAll giữ đúng thứ tự khối
    }
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

/** Gom các dòng thành chunk <= [MAX_CHUNK] ký tự, giữ nguyên xuống dòng. */
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
