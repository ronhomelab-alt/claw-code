package com.clawcode.smsfilter.core

import java.text.Normalizer

/**
 * Canonicalizes text so one rule catches a spammer's whole family of
 * obfuscations: case, spacing, punctuation, common leetspeak digit
 * substitutions, and look-alike (homoglyph) letters from other scripts are
 * all folded away before matching.
 *
 * "Top Tier Solar", "TOP-TIER SOLAR!!", "T0p T1er S0lar", "TopTier$olar",
 * and a Cyrillic-spoofed "Тор Тiеr Ѕоlаr" all canonicalize to the same
 * string.
 *
 * `l`, `1`, `|`, and `!` are folded together with `i` (visually confusable),
 * so the mapping is applied to both the rule and the message — matching
 * happens entirely in canonical space.
 */
object TextNormalizer {

    /**
     * Look-alike letters folded to their Latin visual equivalent. Keys are
     * the lowercase forms (input is lowercased first); Cyrillic capitals like
     * Т lowercase to т, so mapping the lowercase form covers both cases.
     */
    private val HOMOGLYPHS = mapOf(
        // Cyrillic
        'а' to 'a', 'в' to 'b', 'е' to 'e', 'к' to 'k', 'м' to 'm',
        'н' to 'h', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'т' to 't',
        'х' to 'x', 'у' to 'y', 'ѕ' to 's', 'і' to 'i', 'ј' to 'j',
        'ԛ' to 'q', 'ԝ' to 'w', 'ь' to 'b', 'г' to 'r',
        // Greek
        'α' to 'a', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v',
        'ο' to 'o', 'ρ' to 'p', 'τ' to 't', 'υ' to 'u', 'χ' to 'x',
    )

    fun canonical(text: String): String {
        // NFKC folds fullwidth forms (ｔ→t), ligatures, and other compatibility
        // characters before the per-character mapping below.
        val compat = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return buildString(compat.length) {
            for (raw in compat.lowercase()) {
                val ch = HOMOGLYPHS[raw] ?: raw
                val mapped = when (ch) {
                    '0' -> 'o'
                    '1', 'l', '|', '!' -> 'i'
                    '3' -> 'e'
                    '4' -> 'a'
                    '5', '$' -> 's'
                    '7' -> 't'
                    '8' -> 'b'
                    '@' -> 'a'
                    else -> ch
                }
                if (mapped.isLetterOrDigit()) append(mapped)
            }
        }
    }
}
