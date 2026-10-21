# Hướng dẫn triển khai API sinh truyện cho dự án thật

File này viết để đưa cho Claude (hoặc kỹ sư khác) làm theo khi tích hợp vào **một project Android khác**
với project test này. **Phạm vi chỉ gồm 2 việc** (đúng những gì dự án thật sẽ dùng):

1. **Truyện nhiều chương** — qua server trung gian của bạn (`sp155-ai-story.pandaglobal.top`).
2. **Dịch** — gọi thẳng `gptchatapi.breakerai.com/api/ask`.

Không có truyện ngắn (`generate_story_v2`/`storygenapi.breakerai.com`) — phần đó app test có nhưng dự án
thật không dùng, nên bỏ hẳn khỏi tài liệu này.

**Cách dịch:** không có nút/menu chọn ngôn ngữ. Dịch chạy **ngay sau khi gen xong**, tự động, theo đúng
ngôn ngữ app đang hiển thị cho người dùng. Không cần sửa gì ở proxy — vẫn đúng 3 endpoint, đúng 4 field
`title, numChapter, topic, genre` như hiện tại, không thêm field `language` nào. Việc dịch do **client tự
gọi thêm** sang chatbot sau khi nhận nội dung tiếng Anh từ proxy.

Mọi code dưới đây **đã được dựng và biên dịch thành công** trong project test (`com.example.test`) —
là code thật, không phải viết lại tay.

---

## 0. Kiến trúc

```
App --JSON/https--> sp155-ai-story.pandaglobal.top (proxy của bạn) --> novelv2.breakerai.com
App --form/AES/http--> gptchatapi.breakerai.com/api/ask   (dịch, GỌI THẲNG, không qua proxy)
```

> ⚠️ **Lưu ý rủi ro còn lại:** Luồng truyện nhiều chương đi qua proxy của bạn nên app không giữ key AES
> nào cho phần đó. Nhưng luồng **dịch** vẫn gọi thẳng `gptchatapi.breakerai.com`, nên **key AES
> `K1k2hH2uZF96ik5n` nằm trong APK** — ai giải nén cũng lấy được, và app gọi trực tiếp một backend bên
> thứ ba chưa có thỏa thuận sử dụng (xem `server_api.md` mục 8 nếu còn giữ). Chấp nhận được cho bản
> test/demo nội bộ; muốn giấu luôn key này thì phải làm thêm 1 route dịch trên chính proxy của bạn
> (client gửi text + ngôn ngữ đích lên proxy, proxy gọi chatbot hộ) — đó là việc khác, không nằm trong
> yêu cầu lần này.

---

## 1. Cấu hình & hằng số

```kotlin
package com.example.test.data

const val BASE_STORY = "http://storygenapi.breakerai.com"
const val BASE_CHAT = "http://gptchatapi.breakerai.com"

const val KEY_STORY_V2 = "K1k2hH2uZF96ik9M"   // generate_story_v2
const val KEY_CHAT = "K1k2hH2uZF96ik5n"       // api/ask (chatbot)

const val TIMEOUT_DEFAULT_MS = 60_000
const val TIMEOUT_CHAPTER_MS = 90_000         // sinh 1 chương qua proxy, API gốc phía sau timeout 60s

// Server trung gian của người dùng (server_api.md) — truyện nhiều chương đi qua đây,
// không gọi thẳng novelv2.breakerai.com nữa. Điền token thật vào đây nếu server yêu cầu
// `Authorization: Bearer <token>`; để trống thì không gửi header này.
const val PROXY_BASE_URL = "https://sp155-ai-story.pandaglobal.top"
const val PROXY_TOKEN = ""

const val POLL_INTERVAL_MS = 4_000L           // tần suất hỏi GET /v1/jobs/{id}, theo gợi ý 3–5s của server_api.md
const val JOB_TIMEOUT_MS = 12 * 60 * 1000L    // client tự bỏ cuộc sớm hơn mốc 10 phút server tự hủy job
```

