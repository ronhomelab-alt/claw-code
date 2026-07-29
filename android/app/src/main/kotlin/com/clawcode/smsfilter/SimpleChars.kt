package com.clawcode.smsfilter

import java.text.Normalizer

/**
 * Transliterates text toward the GSM 7-bit basic set so a message stays a
 * single SMS segment: strips accents, and replaces smart quotes, dashes, and
 * ellipses with plain ASCII equivalents. Characters with no simple equivalent
 * (e.g. emoji) are left untouched.
 */
object SimpleChars {
    fun simplify(text: String): String {
        val replaced = text
            .replace("‘", "'").replace("’", "'") // ‘ ’
            .replace("“", "\"").replace("”", "\"") // “ ”
            .replace("–", "-").replace("—", "-") // – —
            .replace("…", "...") // …
            .replace(" ", " ") // non-breaking space
        // Decompose accented letters, then drop the combining marks.
        val decomposed = Normalizer.normalize(replaced, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) append(ch)
            }
        }
    }
}
