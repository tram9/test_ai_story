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