Đổi khi mang sang project khác:
- `PROXY_BASE_URL`: domain proxy thật của bạn.
- `PROXY_TOKEN`: điền token nếu proxy có bật `Authorization: Bearer <token>`; để trống nếu chưa bật.

> File gốc còn `KEY_STORY_V2`/`BASE_STORY` cho truyện ngắn — **bỏ 2 dòng đó** khi copy sang dự án thật
> vì không dùng tới.

---

## 2. Lớp HTTP & giải mã dùng chung

`StoryCrypto.kt` — giải mã AES, chỉ còn cần cho `ChatApi.kt` (dịch) ở dự án thật:
```kotlin
package com.example.test.data

import android.util.Base64
import org.json.JSONTokener
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bóc cặp ngoặc kép của chuỗi JSON ("abc" -> abc). */
fun stripJsonString(raw: String): String {
    val t = raw.trim()
    return if (t.startsWith("\"")) JSONTokener(t).nextValue() as String else t
}

/** AES/CBC/NoPadding: 16 ký tự đầu = IV, phần còn lại = Base64 ciphertext. Key tùy endpoint. */
fun decrypt(cipherText: String, key: String): String {
    val iv = cipherText.substring(0, 16).toByteArray(Charsets.UTF_8)
    val ct = Base64.decode(cipherText.substring(16), Base64.DEFAULT)
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
        IvParameterSpec(iv)
    )
    return String(cipher.doFinal(ct), Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r', '\t')
}
```

`HttpUtil.kt` — gộp cả 2 kiểu gọi: `postForm`/`postEncrypted` (form + AES, cho `ChatApi.kt` gọi thẳng
breakerai) và `postJson`/`getJson` (JSON, cho `NovelApi.kt` gọi proxy của bạn):
```kotlin
package com.example.test.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** POST application/x-www-form-urlencoded; trả body String (đọc cả errorStream khi HTTP 500). */
fun postForm(urlStr: String, params: Map<String, String>, timeoutMs: Int = TIMEOUT_DEFAULT_MS): String {
    val body = params.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body); it.flush() }
    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
    return stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
}

/** POST form + bóc ngoặc kép + giải mã bằng [key] -> JSONObject. */
fun postEncrypted(
    urlStr: String,
    params: Map<String, String>,
    key: String,
    timeoutMs: Int = TIMEOUT_DEFAULT_MS
): JSONObject = JSONObject(decrypt(stripJsonString(postForm(urlStr, params, timeoutMs)), key))

/** v2 trả `error_code` (snake_case), endpoint mới trả `errorCode` (camelCase). */
fun readErrorCode(o: JSONObject): Int =
    if (o.has("error_code")) o.optInt("error_code", -1) else o.optInt("errorCode", -1)

fun readErrorMsg(o: JSONObject): String =
    if (o.has("error_message")) o.optString("error_message") else o.optString("errorMessage")

/** Lỗi/timeout thì trả null thay vì ném exception — ảnh bìa là phụ, không nên làm hỏng cả luồng chính. */
fun downloadImage(urlStr: String): ImageBitmap? = try {
    (URL(urlStr).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 15_000
        inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }
} catch (e: Exception) { null }

/** Lỗi server trung gian của mình, dạng `{"error": {"code","message","retryable"}}` (server_api.md mục 3.5). */
class ProxyException(val code: String, message: String, val retryable: Boolean) : Exception(message)

/**
 * Gọi server trung gian của mình (JSON, https) — khác với [postForm]/[postEncrypted] vốn gọi thẳng
 * API gốc (form + AES). HTTP lỗi thì đọc body theo mục 3.5 và ném [ProxyException]; không đọc được
 * JSON thì ném [java.io.IOException] thường.
 */
private fun proxyRequest(
    urlStr: String,
    method: String,
    body: JSONObject?,
    timeoutMs: Int,
    extraHeaders: Map<String, String>
): JSONObject {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("Accept", "application/json")
        if (PROXY_TOKEN.isNotBlank()) setRequestProperty("Authorization", "Bearer $PROXY_TOKEN")
        extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }
    body?.let { b -> OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(b.toString()); it.flush() } }

    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    val json = runCatching { JSONObject(text) }.getOrNull()

    if (code !in 200..299) {
        val err = json?.optJSONObject("error")
        throw ProxyException(
            code = err?.optString("code")?.takeIf { it.isNotBlank() } ?: "HTTP_$code",
            message = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Lỗi server (HTTP $code)",
            retryable = err?.optBoolean("retryable", false) ?: false
        )
    }
    return json ?: throw java.io.IOException("Server trung gian trả về nội dung không phải JSON hợp lệ")
}

fun postJson(
    urlStr: String,
    body: JSONObject,
    timeoutMs: Int = TIMEOUT_DEFAULT_MS,
    extraHeaders: Map<String, String> = emptyMap()
): JSONObject = proxyRequest(urlStr, "POST", body, timeoutMs, extraHeaders)

fun getJson(urlStr: String, timeoutMs: Int = TIMEOUT_DEFAULT_MS): JSONObject =
    proxyRequest(urlStr, "GET", null, timeoutMs, emptyMap())
```

