package com.clawcode.smsfilter.core

/** Helpers for turning phone-number strings into comparable digit strings. */
object PhoneNumbers {

    /**
     * Normalize a sender address to bare digits for matching.
     *
     * `+1 (809) 123-5678`, `1-809-123-5678`, and `(809) 123 - 5678` all
     * normalize to `8091235678`. Alphanumeric sender IDs (e.g. "GOOGLE")
     * normalize to whatever digits they contain, usually the empty string.
     */
    fun normalize(sender: String): String {
        val digits = sender.filter { it.isDigit() }
        // NANP numbers written with the country code: drop the leading 1.
        return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
    }
}
