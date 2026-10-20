package com.example.test.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.test.data.TRANSLATE_LANGUAGES

private val TABS = listOf("Truyện ngắn", "Nhiều chương")

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var languageCode by rememberSaveable { mutableStateOf<String?>(null) }
    val language = TRANSLATE_LANGUAGES.firstOrNull { it.code == languageCode }
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { i, name ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) })
            }
        }
        LanguagePicker(
            selected = language,
            onSelect = { languageCode = it?.code },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        when (tab) {
            0 -> StoryGenScreen(language)
            else -> NovelScreen(language)
        }
    }
}