> `downloadImage()` trong file này chỉ dùng cho truyện ngắn (ảnh bìa AI sinh) — không cần ở dự án thật
> vì `NovelApi.kt` (mục 4) đã không tải `coverUrl` qua mạng nữa (xem ghi chú ngay trong file). Có thể
> bỏ hàm này nếu không dùng ảnh mạng ở đâu khác.

---

## 3. Model dữ liệu

Chỉ giữ phần liên quan truyện nhiều chương; **bỏ** `StoryResult` và các danh sách
`STORY_TYPES`/`GENRES`/`ENDINGS`/`COVER_STYLES` (đồ của truyện ngắn):

```kotlin
package com.example.test.data

import androidx.compose.ui.graphics.ImageBitmap

// Giá trị hợp lệ trích từ app
val STORY_TYPES = listOf("short", "long")
val GENRES = listOf("Adventure", "Story for kid", "Romance", "Fantasy", "Sci-fi", "Mythology")
val ENDINGS = listOf("Happy Ending", "Sad Ending", "Open Ending")
val COVER_STYLES = listOf(
    "3D Character", "Anime Portrait", "Anime World", "Character", "Comics Portrait",
    "Cyberpunk Portrait", "Cyberpunk World", "Fantasy Portrait", "Fantasy World", "Fantasy",
    "Pixel Art", "Pop Art", "Renaissance", "Steampunk", "Street Art", "Surreal Portrait", "Watercolor"
)

data class StoryResult(
    val errorCode: Int,
    val errorMessage: String,
    val story: String,
    val cover: ImageBitmap?
)

/**
 * Một chương trong dàn ý. [content] luôn rỗng ở bước tạo truyện qua server trung gian — server chỉ
 * trả `index`/`title`/`status` (xem server_api.md mục 3.2), nội dung phải gọi [generateChapter] riêng.
 * Field này giữ lại phòng khi đọc được JSON gốc của API breakerai (có `main_content`/`summary`).
 */
data class ChapterEntry(val title: String, val content: String = "")

/**
 * Kết quả tạo truyện qua server trung gian (`POST /v1/novels` + `GET /v1/jobs/{id}`, xem server_api.md).
 * Không tải `coverUrl` từ server nữa — màn hình tự vẽ ảnh bìa theo genre, xem [GenreCoverPlaceholder].
 */
data class NovelOutline(
    val errorCode: Int,
    val errorMessage: String,
    val novelId: String,
    val chapters: List<ChapterEntry>,
    val rawJson: String,
    val requested: Int = 0          // số chương đã yêu cầu (numChapter), để so với số server trả về
)

data class ChapterResult(val errorCode: Int, val errorMessage: String, val content: String)
```

