# Hướng dẫn tích hợp API AI Story Generator (12.4.1) vào app Android

Hướng dẫn từng bước để đưa 3 endpoint sinh truyện vào một project Android bất kỳ.
Code bên dưới lấy từ project `test` (package `com.example.test`) — **đổi `package` cho khớp project của bạn**.
Tổng hợp chi tiết về API, response và các điểm chưa kiểm chứng xem thêm ở [API_AI_STORY.md](API_AI_STORY.md).

> Đây là backend riêng của nhà phát triển, không có xác thực; endpoint/key lấy từ APK `com.bf.aistory` 12.4.1.
> Chỉ dùng cho mục đích tìm hiểu/thử nghiệm, không nên đưa vào app phát hành.

---

## 0. Tổng quan luồng

```
generate_story_v2 (truyện ngắn):
  POST form ──▶ "<chuỗi mã hóa>" ──▶ bóc " ──▶ AES giải mã ──▶ JSON ──▶ {result, cover_image}

split_chapter + gen_chapter (nhiều chương):
  split_chapter ──▶ {userId, dàn ý các chương, url ảnh bìa}
  với mỗi chương: gen_chapter(userId, tiêu đề chương) ──▶ {content}
```

| Endpoint | Server | Key AES | Timeout |
|---|---|---|---|
| `api/generate_story_v2` | `http://storygenapi.breakerai.com` | `K1k2hH2uZF96ik9M` | 60s |
| `api/split_chapter` | `http://novelv2.breakerai.com` | `YxJstNNI1kuCFP0v` | **300s** |
| `api/gen_chapter` | `http://novelv2.breakerai.com` | `YxJstNNI1kuCFP0v` | 60s |

Không cần Retrofit / Gson / Coil — chỉ dùng `HttpURLConnection`, `javax.crypto`, `org.json`, `kotlinx.coroutines`.

---

## 1. Chuẩn bị

### 1.1. AndroidManifest.xml
Server dùng **http** (không phải https) nên phải bật cleartext:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<application
    android:usesCleartextTraffic="true"
    ... >
```

### 1.2. Dependency
Không cần thêm gì ngoài coroutines (project Compose/Kotlin thường đã có sẵn).
File `StoryModels.kt` dùng `androidx.compose.ui.graphics.ImageBitmap` cho ảnh bìa; nếu project **không dùng Compose**,
đổi `ImageBitmap` thành `android.graphics.Bitmap` và bỏ `.asImageBitmap()` trong `downloadImage` (`HttpUtil.kt`).

---

## 2. Copy các file `data/`

Tạo package `.../data` và thêm các file sau (đổi dòng `package`).

### 2.1. `ApiConfig.kt` — URL, key, timeout
```kotlin
package com.example.test.data

const val BASE_STORY = "http://storygenapi.breakerai.com"
const val BASE_NOVEL = "http://novelv2.breakerai.com"

const val KEY_STORY_V2 = "K1k2hH2uZF96ik9M"   // generate_story_v2
const val KEY_NOVEL = "YxJstNNI1kuCFP0v"      // split_chapter, gen_chapter

const val TIMEOUT_DEFAULT_MS = 60_000
const val TIMEOUT_SPLIT_MS = 300_000          // split_chapter sinh lâu, cần >= 300s
```

### 2.2. `StoryCrypto.kt` — bóc ngoặc kép + giải mã AES
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

- 16 ký tự đầu của response = **IV**, phần còn lại = **Base64** ciphertext, `AES/CBC/NoPadding`.
- Key **khác nhau theo endpoint** — dùng nhầm key sẽ ra lỗi "Malformed UTF-8", không phải lỗi code.

### 2.3. `HttpUtil.kt` — gọi HTTP, đọc mã lỗi, tải ảnh
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

fun downloadImage(urlStr: String): ImageBitmap? = try {
    (URL(urlStr).openConnection() as HttpURLConnection).inputStream.use {
        BitmapFactory.decodeStream(it)?.asImageBitmap()
    }
} catch (e: Exception) { null }
```

- `postForm` gửi `application/x-www-form-urlencoded` (**không** phải JSON) và đọc cả `errorStream` khi HTTP 500.
- `readErrorCode`: `generate_story_v2` trả `error_code`, hai endpoint mới trả `errorCode` → đọc cả hai.

### 2.4. `StoryModels.kt` — model kết quả
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

/** Kết quả `split_chapter`: dàn ý các chương + userId dùng cho `gen_chapter`. */
data class NovelOutline(
    val errorCode: Int,
    val errorMessage: String,
    val userId: String,
    val chapters: List<String>,
    val cover: ImageBitmap?,
    val rawJson: String
)

