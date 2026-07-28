package com.clawcode.smsfilter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhoneNumbersTest {
    @Test
    fun `strips formatting`() {
        assertEquals("8091235678", PhoneNumbers.normalize("( 809) 123 - 5678"))
        assertEquals("8091235678", PhoneNumbers.normalize("+1 809-123-5678"))
        assertEquals("8091235678", PhoneNumbers.normalize("1 (809) 123.5678"))
    }

    @Test
    fun `keeps shortcodes and non-NANP numbers as-is`() {
        assertEquals("40733", PhoneNumbers.normalize("40733"))
        assertEquals("447911123456", PhoneNumbers.normalize("+44 7911 123456"))
    }

    @Test
    fun `alphanumeric sender ids normalize to empty`() {
        assertEquals("", PhoneNumbers.normalize("GOOGLE"))
    }
}

class NumberPatternTest {
    private fun pattern(raw: String) = assertNotNull(NumberPattern.parse(raw), "parse($raw)")

    @Test
    fun `area code alone blocks the whole area code`() {
        val p = pattern("(407)")
        assertTrue(p.matches(PhoneNumbers.normalize("(407) 555-0134")))
        assertTrue(p.matches(PhoneNumbers.normalize("+1 407 000 0000")))
        assertFalse(p.matches(PhoneNumbers.normalize("(507) 555-0134")))
    }

    @Test
    fun `area code plus exchange with wildcards`() {
        val p = pattern("(507) 413 - ####")
        assertTrue(p.matches(PhoneNumbers.normalize("507-413-0000")))
        assertTrue(p.matches(PhoneNumbers.normalize("(507) 413-9999")))
        assertFalse(p.matches(PhoneNumbers.normalize("(507) 414-9999")))
    }

    @Test
    fun `full number is an exact match`() {
        val p = pattern("( 809) 123 - 5678")
        assertTrue(p.matches(PhoneNumbers.normalize("+1 (809) 123-5678")))
        assertFalse(p.matches(PhoneNumbers.normalize("(809) 123-5679")))
    }

    @Test
    fun `area code prefix does not block shortcodes`() {
        val p = pattern("(407)")
        assertFalse(p.matches(PhoneNumbers.normalize("40733")))
    }

    @Test
    fun `pattern with country code is normalized like numbers`() {
        val p = pattern("+1 876 ### ####")
        assertTrue(p.matches(PhoneNumbers.normalize("(876) 555-0100")))
    }

    @Test
    fun `garbage input does not parse`() {
        assertEquals(null, NumberPattern.parse("hello"))
        assertEquals(null, NumberPattern.parse(""))
    }
}

class FilterEngineTest {
    private val engine = FilterEngine(
        RuleSet(
            textRules = listOf("free crypto", "claim your prize"),
            numberPatterns = listOfNotNull(
                NumberPattern.parse("(407)"),
                NumberPattern.parse("(507) 413-####"),
                NumberPattern.parse("(809) 123-5678"),
            ),
            blockedNumbers = setOf(PhoneNumbers.normalize("+1 202 555 0175")),
            allowedNumbers = setOf(PhoneNumbers.normalize("(407) 555-0199")),
        )
    )

    @Test
    fun `blocks by area code`() {
        assertIs<Verdict.Block>(engine.evaluate("+1 (407) 555-0134", "hey"))
    }

    @Test
    fun `blocks by exchange wildcard`() {
        assertIs<Verdict.Block>(engine.evaluate("(507) 413-1234", "hey"))
    }

    @Test
    fun `blocks exact pattern number`() {
        assertIs<Verdict.Block>(engine.evaluate("( 809) 123 - 5678", "hey"))
    }

    @Test
    fun `blocks exact blocklisted number`() {
        assertIs<Verdict.Block>(engine.evaluate("(202) 555-0175", "hey"))
    }

    @Test
    fun `blocks by body text case-insensitively`() {
        assertIs<Verdict.Block>(engine.evaluate("(555) 555-0100", "FREE CRYPTO now!!"))
    }

