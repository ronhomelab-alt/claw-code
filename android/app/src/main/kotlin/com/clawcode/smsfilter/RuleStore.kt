package com.clawcode.smsfilter

import android.content.Context
import com.clawcode.smsfilter.core.FilterEngine
import com.clawcode.smsfilter.core.RuleSet
import com.clawcode.smsfilter.core.RuleSetCodec
import com.clawcode.smsfilter.core.SeedRules
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * File-backed rule storage. Rules live in a single human-readable text file
 * (see [RuleSetCodec]) under the app's private files dir, which makes
 * export/import a plain file copy.
 */
class RuleStore(context: Context) {

    private val file = File(context.filesDir, "rules.txt")

    private val _rules = MutableStateFlow(load())
    val rules: StateFlow<RuleSet> = _rules

    fun engine(): FilterEngine = FilterEngine(_rules.value)

    @Synchronized
    fun update(transform: (RuleSet) -> RuleSet) {
        val next = transform(_rules.value)
        file.writeText(RuleSetCodec.encode(next))
        _rules.value = next
    }

    fun exportText(): String = RuleSetCodec.encode(_rules.value)

    /** Merge rules from an imported file/community list into the current set. */
    fun importText(content: String) = update { current ->
        val imported = RuleSetCodec.decode(content)
        RuleSet(
            textRules = (current.textRules + imported.textRules).distinct(),
            numberPatterns = (current.numberPatterns + imported.numberPatterns).distinct(),
            blockedNumbers = current.blockedNumbers + imported.blockedNumbers,
            allowedNumbers = current.allowedNumbers + imported.allowedNumbers,
        )
    }

    private fun load(): RuleSet =
        if (file.exists()) {
            RuleSetCodec.decode(file.readText())
        } else {
            SeedRules.defaultRuleSet().also { file.writeText(RuleSetCodec.encode(it)) }
        }
}