data class ChapterResult(val errorCode: Int, val errorMessage: String, val content: String)
```

### 2.5. `StoryApi.kt` — truyện ngắn
```kotlin
package com.example.test.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Truyện ngắn — `POST api/generate_story_v2` (8 field, có `story_style`). */
suspend fun generateStory(params: Map<String, String>): StoryResult = withContext(Dispatchers.IO) {
    val obj = postEncrypted("$BASE_STORY/api/generate_story_v2", params, KEY_STORY_V2)
    val code = readErrorCode(obj)
    if (code != 0) {
        return@withContext StoryResult(code, readErrorMsg(obj).ifBlank { "unknown" }, "", null)
    }
    val data = obj.getJSONObject("data")
    val story = data.optString("result", "").trim()
    val coverPath = data.optString("cover_image", "")
    val cover = if (coverPath.isNotBlank()) downloadImage(BASE_STORY + coverPath) else null
    StoryResult(0, "", story, cover)
}
```

### 2.6. `NovelApi.kt` — truyện nhiều chương
```kotlin
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

/** 4 field người dùng nhập; 8 field còn lại luôn gửi với giá trị rỗng/mặc định. */
fun buildNovelFields(title: String, numChapter: String, topic: String, genre: String): Map<String, String> =
    linkedMapOf(
        "title" to title,
        "num_chapter" to (numChapter.toIntOrNull() ?: 1).coerceIn(1, MAX_CHAPTERS).toString(),
        "topic" to withLanguageHint(topic),
        "genre" to genre,
        "writing_style" to "",
        "characters" to "",
        "world" to "",
        "plot" to "",
        "prompt" to "",
        "width" to "512",
        "height" to "768",
        "negative_prompt" to ""
    )

/**
 * Truyện nhiều chương — `POST api/split_chapter` (dàn ý + ảnh bìa, có thể mất tới 300s)
 * rồi `POST api/gen_chapter` cho từng chương.
 */
suspend fun splitChapter(params: Map<String, String>): NovelOutline = withContext(Dispatchers.IO) {
    val obj = postEncrypted("$BASE_NOVEL/api/split_chapter", params, KEY_NOVEL, TIMEOUT_SPLIT_MS)
    val code = readErrorCode(obj)
    if (code != 0) {
        return@withContext NovelOutline(code, readErrorMsg(obj).ifBlank { "unknown" }, "", emptyList(), null, obj.toString(2))
    }
    val userId = obj.optString("userId").ifBlank { obj.optString("user_id") }
    val coverPath = obj.optString("url")
    val cover = if (coverPath.isNotBlank()) downloadImage(absoluteUrl(coverPath)) else null
    NovelOutline(0, "", userId, parseChapterTitles(obj.opt("data")), cover, obj.toString(2))
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
    is JSONArray -> (0 until data.length()).mapNotNull { titleOf(data.get(it)) }
    is JSONObject -> {
        val arr = LIST_KEYS.firstNotNullOfOrNull { data.optJSONArray(it) }
            ?: data.keys().asSequence().firstNotNullOfOrNull { data.optJSONArray(it) }
        if (arr != null) parseChapterTitles(arr) else emptyList()
    }
    is String -> runCatching { parseChapterTitles(org.json.JSONTokener(data).nextValue()) }
        .getOrDefault(emptyList())
        .ifEmpty { data.lines().map { it.trim() }.filter { it.isNotEmpty() } }
    else -> emptyList()
}

private fun titleOf(item: Any?): String? = when (item) {
    is String -> item.takeIf { it.isNotBlank() }
    is JSONObject -> TITLE_KEYS.firstNotNullOfOrNull { k -> item.optString(k).takeIf { it.isNotBlank() } }
        ?: item.keys().asSequence().firstNotNullOfOrNull { k -> (item.opt(k) as? String)?.takeIf { it.isNotBlank() } }
    else -> null
}
```

- `buildNovelFields()` chỉ nhận 4 field (`title`, `num_chapter`, `topic`, `genre`); 8 field còn lại tự gửi rỗng/mặc định.
- `parseChapterTitles()` đọc "mềm" vì cấu trúc `data` của `split_chapter` chưa được tài liệu mô tả. Nếu app không hiện danh sách
  chương, xem `NovelOutline.rawJson` (JSON gốc đã giải mã) rồi chỉnh hàm này cho khớp.

---

## 3. Gọi API

Mọi hàm đều là `suspend` và tự chuyển sang `Dispatchers.IO` → gọi được thẳng từ `viewModelScope` / `rememberCoroutineScope()`.
Chúng **ném exception** khi lỗi mạng/timeout/giải mã; lỗi nghiệp vụ của server nằm trong `errorCode`/`errorMessage` của kết quả.

### 3.1. Truyện ngắn
```kotlin
val result = runCatching {
    generateStory(
        linkedMapOf(
            "story_type" to "short",                 // short | long
            "character_desc" to "A brave young knight",
            "character_name" to "Aria",
            "story_desc" to "A quest to save a hidden village",
            "cover_image_style" to "Fantasy",
            "story_genre" to "Adventure",
            "ending_mode" to "Happy Ending",
            "story_style" to "3rd person"            // field mới ở 12.4.1
        )
    )
}

