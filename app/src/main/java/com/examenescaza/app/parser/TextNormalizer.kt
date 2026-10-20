package com.examenescaza.app.parser

import java.security.MessageDigest
import java.text.Normalizer

object TextNormalizer {
    private val ligatures = mapOf(
        "ﬁ" to "fi", "ﬂ" to "fl", "ﬀ" to "ff", "ﬃ" to "ffi", "ﬄ" to "ffl", "ﬅ" to "st", "ﬆ" to "st",
    )

    fun normalizeGlyph(s: String): String {
        var out = s
        for ((k, v) in ligatures) if (out.contains(k)) out = out.replace(k, v)
        return out
    }

    /** Lowercase, accent-free, alphanumeric-only form used for comparisons and stable keys. */
    fun comparable(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    /** Accent-free lowercase words, used for search and related-theory matching. */
    fun words(s: String): List<String> {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            sb.append(if (c.isLetterOrDigit()) c else ' ')
        }
        return sb.split(' ').filter { it.isNotEmpty() }
    }

    fun foldForSearch(s: String): String = words(s).joinToString(" ")

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(s: String): String = sha256(s.toByteArray())

    fun romanToInt(roman: String): Int? {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50)
        var total = 0
        var prev = 0
        for (c in roman.uppercase().reversed()) {
            val v = values[c] ?: return null
            if (v < prev) total -= v else { total += v; prev = v }
        }
        return total.takeIf { it > 0 }
    }
}
