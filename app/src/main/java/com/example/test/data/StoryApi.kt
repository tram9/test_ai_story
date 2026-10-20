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
