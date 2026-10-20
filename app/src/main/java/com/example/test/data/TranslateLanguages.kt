package com.example.test.data

/**
 * Ngôn ngữ có thể dịch tới. [code] là mã theo app (vd "in" cho Indonesian, như Locale của Android),
 * [apiCode] là mã Google Translate hiểu (Indonesian là "id").
 */
data class TranslateLanguage(
    val code: String,
    val name: String,
    val flag: String,
    val apiCode: String = code
) {
    val label: String get() = "$flag $name"
}

val TRANSLATE_LANGUAGES = listOf(
    TranslateLanguage("vi", "Vietnamese", "🇻🇳"),
    TranslateLanguage("in", "Indonesian", "🇮🇩", apiCode = "id"),
    TranslateLanguage("es", "Spanish", "🇪🇸"),
    TranslateLanguage("ur", "Urdu", "🇵🇰"),
    TranslateLanguage("tl", "Tagalog", "🇵🇭"),
    TranslateLanguage("pt", "Portuguese", "🇵🇹"),
    TranslateLanguage("hi", "Hindi", "🇮🇳"),
    TranslateLanguage("ko", "Korean", "🇰🇷"),
    TranslateLanguage("ja", "Japanese", "🇯🇵"),
    TranslateLanguage("de", "German", "🇩🇪"),
    TranslateLanguage("fr", "French", "🇫🇷"),
    TranslateLanguage("it", "Italian", "🇮🇹")
)
