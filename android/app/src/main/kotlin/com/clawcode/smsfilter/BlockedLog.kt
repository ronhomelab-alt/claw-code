package com.clawcode.smsfilter

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BlockedMessage(
    val timestampMs: Long,
    val sender: String,
    val body: String,
    val reason: String,
)

/**
 * Quarantine log of blocked messages, so nothing is silently lost — the user
 * can review what was filtered and copy a wrongly blocked message back out.
 * Stored as tab-separated lines; capped to the most recent [MAX_ENTRIES].
 */
class BlockedLog(context: Context) {

    private val file = File(context.filesDir, "blocked.tsv")

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<BlockedMessage>> = _entries

    @Synchronized
    fun append(entry: BlockedMessage) {
        val next = (_entries.value + entry).takeLast(MAX_ENTRIES)
        file.writeText(next.joinToString("\n") { encode(it) })
        _entries.value = next
    }

    @Synchronized
    fun clear() {
        file.delete()
        _entries.value = emptyList()
    }

    /** Normalized digits of every sender with a quarantine entry. */
    fun senders(): Set<String> =
        _entries.value.mapNotNull {
            com.clawcode.smsfilter.core.PhoneNumbers.normalize(it.sender)
                .ifEmpty { null }
        }.toSet()

    /** Drops all entries for [digits] (normalized) — the "not spam" path. */
    @Synchronized
    fun removeSender(digits: String) {
        val next = _entries.value.filterNot {
            com.clawcode.smsfilter.core.PhoneNumbers.normalize(it.sender) == digits
        }
        if (next.size != _entries.value.size) {
            file.writeText(next.joinToString("\n") { encode(it) })
            _entries.value = next
        }
    }

    /** Auto-delete support: drops entries older than [cutoffMs]. */
    @Synchronized
    fun purgeOlderThan(cutoffMs: Long) {
        val next = _entries.value.filter { it.timestampMs >= cutoffMs }
        if (next.size != _entries.value.size) {
            file.writeText(next.joinToString("\n") { encode(it) })
            _entries.value = next
        }
    }

    private fun load(): List<BlockedMessage> =
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { decode(it) }

    private fun encode(e: BlockedMessage): String = listOf(
        e.timestampMs.toString(),
        e.sender.replace('\t', ' '),
        e.body.replace('\t', ' ').replace('\n', ' '),
        e.reason.replace('\t', ' '),
    ).joinToString("\t")

    private fun decode(line: String): BlockedMessage? {
        val parts = line.split('\t')
        if (parts.size != 4) return null
        val ts = parts[0].toLongOrNull() ?: return null
        return BlockedMessage(ts, parts[1], parts[2], parts[3])
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
