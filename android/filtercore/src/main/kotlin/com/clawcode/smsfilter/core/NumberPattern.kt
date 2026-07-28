package com.clawcode.smsfilter.core

/**
 * A phone-number pattern where `#` matches any single digit and all
 * formatting characters (parens, spaces, dashes, dots, `+`) are ignored.
 *
 * Examples of accepted input and their meaning:
 *  - `(407)`              -> any 10-digit number in area code 407
 *  - `(507) 413-####`     -> any number 507-413-0000 through 507-413-9999
 *  - `(809) 123-5678`     -> exactly 809-123-5678
 *  - `+1 876 ### ####`    -> any 10-digit number in area code 876
 *
 * Matching semantics: the pattern is compared digit-by-digit against the
 * start of the normalized sender number. A pattern shorter than the number
 * acts as a prefix (area code / exchange match) but only against full
 * 10-digit NANP numbers, so an area-code rule like `407` never accidentally
 * blocks a 5-digit shortcode that happens to start with 407.
 */
class NumberPattern private constructor(
    /** The pattern exactly as the user typed it, for display and storage. */
    val raw: String,
    private val pattern: String,
) {
    fun matches(senderDigits: String): Boolean {
        if (senderDigits.isEmpty() || pattern.length > senderDigits.length) return false
        // Partial patterns are NANP prefixes; only apply them to 10-digit numbers.
        if (pattern.length < senderDigits.length && senderDigits.length != 10) return false
        for (i in pattern.indices) {
            val pc = pattern[i]
            if (pc != '#' && pc != senderDigits[i]) return false
        }
        return true
    }

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is NumberPattern && other.pattern == pattern
    override fun hashCode(): Int = pattern.hashCode()

    companion object {
        /** Parse user input into a pattern, or null if it contains no digits/wildcards. */
        fun parse(raw: String): NumberPattern? {
            val kept = raw.filter { it.isDigit() || it == '#' }
            if (kept.isEmpty()) return null
            // Country-code form of a NANP number/pattern: drop the leading 1.
            val normalized =
                if (kept.length == 11 && kept.startsWith("1")) kept.substring(1) else kept
            return NumberPattern(raw.trim(), normalized)
        }
    }
}
