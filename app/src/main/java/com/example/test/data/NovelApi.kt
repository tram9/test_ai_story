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
