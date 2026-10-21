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