---

## 4. Truyện nhiều chương — qua proxy của bạn

```kotlin
package com.example.test.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

const val MAX_CHAPTERS = 15

/** Gợi ý genre (ô genre vẫn nhập tự do). */
val GENRE_SUGGESTIONS = listOf(
    "Fantasy", "Romance", "Sci-fi", "Adventure",
    "Horror", "Mystery", "Story for kid", "Mythology"
)

/**
 * Truyện nhiều chương — qua server trung gian của người dùng (xem server_api.md), KHÔNG gọi thẳng
 * `novelv2.breakerai.com` nữa: `POST /v1/novels` tạo job, rồi hỏi `GET /v1/jobs/{id}` mỗi
 * [POLL_INTERVAL_MS] cho tới khi `done`/`failed`. Chưa có nội dung chương nào ở bước này —
 * gọi [generateChapter] riêng cho từng chương khi người dùng bấm.
 */
suspend fun splitChapter(
    title: String,
    numChapter: String,
    topic: String,
    genre: String,
    onProgress: (String) -> Unit = {}
): NovelOutline = withContext(Dispatchers.IO) {
    val requested = (numChapter.toIntOrNull() ?: 1).coerceIn(1, MAX_CHAPTERS)

    val body = JSONObject()
        .put("title", title)
        .put("numChapter", requested)
        .put("topic", topic)
        .put("genre", genre)

    onProgress("Đang gửi yêu cầu tạo truyện…")
    val createResp = postJson(
        "$PROXY_BASE_URL/v1/novels", body,
        extraHeaders = mapOf("Idempotency-Key" to UUID.randomUUID().toString())
    )
    val jobId = createResp.optString("jobId")
    require(jobId.isNotBlank()) { "Server không trả jobId" }

    val deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS
    while (true) {
        val job = getJson("$PROXY_BASE_URL/v1/jobs/$jobId")
        when (job.optString("status")) {
            "done" -> return@withContext outlineFromNovel(job.getJSONObject("novel"), requested, job.toString(2))
            "failed" -> {
                val err = job.optJSONObject("error")
                throw ProxyException(
                    err?.optString("code")?.takeIf { it.isNotBlank() } ?: "UPSTREAM_ERROR",
                    err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Tạo truyện thất bại",
                    err?.optBoolean("retryable", false) ?: false
                )
            }
            else -> {
                if (System.currentTimeMillis() > deadline) {
                    throw ProxyException("TIMEOUT", "Quá thời gian chờ tạo truyện (job $jobId)", true)
                }
                onProgress("Đang tạo truyện… (job $jobId)")
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    @Suppress("UNREACHABLE_CODE")
    error("unreachable") // while(true) ở trên luôn return hoặc throw
}

/** Không tải `coverUrl` từ server — xem ghi chú ở [NovelOutline]. */
private fun outlineFromNovel(novel: JSONObject, requested: Int, rawJson: String): NovelOutline {
    val novelId = novel.optString("id")
    val chaptersArr = novel.optJSONArray("chapters")
    val chapters = if (chaptersArr != null) {
        (0 until chaptersArr.length())
            .map { chaptersArr.getJSONObject(it) }
            .sortedBy { it.optInt("index") }
            .map { ChapterEntry(it.optString("title").ifBlank { "Chương ${it.optInt("index")}" }) }
    } else {
        emptyList()
    }
    return NovelOutline(0, "", novelId, chapters, rawJson, requested)
}

/** Sinh nội dung chương [index] (bắt đầu từ 1) — `POST /v1/novels/{novelId}/chapters/{index}/generate`. */
suspend fun generateChapter(novelId: String, index: Int): ChapterResult = withContext(Dispatchers.IO) {
    val obj = postJson(
        "$PROXY_BASE_URL/v1/novels/$novelId/chapters/$index/generate",
        JSONObject(),
        timeoutMs = TIMEOUT_CHAPTER_MS
    )
    ChapterResult(0, "", obj.optString("content", "").trim())
}
```

