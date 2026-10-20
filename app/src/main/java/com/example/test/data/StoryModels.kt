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
    val rawJson: String,
    val requested: Int = 0          // số chương đã yêu cầu (num_chapter), để so với số server trả về
)

data class ChapterResult(val errorCode: Int, val errorMessage: String, val content: String)
