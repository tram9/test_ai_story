package com.example.test.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.test.data.TRANSLATE_LANGUAGES
import com.example.test.data.TranslateLanguage
import com.example.test.data.lengthLabel
import com.example.test.data.translateTo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

/** Ô chọn ngôn ngữ kết quả. `null` = giữ bản gốc, không dịch. */
@Composable
fun LanguagePicker(
    selected: TranslateLanguage?,
    onSelect: (TranslateLanguage?) -> Unit,
    modifier: Modifier = Modifier
) {
    val none = "Bản gốc (không dịch)"
    val options = listOf(none) + TRANSLATE_LANGUAGES.map { it.label }
    Column(modifier) {
        DropdownField("Ngôn ngữ kết quả (tự dịch sau khi tạo)", options, selected?.label ?: none) { picked ->
            onSelect(TRANSLATE_LANGUAGES.firstOrNull { it.label == picked })
        }
    }
}

/**
 * Hiển thị văn bản kèm nút chọn ngôn ngữ để dịch ("Dịch sang…") và nút "Xem bản gốc".
 * Nếu có [initialLanguage], tự dịch ngay khi văn bản xuất hiện và chỉ hiện bản dịch (trong lúc chờ hiện thanh tiến độ);
 * dịch lỗi thì hiện bản gốc kèm thông báo lỗi. Bản dịch của từng ngôn ngữ được giữ lại, chọn lại không gọi dịch nữa.
 */
@Composable
fun TranslatableText(text: String, modifier: Modifier = Modifier, initialLanguage: TranslateLanguage? = null) {
    val scope = rememberCoroutineScope()
    val cache = remember(text) { mutableStateMapOf<String, String>() }
    var current by remember(text) { mutableStateOf<TranslateLanguage?>(null) }
    var menuOpen by remember(text) { mutableStateOf(false) }
    var busy by remember(text) { mutableStateOf(false) }
    var auto by remember(text, initialLanguage) { mutableStateOf(initialLanguage != null) }
    var error by remember(text) { mutableStateOf("") }

    fun choose(lang: TranslateLanguage) {
        error = ""
        if (cache.containsKey(lang.code)) {
            current = lang
            auto = false
            return
        }
        busy = true
        scope.launch {
            runCatching { translateTo(text, lang) }
                .onSuccess { cache[lang.code] = it; current = lang }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
            auto = false
        }
    }

    LaunchedEffect(text, initialLanguage) { initialLanguage?.let { choose(it) } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (auto) {
            Text("Đang dịch sang ${initialLanguage?.name}…", fontSize = 13.sp)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val shown = current?.let { cache[it.code] } ?: text
            Text(shown, fontSize = 15.sp, lineHeight = 22.sp)
            Text(lengthLabel(shown), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(enabled = !busy, onClick = { menuOpen = true }) {
                    Text(if (busy) "Đang dịch…" else current?.label ?: "Dịch sang…")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    TRANSLATE_LANGUAGES.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.label) },
                            onClick = { menuOpen = false; choose(lang) }
                        )
                    }
                }
            }
            if (current != null && !busy) {
                OutlinedButton(onClick = { current = null }) { Text("Xem bản gốc") }
            }
        }
        if (error.isNotBlank()) Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
    }
}
