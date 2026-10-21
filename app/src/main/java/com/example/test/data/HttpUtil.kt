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
