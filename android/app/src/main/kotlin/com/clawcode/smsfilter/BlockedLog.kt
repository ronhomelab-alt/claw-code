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