Giữ nguyên 100% hợp đồng đang có với proxy (`title`, `numChapter`, `topic`, `genre` — không thêm field).

---

## 5. Dịch — gọi thẳng `gptchatapi.breakerai.com`

```kotlin
package com.example.test.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val ANSWER_KEYS = listOf("result", "answer", "content", "text", "message", "response", "reply")

/**
 * Chatbot — `POST api/ask` (form: `text`), giải mã bằng [KEY_CHAT].
 * Cấu trúc response chưa được tài liệu mô tả: đọc `data` dạng chuỗi, hoặc object chứa một trong
 * [ANSWER_KEYS]; không nhận ra thì trả nguyên JSON để đối chiếu. Ném exception nếu server báo lỗi.
 */
suspend fun ask(text: String): String = withContext(Dispatchers.IO) {
    val obj = postEncrypted("$BASE_CHAT/api/ask", linkedMapOf("text" to text), KEY_CHAT)
    val code = readErrorCode(obj)
    check(code == 0) { "ask lỗi error_code=$code: ${readErrorMsg(obj).ifBlank { "unknown" }}" }
    extractAnswer(obj)
}

private fun extractAnswer(obj: JSONObject): String {
    val data = obj.opt("data")
    if (data is String && data.isNotBlank()) return data.trim()
    if (data is JSONObject) {
        ANSWER_KEYS.firstNotNullOfOrNull { k -> data.optString(k).takeIf { it.isNotBlank() } }?.let { return it.trim() }
    }
    ANSWER_KEYS.firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() } }?.let { return it.trim() }
    return obj.toString(2)
}
```

```kotlin
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
```

```kotlin
package com.example.test.data

/**
 * Ngôn ngữ có thể dịch tới. [code] là mã theo app (vd "in" cho Indonesian, như Locale của Android),
 * [apiCode] là mã Google Translate hiểu (Indonesian là "id").
 */
data class TranslateLanguage(
    val code: String,
    val name: String,
    val flag: String,
    val apiCode: String = code
) {
    val label: String get() = "$flag $name"
}

val TRANSLATE_LANGUAGES = listOf(
    TranslateLanguage("vi", "Vietnamese", "🇻🇳"),
    TranslateLanguage("in", "Indonesian", "🇮🇩", apiCode = "id"),
    TranslateLanguage("es", "Spanish", "🇪🇸"),
    TranslateLanguage("ur", "Urdu", "🇵🇰"),
    TranslateLanguage("tl", "Tagalog", "🇵🇭"),
    TranslateLanguage("pt", "Portuguese", "🇵🇹"),
    TranslateLanguage("hi", "Hindi", "🇮🇳"),
    TranslateLanguage("ko", "Korean", "🇰🇷"),
    TranslateLanguage("ja", "Japanese", "🇯🇵"),
    TranslateLanguage("de", "German", "🇩🇪"),
    TranslateLanguage("fr", "French", "🇫🇷"),
    TranslateLanguage("it", "Italian", "🇮🇹")
)
```

---

## 6. Dịch NGAY LÚC GEN, theo ngôn ngữ app hiện tại — phần quan trọng nhất

Lớp bọc ngoài 2 hàm gen gốc (`splitChapter`, `generateChapter`): gọi xong là dịch luôn, không có bước
trung gian lộ ra cho người dùng, không có nút bấm nào. Lỗi dịch thì **lặng lẽ giữ bản gốc**, không được
làm hỏng cả luồng chính.

```kotlin
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
```

