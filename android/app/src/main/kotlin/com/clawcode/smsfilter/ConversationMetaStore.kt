package com.clawcode.smsfilter

import android.content.Context
import com.clawcode.smsfilter.core.PhoneNumbers
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Gmail-style per-conversation flags: star, important, and custom labels. */
data class ThreadMeta(
    val starred: Boolean = false,
    val important: Boolean = false,
    val labels: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = !starred && !important && labels.isEmpty()
}

/**
 * File-backed store of conversation metadata, keyed by the sender's
 * normalized number so a star/label survives thread deletion and re-creation.
 * Also tracks the full set of label names the user has created (so an empty
 * label/folder still exists).
 */
class ConversationMetaStore(context: Context) {

    private val file = File(context.filesDir, "conversation_meta.json")

    private val _meta = MutableStateFlow<Map<String, ThreadMeta>>(emptyMap())
    val meta: StateFlow<Map<String, ThreadMeta>> = _meta

    private val _labels = MutableStateFlow<List<String>>(emptyList())
    val labels: StateFlow<List<String>> = _labels

    init {
        load()
    }

    /** Metadata key for an address: normalized digits, or the raw string for shortcodes. */
    fun keyFor(address: String): String =
        PhoneNumbers.normalize(address).ifEmpty { address.trim() }

    fun metaFor(address: String): ThreadMeta = _meta.value[keyFor(address)] ?: ThreadMeta()

    fun toggleStar(address: String) = mutate(address) { it.copy(starred = !it.starred) }

    fun setStarred(address: String, value: Boolean) = mutate(address) { it.copy(starred = value) }

    fun toggleImportant(address: String) = mutate(address) { it.copy(important = !it.important) }

    fun setImportant(address: String, value: Boolean) =
        mutate(address) { it.copy(important = value) }

    fun addLabel(address: String, label: String) {
        createLabel(label)
        mutate(address) { it.copy(labels = it.labels + label) }
    }

    fun removeLabel(address: String, label: String) =
        mutate(address) { it.copy(labels = it.labels - label) }

    fun createLabel(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed !in _labels.value) {
            _labels.value = (_labels.value + trimmed).sorted()
            save()
        }
    }

    /** Deletes a label everywhere it is applied. */
    fun deleteLabel(name: String) {
        _labels.value = _labels.value - name
        _meta.value = _meta.value.mapValues { (_, m) -> m.copy(labels = m.labels - name) }
            .filterValues { !it.isEmpty }
        save()
    }

    @Synchronized
    private fun mutate(address: String, transform: (ThreadMeta) -> ThreadMeta) {
        val key = keyFor(address)
        if (key.isEmpty()) return
        val next = transform(_meta.value[key] ?: ThreadMeta())
        _meta.value = if (next.isEmpty) {
            _meta.value - key
        } else {
            _meta.value + (key to next)
        }
        save()
    }

    @Synchronized
    private fun save() {
        try {
            val root = JSONObject()
            val metaObj = JSONObject()
            for ((key, m) in _meta.value) {
                metaObj.put(
                    key,
                    JSONObject()
                        .put("s", m.starred)
                        .put("i", m.important)
                        .put("l", JSONArray(m.labels.toList())),
                )
            }
            root.put("meta", metaObj)
            root.put("labels", JSONArray(_labels.value))
            file.writeText(root.toString())
        } catch (e: Exception) {
            android.util.Log.w("ConversationMetaStore", "save failed", e)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val metaObj = root.optJSONObject("meta") ?: JSONObject()
            val loaded = mutableMapOf<String, ThreadMeta>()
            for (key in metaObj.keys()) {
                val o = metaObj.getJSONObject(key)
                val labelArray = o.optJSONArray("l") ?: JSONArray()
                val labels = (0 until labelArray.length()).map { labelArray.getString(it) }.toSet()
                loaded[key] = ThreadMeta(
                    starred = o.optBoolean("s", false),
                    important = o.optBoolean("i", false),
                    labels = labels,
                )
            }
            _meta.value = loaded
            val labelArray = root.optJSONArray("labels") ?: JSONArray()
            _labels.value = (0 until labelArray.length()).map { labelArray.getString(it) }.sorted()
        } catch (e: Exception) {
            android.util.Log.w("ConversationMetaStore", "load failed", e)
        }
    }
}
