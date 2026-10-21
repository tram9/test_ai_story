package com.example.test.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.test.data.COVER_STYLES
import com.example.test.data.GENRE_SUGGESTIONS
import com.example.test.data.MAX_CHAPTERS
import com.example.test.data.NovelOutline
import com.example.test.data.ProxyException
import com.example.test.data.TranslateLanguage
import com.example.test.data.translateTo
import com.example.test.data.generateChapter
import com.example.test.data.lengthLabel
import com.example.test.data.splitChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn test truyện nhiều chương — gọi qua server trung gian của người dùng (server_api.md), KHÔNG
 * gọi thẳng breakerai nữa: `POST /v1/novels` tạo job -> `GET /v1/jobs/{id}` chờ dàn ý -> bấm từng
 * chương để `POST .../chapters/{index}/generate`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NovelScreen(language: TranslateLanguage?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("The Last Moonlight") }
    var numChapter by remember { mutableStateOf("1") }
    var topic by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("Fantasy") }

    // Ảnh bìa: chỉ để xem trong app, KHÔNG gửi lên server, không tốn lượt gen nào.
    var pickedCoverUri by remember { mutableStateOf<Uri?>(null) }
    var coverBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var selectedStyle by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pickCoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { pickedCoverUri = uri; selectedStyle = null }
    }
    LaunchedEffect(pickedCoverUri) {
        val uri = pickedCoverUri
        coverBitmap = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }

    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var outline by remember { mutableStateOf<NovelOutline?>(null) }
    var shownTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var busyChapter by remember { mutableStateOf<Int?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    val contents = remember { mutableStateMapOf<Int, String>() }

    suspend fun loadChapter(o: NovelOutline, i: Int) {
        busyChapter = i; status = "Đang sinh chương ${i + 1}…"
        runCatching { generateChapter(o.novelId, i + 1) }   // index server bắt đầu từ 1
            .onSuccess { r ->
                contents[i] = r.content
                status = "OK — chương ${i + 1}: ${lengthLabel(r.content)}"
            }
            .onFailure { e -> status = "Lỗi: ${errorText(e)}" }
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
        Field("Topic — truyện nói về gì?", topic, minLines = 3) { topic = it }
        Field("Genre (tự nhập hoặc bấm gợi ý)", genre) { genre = it }

        // Chip gợi ý — bấm để điền nhanh vào ô genre
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GENRE_SUGGESTIONS.forEach { g -> AssistChip(onClick = { genre = g }, label = { Text(g) }) }
        }

        Text(
            "Book Cover — chỉ hiển thị trong app, không gửi lên server, không gen ảnh",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { pickCoverLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            ) { Text("Tải ảnh lên") }
            COVER_STYLES.forEach { style ->
                AssistChip(onClick = { selectedStyle = style; pickedCoverUri = null }, label = { Text(style) })
            }
        }
        coverBitmap?.let {
            Image(
                it, contentDescription = "Ảnh bìa đã chọn",
                modifier = Modifier.padding(top = 8.dp).width(220.dp).aspectRatio(2f / 3f)
            )
        } ?: GenreCoverPlaceholder(selectedStyle ?: genre)

        Button(
            onClick = {
                loading = true; status = "Đang tạo truyện…"
                outline = null; shownTitles = emptyList(); contents.clear()
                scope.launch {
                    runCatching { splitChapter(title, numChapter, topic, genre) { msg -> status = msg } }
                        .onSuccess { r ->
                            shownTitles = r.chapters.map { it.title }
                            if (language != null && r.chapters.isNotEmpty()) {
                                status = "Đang dịch tên chương sang ${language.name}…"
                                // Dịch cả danh sách một lần; số dòng không khớp thì giữ tên gốc.
                                runCatching { translateTo(shownTitles.joinToString("\n"), language) }
                                    .onSuccess { t ->
                                        val lines = t.lines().filter { it.isNotBlank() }
                                        if (lines.size == r.chapters.size) shownTitles = lines
                                    }
                            }
                            outline = r
                            status = "OK — server trả ${r.chapters.size} chương (yêu cầu ${r.requested}), novelId=${r.novelId}"
                            if (r.chapters.isNotEmpty()) loadChapter(r, 0)   // sinh luôn nội dung đầy đủ chương 1
                        }
                        .onFailure { e -> status = "Lỗi: ${errorText(e)}" }
                    loading = false
                }
            },
            enabled = !loading && busyChapter == null &&
                title.isNotBlank() && numChapter.isNotBlank() && topic.isNotBlank() && genre.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "Đang tạo…" else "Generate") }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, fontSize = 13.sp)

        outline?.let { o ->
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

            o.chapters.forEachIndexed { i, chapter ->
                Text("${i + 1}. ${shownTitles.getOrElse(i) { chapter.title }}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                if (chapter.content.isNotBlank()) {
                    // Chỉ là tóm tắt từ dàn ý (main_content), không phải nội dung đọc đầy đủ.
                    Text("Tóm tắt: ${chapter.content}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = { scope.launch { loadChapter(o, i) } },
                    enabled = !loading && busyChapter == null
                ) {
                    Text(
                        when {
                            busyChapter == i -> "Đang sinh…"
                            contents[i] != null -> "Sinh lại chương ${i + 1}"
                            else -> "Sinh nội dung chương ${i + 1}"
                        }
                    )
                }

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

/** Lỗi từ server trung gian (server_api.md mục 3.5) thì hiện kèm mã lỗi và gợi ý thử lại; lỗi khác hiện tên + message. */
private fun errorText(e: Throwable): String = when (e) {
    is ProxyException -> "${e.code}: ${e.message}" + if (e.retryable) " — bấm Generate để thử lại." else ""
    else -> "${e.javaClass.simpleName}: ${e.message}"
}
