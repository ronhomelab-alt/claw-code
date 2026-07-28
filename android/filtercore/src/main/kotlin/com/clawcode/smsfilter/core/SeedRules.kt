package com.clawcode.smsfilter.core

/**
 * Starter rules shipped with the app. The area codes below are the Caribbean
 * NANP codes repeatedly cited by the FCC/FTC in "one-ring" (Wangiri) scam
 * advisories — they look like US numbers but are international toll lines
 * favored by call-farming operations. Users can delete any of these.
 *
 * Deliberately NOT included: specific "known spammer" phone numbers. Spam
 * numbers are spoofed and recycled within days, so hardcoding them creates
 * false positives against future legitimate owners. Instead the app supports
 * importing blocklists (see [RuleSetCodec]) so users can pull a current
 * community list.
 */
object SeedRules {

    /** One-ring / Wangiri scam area codes from FCC & FTC consumer advisories. */
    val SCAM_AREA_CODES: List<String> = listOf(
        "232", // Sierra Leone (frequently spoofed as a US area code)
        "268", // Antigua and Barbuda
        "284", // British Virgin Islands
        "473", // Grenada
        "649", // Turks and Caicos
        "664", // Montserrat
        "767", // Dominica
        "809", // Dominican Republic
        "829", // Dominican Republic
        "849", // Dominican Republic
        "876", // Jamaica
    )

    fun defaultRuleSet(): RuleSet = RuleSet(
        numberPatterns = SCAM_AREA_CODES.mapNotNull { NumberPattern.parse("($it)") },
    )
}