result
    .onSuccess { r ->
        if (r.errorCode == 0) { /* r.story (String), r.cover (ImageBitmap?) */ }
        else { /* lỗi server: r.errorCode, r.errorMessage */ }
    }
    .onFailure { e -> /* mạng / timeout / giải mã: e.message */ }
```

Giá trị hợp lệ của `story_type`, `cover_image_style`, `story_genre`, `ending_mode` có sẵn trong `StoryModels.kt`
(`STORY_TYPES`, `COVER_STYLES`, `GENRES`, `ENDINGS`).

### 3.2. Truyện nhiều chương (2 bước)
```kotlin
// Bước 1: dàn ý + ảnh bìa — có thể mất tới 5 phút, nhớ hiện loading
val outline = splitChapter(buildNovelFields(
    title = "The Last Moonlight",
    numChapter = "5",              // 1–15, tự ép về khoảng này
    topic = "A young knight sets out to save a hidden village",
    genre = "Fantasy"              // tự nhập; có chip gợi ý: GENRE_SUGGESTIONS
))
if (outline.errorCode != 0) return    // outline.errorMessage

// Bước 2: sinh nội dung từng chương bằng userId của bước 1
val chapter = genChapter(outline.userId, outline.chapters[0])
if (chapter.errorCode == 0) { /* chapter.content */ }
```

Gọi `genChapter` cho từng chương khi người dùng bấm, hoặc lặp tuần tự để sinh hết (không nên chạy song song hàng loạt).

### 3.3. Ví dụ ViewModel (giữ trạng thái khi xoay màn hình)
```kotlin
class NovelViewModel : ViewModel() {
    var status by mutableStateOf("")
    var outline by mutableStateOf<NovelOutline?>(null)
    val contents = mutableStateMapOf<Int, String>()

    fun create(title: String, n: String, topic: String, genre: String) = viewModelScope.launch {
        status = "Đang tạo dàn ý (có thể mất tới 5 phút)…"
        runCatching { splitChapter(buildNovelFields(title, n, topic, genre)) }
            .onSuccess {
                if (it.errorCode == 0) { outline = it; status = "OK — ${it.chapters.size} chương" }
                else status = "Server error_code=${it.errorCode}: ${it.errorMessage}"
            }
            .onFailure { status = "Lỗi: ${it.javaClass.simpleName}: ${it.message}" }
    }

    fun generate(index: Int) = viewModelScope.launch {
        val o = outline ?: return@launch
        runCatching { genChapter(o.userId, o.chapters[index]) }
            .onSuccess { if (it.errorCode == 0) contents[index] = it.content else status = it.errorMessage }
            .onFailure { status = "Lỗi: ${it.message}" }
    }
}
```

---

## 4. Xử lý lỗi

| Tình huống | Biểu hiện | Xử lý |
|---|---|---|
| Lỗi nghiệp vụ server | `errorCode != 0` + `errorMessage` | hiện thông báo; `999` = lỗi tạm thời, cho thử lại |
| Timeout | `SocketTimeoutException` | `split_chapter` cần timeout ≥ 300s (đã đặt sẵn `TIMEOUT_SPLIT_MS`) |
| Sai key giải mã | `Malformed UTF-8` / `JSONException` | kiểm tra đúng key theo endpoint (`ApiConfig.kt`) |
| Chặn cleartext | `Cleartext HTTP traffic ... not permitted` | thêm `usesCleartextTraffic="true"` |
| Không có mạng | `UnknownHostException` | báo người dùng kiểm tra kết nối |
| Danh sách chương rỗng | `outline.chapters.isEmpty()` | xem `outline.rawJson`, chỉnh `parseChapterTitles()` |
| `genre` lạ bị `999` | `errorCode == 999` | dùng một genre chuẩn trong `GENRE_SUGGESTIONS` |

---

## 5. (Tùy chọn) Ngôn ngữ kết quả & nút dịch

Theo quan sát khi test thủ công (chưa kiểm chứng có hệ thống): nhập tiếng Việt thì server hiểu, nhưng kết quả nhận được đều là tiếng Anh.

### 5.1. Bảo AI viết thẳng ngôn ngữ đích (chưa xác nhận hiệu quả)
```kotlin
package com.example.test.data

import java.util.Locale

/** Tên tiếng Anh của ngôn ngữ app/máy hiện tại (Locale.getDefault()), vd "Vietnamese". */
fun outputLanguageName(): String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)

