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
