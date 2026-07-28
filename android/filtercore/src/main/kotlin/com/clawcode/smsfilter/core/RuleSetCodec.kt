package com.clawcode.smsfilter.core

/**
 * Serializes a [RuleSet] to a human-editable plain-text format, one rule per
 * line, so rules can be exported, imported, and merged from community lists:
 *
 * ```
 * # comment lines start with '#' at column 0; blank lines are ignored
 * allow:+1 407 555 0100
 * block:(809) 123-5678
 * pattern:(507) 413-####
 * text:free crypto
 * ```
 */
object RuleSetCodec {

    fun encode(rules: RuleSet): String = buildString {
        appendLine("# SMS spam filter rules")
        rules.allowedNumbers.forEach { appendLine("allow:$it") }
        rules.blockedNumbers.forEach { appendLine("block:$it") }
        rules.numberPatterns.forEach { appendLine("pattern:${it.raw}") }
        rules.textRules.forEach { appendLine("text:$it") }
    }

    fun decode(content: String): RuleSet {
        val allowed = mutableSetOf<String>()
        val blocked = mutableSetOf<String>()
        val patterns = mutableListOf<NumberPattern>()
        val texts = mutableListOf<String>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val value = line.substring(sep + 1).trim()
            if (value.isEmpty()) continue
            when (line.substring(0, sep).lowercase()) {
                "allow" -> allowed += PhoneNumbers.normalize(value)
                "block" -> blocked += PhoneNumbers.normalize(value)
                "pattern" -> NumberPattern.parse(value)?.let { patterns += it }
                "text" -> texts += value
            }
        }
        return RuleSet(
            textRules = texts,
            numberPatterns = patterns,
            blockedNumbers = blocked,
            allowedNumbers = allowed,
        )
    }
}
