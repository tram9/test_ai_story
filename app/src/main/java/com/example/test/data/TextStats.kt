package com.example.test.data

import kotlin.math.ceil
import kotlin.math.max

private const val WORDS_PER_MIN = 200.0     // tốc độ đọc ước chừng
private const val CJK_CHARS_PER_MIN = 450.0

/** Số từ, tách theo khoảng trắng (đủ dùng cho tiếng Anh/Việt và các ngôn ngữ có dấu cách). */
fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

private fun isCjk(c: Char): Boolean {
    val b = Character.UnicodeBlock.of(c)
    return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA
}

/**
 * Nhãn độ dài, vd "1.250 từ · ~7 phút đọc". Tiếng Nhật/Trung không có dấu cách giữa các từ
 * nên đếm theo ký tự: "2.400 ký tự · ~6 phút đọc". Thời gian đọc chỉ là ước chừng.
 */
fun lengthLabel(text: String): String {
    val visible = text.count { !it.isWhitespace() }
    if (visible == 0) return "0 từ"
    val cjk = text.count(::isCjk)
    return if (cjk * 3 > visible) {
        "${fmt(visible)} ký tự · ~${max(1, ceil(visible / CJK_CHARS_PER_MIN).toInt())} phút đọc"
    } else {
        val w = wordCount(text)
        "${fmt(w)} từ · ~${max(1, ceil(w / WORDS_PER_MIN).toInt())} phút đọc"
    }
}

private fun fmt(n: Int): String = "%,d".format(n).replace(',', '.')