**Cách dùng ở UI:** thay lời gọi `splitChapter(...)` / `generateChapter(...)` bằng
`splitChapterLocalized(...)` / `generateChapterLocalized(...)`. Không cần hiện bất kỳ ngôn ngữ nào,
không cần nút "Xem bản gốc" — UI chỉ hiển thị thẳng `r.content` / tên chương, coi như nó đã luôn đúng
ngôn ngữ người dùng cần.

### 6.1. Nếu app có màn cho người dùng tự chọn ngôn ngữ riêng (khác ngôn ngữ máy)

`currentAppLanguageCode()` ở trên chỉ đọc `Locale.getDefault()` (ngôn ngữ máy), vì project test này
không dùng AppCompat. Nếu dự án thật cho người dùng đổi ngôn ngữ app riêng, thêm dependency
`androidx.appcompat:appcompat` và đổi hàm này thành:

```kotlin
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

fun currentAppLanguageCode(): String {
    val appLocales = AppCompatDelegate.getApplicationLocales()
    if (!appLocales.isEmpty) return appLocales[0]?.language ?: Locale.getDefault().language
    return Locale.getDefault().language
}
```

`AppCompatDelegate.getApplicationLocales()` đọc đúng ngôn ngữ app đã đặt qua
`AppCompatDelegate.setApplicationLocales(...)` (API per-app language chuẩn của AndroidX, không cần tự
quản lý SharedPreferences); không có thì rơi về ngôn ngữ máy.

---

## 7. Fallback & những gì KHÔNG được làm hỏng luồng chính

| Tình huống | Hành vi |
|---|---|
| App đang ở tiếng Anh (`currentAppLanguageCode() == "en"`) | Không gọi dịch gì cả — `targetLanguageOrNull()` trả `null` |
| Dịch lỗi mạng/timeout/chatbot trả sai định dạng | `runCatching { }.getOrDefault(text)` — giữ nguyên bản gốc, **không throw**, không làm hỏng kết quả gen |
| Dịch tên chương bị lệch số dòng (model gộp/tách dòng) | Giữ nguyên toàn bộ tên chương gốc, không dịch một phần |
| Proxy lỗi (`ProxyException`) lúc `splitChapter`/`generateChapter` | Vẫn ném lên như cũ — lớp dịch **không** nuốt lỗi của bước gen, chỉ nuốt lỗi của riêng bước dịch |

---

## 8. Checklist tích hợp

- [ ] Copy các file ở mục 1–6 vào package `data/` của project mới (đổi `package` ở dòng đầu mỗi file)
- [ ] Bỏ `KEY_STORY_V2`/`BASE_STORY` (ApiConfig.kt) và `StoryResult`/`STORY_TYPES`/`GENRES`/`ENDINGS`/
      `COVER_STYLES` (StoryModels.kt) — đồ của truyện ngắn, không dùng tới
- [ ] Đổi `PROXY_BASE_URL`/`PROXY_TOKEN` trong `ApiConfig.kt`
- [ ] UI gọi `splitChapterLocalized` / `generateChapterLocalized` — **không** gọi thẳng
      `splitChapter`/`generateChapter` từ UI
- [ ] UI **không** có nút "Dịch sang…", không hiện danh sách ngôn ngữ, không có "Xem bản gốc"
- [ ] Nếu app hỗ trợ đổi ngôn ngữ riêng (không theo máy): làm theo mục 6.1
- [ ] Test tay: đổi ngôn ngữ máy/app sang ít nhất 1 ngôn ngữ khác tiếng Anh, tạo truyện nhiều chương,
      xác nhận cả tên chương lẫn nội dung ra đúng ngôn ngữ đó — **chưa ai kiểm chứng điều này với server
      thật**, kể cả trong project test, nên đây là bước bắt buộc tự làm
- [ ] Cân nhắc mục 0 — nếu muốn giấu luôn key `K1k2hH2uZF96ik5n`, làm thêm route dịch trên proxy thay vì
      gọi thẳng `gptchatapi.breakerai.com` từ app