/**
 * Thêm chỉ dẫn "viết bằng <ngôn ngữ>" vào prompt tự do để AI sinh thẳng đúng ngôn ngữ,
 * không cần dịch. Tiếng Anh thì giữ nguyên. Phụ thuộc việc server có làm theo chỉ dẫn.
 */
fun withLanguageHint(text: String, language: String = outputLanguageName()): String =
    if (language.isBlank() || language == "English") text
    else "${text.trim()}\n\n(Write the entire story in $language.)"
```
Gắn vào `story_desc` / `topic`: `"story_desc" to withLanguageHint(storyDesc)`. Ngôn ngữ lấy từ `Locale.getDefault()`;
máy để tiếng Anh thì không gắn. **Chưa xác nhận server làm theo** — nếu vẫn ra tiếng Anh thì dùng 5.2.

### 5.2. Dịch kết quả bằng Google Translate online (nút "Dịch sang…")
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
```kotlin
package com.example.test.data

import kotlinx.coroutines.Dispatchers
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

/** Dịch [text] (tự nhận ngôn ngữ nguồn) sang [language]. Ném exception nếu lỗi mạng/bị giới hạn. */
suspend fun translateTo(text: String, language: TranslateLanguage): String =
    translate(text, "auto", language.apiCode)

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

/** Dịch text dài: gom các dòng thành chunk <= [MAX_CHUNK] ký tự rồi dịch lần lượt. */
private suspend fun translate(text: String, from: String, to: String): String {
    if (text.isBlank()) return text
    return withContext(Dispatchers.IO) {
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in text.split("\n")) {
            if (cur.isNotEmpty() && cur.length + line.length + 1 > MAX_CHUNK) {
                chunks += cur.toString(); cur.clear()
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(line)
        }
        if (cur.isNotEmpty()) chunks += cur.toString()
        chunks.joinToString("\n") { translateChunk(it, from, to) }
    }
}
```

Dùng: `val vi = translateTo(story, TRANSLATE_LANGUAGES.first())`.

- Endpoint **không chính thức**, không cần key, không tải model, không thêm thư viện.
- **Bắt buộc** gửi User-Agent giống trình duyệt (đã đặt trong `StoryTranslate.kt`), nếu không dễ bị `HTTP 429` / trang "Sorry…".
- Google có thể chặn hoặc ngừng bất kỳ lúc nào → luôn bắt lỗi và giữ bản gốc. Với app thật nên đổi sang
  Google Cloud Translation (cần API key) hoặc ML Kit Translate (offline, tải ~30MB model theo ngôn ngữ).

### 5.3. UI mẫu
`ui/Components.kt` có `TranslatableText(text)`: hiển thị văn bản + nút chọn ngôn ngữ + "Xem bản gốc", có cache bản dịch theo ngôn ngữ.
Chỉ cần thay `Text(story)` bằng `TranslatableText(story)`. Màn hình đầy đủ: `ui/StoryGenScreen.kt`, `ui/NovelScreen.kt`, `ui/HomeScreen.kt`.

---

## 6. Test riêng API bằng Postman

- Body **x-www-form-urlencoded** (đừng đặt `Content-Type: application/json`).
- Tăng Request timeout cho `split_chapter` (≥ 300s).
- Script giải mã (Tests / After response), đổi key theo endpoint:

```javascript
const body = JSON.parse(pm.response.text());               // bóc dấu " bao ngoài
const key = CryptoJS.enc.Utf8.parse("YxJstNNI1kuCFP0v");   // K1k2hH2uZF96ik9M cho generate_story_v2
const iv  = CryptoJS.enc.Utf8.parse(body.substring(0, 16));
const cp  = CryptoJS.lib.CipherParams.create({ ciphertext: CryptoJS.enc.Base64.parse(body.substring(16)) });
const dec = CryptoJS.AES.decrypt(cp, key, { iv, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.NoPadding });
console.log("DECRYPTED:", dec.toString(CryptoJS.enc.Utf8).replace(/\0+$/, "").trim());
```

---

## 7. Checklist tích hợp

- [ ] Manifest: `INTERNET` + `usesCleartextTraffic="true"`
- [ ] Copy 6 file bắt buộc: `ApiConfig`, `StoryCrypto`, `HttpUtil`, `StoryModels`, `StoryApi`, `NovelApi` (đổi `package`)
- [ ] Đúng key theo endpoint; `split_chapter` timeout ≥ 300s
- [ ] Gửi form-urlencoded, không phải JSON
- [ ] Hiện loading khi gọi `split_chapter` (lâu), xử lý cả `errorCode != 0` lẫn exception
- [ ] Nếu cần: `withLanguageHint` (5.1) và/hoặc nút dịch (5.2)
- [ ] **Chưa kiểm chứng** với server thật: cấu trúc `data` của `split_chapter`, `genre` tự nhập, chỉ dẫn ngôn ngữ — xem [API_AI_STORY.md](API_AI_STORY.md) mục 9
