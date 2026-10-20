package com.example.test.data

import java.util.Locale

/** Tên tiếng Anh của ngôn ngữ app/máy hiện tại (Locale.getDefault()), vd "Vietnamese". */
fun outputLanguageName(): String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)

/**
 * Thêm chỉ dẫn "viết bằng <ngôn ngữ>" vào prompt tự do để AI sinh thẳng đúng ngôn ngữ,
 * không cần dịch. Tiếng Anh thì giữ nguyên. Phụ thuộc việc server có làm theo chỉ dẫn.
 */
fun withLanguageHint(text: String, language: String = outputLanguageName()): String =
    if (language.isBlank() || language == "English") text
    else "${text.trim()}\n\n(Write the entire story in $language.)"
