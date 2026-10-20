package com.example.test.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.test.data.GENRE_SUGGESTIONS
import com.example.test.data.MAX_CHAPTERS
import com.example.test.data.NovelOutline
import com.example.test.data.TranslateLanguage
import com.example.test.data.translateTo
import com.example.test.data.buildNovelFields
import com.example.test.data.genChapter
import com.example.test.data.lengthLabel
import com.example.test.data.splitChapter
import kotlinx.coroutines.launch

/** Màn test truyện nhiều chương: split_chapter (dàn ý + bìa) -> gen_chapter từng chương. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NovelScreen(language: TranslateLanguage?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("The Last Moonlight") }
    var numChapter by remember { mutableStateOf("1") }
    var prompt by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("Fantasy") }

    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var outline by remember { mutableStateOf<NovelOutline?>(null) }
    var shownTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var busyChapter by remember { mutableStateOf<Int?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    val contents = remember { mutableStateMapOf<Int, String>() }

    suspend fun loadChapter(o: NovelOutline, i: Int) {
        busyChapter = i; status = "Đang sinh chương ${i + 1}…"
        runCatching { genChapter(o.userId, o.chapters[i]) }
            .onSuccess { r ->
                if (r.errorCode == 0) {
                    contents[i] = r.content
                    status = "OK — chương ${i + 1}: ${lengthLabel(r.content)}"
                } else {
                    status = "Chương ${i + 1} lỗi error_code=${r.errorCode}: ${r.errorMessage}"
                }
            }
            .onFailure { e -> status = "Lỗi: ${e.javaClass.simpleName}: ${e.message}" }
        busyChapter = null
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Truyện nhiều chương", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Field("Title", title) { title = it }
        Field("Số chương (1–$MAX_CHAPTERS)", numChapter, number = true) { numChapter = it.filter(Char::isDigit) }
        Field("Prompt — truyện nói về gì?", prompt, minLines = 3) { prompt = it }
        Field("Genre (tự nhập hoặc bấm gợi ý)", genre) { genre = it }

        // Chip gợi ý — bấm để điền nhanh vào ô genre
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GENRE_SUGGESTIONS.forEach { g -> AssistChip(onClick = { genre = g }, label = { Text(g) }) }
        }

        Button(
            onClick = {
                val params = buildNovelFields(title, numChapter, prompt, genre)
                loading = true; status = "Đang tạo dàn ý (có thể mất tới 5 phút)…"
                outline = null; shownTitles = emptyList(); contents.clear()
                scope.launch {
                    runCatching { splitChapter(params) }
                        .onSuccess { r ->
                            if (r.errorCode == 0) {
                                shownTitles = r.chapters
                                if (language != null && r.chapters.isNotEmpty()) {
                                    status = "Đang dịch tên chương sang ${language.name}…"
                                    // Dịch cả danh sách một lần; số dòng không khớp thì giữ tên gốc.
                                    runCatching { translateTo(r.chapters.joinToString("\n"), language) }
                                        .onSuccess { t ->
                                            val lines = t.lines().filter { it.isNotBlank() }
                                            if (lines.size == r.chapters.size) shownTitles = lines
                                        }
                                }
                                outline = r
                                status = "OK — server trả ${r.chapters.size} chương (yêu cầu ${r.requested}), userId=${r.userId}"
                                if (r.chapters.isNotEmpty()) loadChapter(r, 0)   // sinh luôn chương 1
                            } else {
                                status = "Server error_code=${r.errorCode}: ${r.errorMessage}"
                            }
                        }
                        .onFailure { e -> status = "Lỗi: ${e.javaClass.simpleName}: ${e.message}" }
                    loading = false
                }
            },
            enabled = !loading && busyChapter == null &&
                title.isNotBlank() && numChapter.isNotBlank() && genre.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "Đang tạo…" else "Generate") }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, fontSize = 13.sp)

        outline?.let { o ->
            o.cover?.let { CoverImage(it) }

            if (o.requested > 0 && o.chapters.size != o.requested) {
                Text(
                    "Lệch số chương: bạn yêu cầu ${o.requested}, app đọc được ${o.chapters.size}. " +
                        "Mở \"Xem JSON gốc\" để biết server trả bao nhiêu.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Ẩn JSON gốc" else "Xem JSON gốc") }
            if (showRaw) Text(o.rawJson, fontSize = 12.sp)

            if (o.chapters.isEmpty()) {
                Text("Không nhận ra danh sách chương — JSON gốc:", fontSize = 13.sp)
                Text(o.rawJson, fontSize = 12.sp)
            }

            o.chapters.forEachIndexed { i, chapterTitle ->
                Text("${i + 1}. ${shownTitles.getOrElse(i) { chapterTitle }}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(
                    onClick = { scope.launch { loadChapter(o, i) } },
                    enabled = !loading && busyChapter == null
                ) { Text(if (busyChapter == i) "Đang sinh…" else "Sinh nội dung chương ${i + 1}") }

                contents[i]?.let { TranslatableText(it, initialLanguage = language) }
            }
        }
        if (busyChapter != null) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    number: Boolean = false,
    minLines: Int = 1,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default
    )
}

@Composable
private fun CoverImage(bitmap: ImageBitmap) {
    Image(bitmap, contentDescription = "cover", modifier = Modifier.padding(top = 8.dp).width(220.dp))
}