    @Test
    fun `allowlist beats every block rule`() {
        assertIs<Verdict.Allow>(engine.evaluate("(407) 555-0199", "free crypto"))
    }

    @Test
    fun `ordinary messages pass`() {
        assertIs<Verdict.Allow>(engine.evaluate("(555) 555-0100", "lunch at noon?"))
    }

    @Test
    fun `contacts are exempt from text rules but not number rules`() {
        // Body would normally be blocked, but a contact is never text-blocked.
        assertIs<Verdict.Allow>(
            engine.evaluate("(555) 555-0100", "free crypto for you", senderIsContact = true)
        )
        // A contact on an explicitly blocked pattern is still blocked.
        assertIs<Verdict.Block>(
            engine.evaluate("(407) 555-0134", "hi mom", senderIsContact = true)
        )
        // Non-contacts still hit text rules.
        assertIs<Verdict.Block>(
            engine.evaluate("(555) 555-0100", "free crypto for you", senderIsContact = false)
        )
    }

    @Test
    fun `verification codes are never body-blocked`() {
        assertIs<Verdict.Allow>(
            engine.evaluate("(555) 555-0100", "Your FREE CRYPTO exchange verification code is 483920")
        )
        // ...but a number rule still blocks even OTP-looking spam
        assertIs<Verdict.Block>(
            engine.evaluate("(407) 555-0134", "Your verification code is 483920")
        )
    }
}

class TextNormalizerTest {
    private val engine = FilterEngine(RuleSet(textRules = listOf("Top Tier Solar")))

    @Test
    fun `one rule catches case spacing punctuation and leetspeak variants`() {
        val variants = listOf(
            "Top Tier Solar has a deal for you",
            "TOP-TIER SOLAR!! final notice",
            "T0p T1er S0lar savings expire today",
            "Reply YES to TopTier\$olar",
            "top.tier.solar wants to talk",
            "T0PT13R50LAR",
        )
        for (variant in variants) {
            assertIs<Verdict.Block>(
                engine.evaluate("(555) 555-0100", variant),
                "should block: $variant",
            )
        }
    }

    @Test
    fun `unrelated solar messages are not blocked`() {
        assertIs<Verdict.Allow>(
            engine.evaluate("(555) 555-0100", "my solar panels finally got installed!")
        )
        assertIs<Verdict.Allow>(
            engine.evaluate("(555) 555-0100", "top tier performance from the team today")
        )
    }

    @Test
    fun `canonicalization folds confusable characters`() {
        assertEquals("toptiersoiar", TextNormalizer.canonical("T0p-T1er $0lar"))
        assertEquals(TextNormalizer.canonical("solar"), TextNormalizer.canonical("S0LAR"))
    }
}

class RuleSetCodecTest {
    @Test
    fun `round trips through text format`() {
        val original = RuleSet(
            textRules = listOf("free crypto"),
            numberPatterns = listOfNotNull(NumberPattern.parse("(507) 413-####")),
            blockedNumbers = setOf("8091235678"),
            allowedNumbers = setOf("4075550199"),
        )
        val decoded = RuleSetCodec.decode(RuleSetCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `ignores comments blanks and junk lines`() {
        val decoded = RuleSetCodec.decode(
            """
            # my rules

            pattern:(407)
            nonsense line
            text:win a prize
            """.trimIndent()
        )
        assertEquals(1, decoded.numberPatterns.size)
        assertEquals(listOf("win a prize"), decoded.textRules)
    }

    @Test
    fun `seed rules block one-ring scam area codes`() {
        val engine = FilterEngine(SeedRules.defaultRuleSet())
        assertIs<Verdict.Block>(engine.evaluate("(809) 555-0100", "call me back"))
        assertIs<Verdict.Block>(engine.evaluate("+1 876 555 0100", "you won"))
        assertIs<Verdict.Allow>(engine.evaluate("(212) 555-0100", "hello"))
    }
}
