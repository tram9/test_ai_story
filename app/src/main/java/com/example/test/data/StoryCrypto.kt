package com.example.test.data

import android.util.Base64
import org.json.JSONTokener
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bóc cặp ngoặc kép của chuỗi JSON ("abc" -> abc). */
fun stripJsonString(raw: String): String {
    val t = raw.trim()
    return if (t.startsWith("\"")) JSONTokener(t).nextValue() as String else t
}

/** AES/CBC/NoPadding: 16 ký tự đầu = IV, phần còn lại = Base64 ciphertext. Key tùy endpoint. */
fun decrypt(cipherText: String, key: String): String {
    val iv = cipherText.substring(0, 16).toByteArray(Charsets.UTF_8)
    val ct = Base64.decode(cipherText.substring(16), Base64.DEFAULT)
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
        IvParameterSpec(iv)
    )
    return String(cipher.doFinal(ct), Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r', '\t')
}
