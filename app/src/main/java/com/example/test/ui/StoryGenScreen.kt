package com.example.test.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.test.data.COVER_STYLES
import com.example.test.data.ENDINGS
import com.example.test.data.GENRES
import com.example.test.data.STORY_TYPES
import com.example.test.data.TranslateLanguage
import com.example.test.data.generateStory
import com.example.test.data.lengthLabel
import kotlinx.coroutines.launch

/** Màn test cho API sinh truyện của app "AI Story Generator". */
@Composable
fun StoryGenScreen(language: TranslateLanguage?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    var storyType by remember { mutableStateOf(STORY_TYPES.first()) }
    var characterDesc by remember { mutableStateOf("A brave young knight") }
    var characterName by remember { mutableStateOf("Aria") }
    var storyDesc by remember { mutableStateOf("A quest to save a hidden village") }
    var coverStyle by remember { mutableStateOf("Fantasy") }
    var genre by remember { mutableStateOf(GENRES.first()) }
    var ending by remember { mutableStateOf(ENDINGS.first()) }
    var storyStyle by remember { mutableStateOf("3rd person") }

    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var story by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("AI Story Generator — test", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        DropdownField("story_type", STORY_TYPES, storyType) { storyType = it }
        OutlinedTextField(characterDesc, { characterDesc = it },
            label = { Text("character_desc") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(characterName, { characterName = it },
            label = { Text("character_name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(storyDesc, { storyDesc = it },
            label = { Text("story_desc") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        DropdownField("cover_image_style", COVER_STYLES, coverStyle) { coverStyle = it }
        DropdownField("story_genre", GENRES, genre) { genre = it }
        DropdownField("ending_mode", ENDINGS, ending) { ending = it }
        OutlinedTextField(storyStyle, { storyStyle = it },
            label = { Text("story_style (First person, 3rd person, ...)") }, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = {
                val params = linkedMapOf(
                    "story_type" to storyType,
                    "character_desc" to characterDesc,
                    "character_name" to characterName,
                    "story_desc" to storyDesc,
                    "cover_image_style" to coverStyle,
                    "story_genre" to genre,
                    "ending_mode" to ending,
                    "story_style" to storyStyle
                )
                loading = true; status = "Đang gọi API…"; story = ""; cover = null
                scope.launch {
                    val result = runCatching { generateStory(params) }
                    result.onSuccess { r ->
                        if (r.errorCode == 0) {
                            status = "OK — ${lengthLabel(r.story)}"
                            story = r.story
                            cover = r.cover
                        } else {
                            status = "Server error_code=${r.errorCode}: ${r.errorMessage}"
                        }
                    }.onFailure { e ->
                        status = "Lỗi: ${e.javaClass.simpleName}: ${e.message}"
                    }
                    loading = false
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "Đang tạo…" else "Generate story") }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, fontSize = 13.sp)

        cover?.let {
            Image(it, contentDescription = "cover",
                modifier = Modifier.padding(top = 8.dp).width(220.dp))
        }

        if (story.isNotBlank()) {
            TranslatableText(story, Modifier.padding(top = 8.dp), initialLanguage = language)
        }
    }
}
