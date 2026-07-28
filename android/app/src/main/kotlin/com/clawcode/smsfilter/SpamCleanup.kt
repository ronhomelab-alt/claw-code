package com.clawcode.smsfilter

import com.clawcode.smsfilter.core.PhoneNumbers
import com.clawcode.smsfilter.core.Verdict

/** Shared retroactive-sweep logic used by the main "Clean up" button and the paged Setup sweep. */
object SpamCleanup {

    data class Result(
        /** Number of messages actually examined in this batch. */
        val scanned: Int,
        /** Number of new senders moved to Blocked. */
        val moved: Int,
        /** True when fewer than [requested] messages remained — i.e. end of inbox. */
        val reachedEnd: Boolean,
    )

    /**
     * Applies the current rules to a batch of incoming messages
     * `[offset, offset + limit)` (newest-first) and moves matching senders'
     * conversations to Blocked. Contacts, allowed numbers, and already-blocked
     * senders are skipped. Call from a background thread.
     */
    fun run(
        repository: MessagingRepository,
        ruleStore: RuleStore,
        blockedLog: BlockedLog,
        offset: Int,
        limit: Int,
    ): Result {
        val engine = ruleStore.engine()
        val alreadySpam = ruleStore.rules.value.blockedNumbers + blockedLog.senders()
        val messages = repository.inboxMessages(offset, limit)
        val matched = LinkedHashMap<String, BlockedMessage>()
        for (message in messages) {
            val digits = PhoneNumbers.normalize(message.address)
            if (digits.isEmpty() || digits in alreadySpam || digits in matched) continue
            val isContact = repository.isContact(message.address)
            val verdict = engine.evaluate(message.address, message.body, isContact)
            if (verdict is Verdict.Block) {
                matched[digits] = BlockedMessage(
                    System.currentTimeMillis(), message.address, message.body,
                    "clean-up: ${verdict.reason}",
                )
            }
        }
        matched.values.forEach { blockedLog.append(it) }
        return Result(scanned = messages.size, moved = matched.size, reachedEnd = messages.size < limit)
    }
}
