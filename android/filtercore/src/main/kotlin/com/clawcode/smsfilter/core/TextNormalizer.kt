package com.clawcode.smsfilter.core

/**
 * Canonicalizes text so one rule catches a spammer's whole family of
 * obfuscations: case, spacing, punctuation, and common leetspeak digit
 * substitutions are all folded away before matching.
 *
 * "Top Tier Solar", "TOP-TIER SOLAR!!", "T0p T1er S0lar", and
 * "TopTier$olar" all canonicalize to the same string.
 *
 * `l`, `1`, `|`, and `!` are folded together with `i` (visually confusable),
 * so the mapping is applied to both the rule and the message — matching
 * happens entirely in canonical space.
 */
object TextNormalizer {

    fun canonical(text: String): String = buildString(text.length) {
        for (ch in text.lowercase()) {
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
