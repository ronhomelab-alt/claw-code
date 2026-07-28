package com.clawcode.smsfilter.core

/** Why a message was blocked (or that it wasn't). */
sealed class Verdict {
    object Allow : Verdict()
    data class Block(val reason: String) : Verdict()
}

/**
 * The full rule configuration, in evaluation-priority order:
 *
 *  1. [allowedNumbers] always win (contacts, banks, OTP senders you trust).
 *  2. [blockedNumbers] — exact known-spam numbers.
 *  3. [numberPatterns] — area-code / prefix / wildcard rules.
 *  4. [textRules] — case-insensitive substring match on the message body.
 */
data class RuleSet(
    val textRules: List<String> = emptyList(),
    val numberPatterns: List<NumberPattern> = emptyList(),
    val blockedNumbers: Set<String> = emptySet(),
    val allowedNumbers: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = RuleSet()
    }
}

class FilterEngine(private val rules: RuleSet) {

    // Pair each text rule with its canonical form once, up front.
    private val canonicalTextRules: List<Pair<String, String>> =
        rules.textRules
            .map { it to TextNormalizer.canonical(it) }
            .filter { it.second.isNotEmpty() }

    /**
     * @param senderIsContact when true, the message is from someone in the
     *   user's contacts, so the broad rules — area-code/[numberPatterns] and
     *   body [textRules][RuleSet.textRules] — are skipped. A contact is never
     *   caught by "block all 407" or by a keyword. The one exception is an
     *   explicit exact-number block (the Block button), which still applies
     *   so deliberately blocking a specific person works.
     */
    fun evaluate(sender: String, body: String, senderIsContact: Boolean = false): Verdict {
        val digits = PhoneNumbers.normalize(sender)

        if (digits.isNotEmpty() && digits in rules.allowedNumbers) return Verdict.Allow

        // Explicit exact-number block applies to everyone, contacts included.
        if (digits.isNotEmpty() && digits in rules.blockedNumbers) {
            return Verdict.Block("number $sender is on the blocklist")
        }

        // Broad area-code / pattern rules never apply to a contact.
        if (!senderIsContact) {
            rules.numberPatterns.firstOrNull { it.matches(digits) }?.let {
                return Verdict.Block("number $sender matches pattern \"${it.raw}\"")
            }
        }

        // Never body-block a contact, and never a message that looks like a
        // verification code: losing either hurts far more than one spam text.
        if (!senderIsContact && !looksLikeVerificationCode(body)) {
            // Match in canonical space so "T0p-T1er $olar" still hits a
            // "Top Tier Solar" rule despite case/spacing/leetspeak tricks.
            val canonicalBody = TextNormalizer.canonical(body)
            canonicalTextRules.firstOrNull { (_, canonical) ->
                canonicalBody.contains(canonical)
            }?.let { (raw, _) ->
                return Verdict.Block("body matches \"$raw\"")
            }
        }

        return Verdict.Allow
    }

    companion object {
        private val OTP_HINT = Regex(
            """\b(verification|security|auth\w*|one[- ]?time|2fa|otp)\b.{0,40}\b\d{4,8}\b|\b\d{4,8}\b.{0,40}\b(verification|security|auth\w*|one[- ]?time|2fa|otp)\s+code\b""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun looksLikeVerificationCode(body: String): Boolean = OTP_HINT.containsMatchIn(body)
    }
}
