package com.clawcode.smsfilter

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Conversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val dateMs: Long,
    val unreadCount: Int,
)

data class ContactMatch(
    val name: String,
    val number: String,
)

data class ThreadMessage(
    val id: Long,
    val address: String,
    val body: String,
    val dateMs: Long,
    val isOutgoing: Boolean,
)

/**
 * Read/write access to the system SMS provider. Works both when this app is
 * the default SMS app (full inbox management) and when it isn't (read + send
 * still function with runtime permissions granted).
 */
class MessagingRepository(private val context: Context) {

    /** Bumped whenever the SMS provider changes, so screens can re-query. */
    private val _changeTick = MutableStateFlow(0L)
    val changeTick: StateFlow<Long> = _changeTick

    private val nameCache = mutableMapOf<String, String>()

    init {
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    _changeTick.value = _changeTick.value + 1
                }
            },
        )
    }

    fun conversations(): List<Conversation> {
        val byThread = LinkedHashMap<Long, Conversation>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val threadId = cursor.getLong(0)
                val address = cursor.getString(1) ?: continue
                val body = cursor.getString(2) ?: ""
                val date = cursor.getLong(3)
                val type = cursor.getInt(4)
                val read = cursor.getInt(5)
                val isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                val unread = if (isIncoming && read == 0) 1 else 0
                val existing = byThread[threadId]
                if (existing == null) {
                    byThread[threadId] = Conversation(
                        threadId = threadId,
                        address = address,
                        displayName = displayName(address),
                        snippet = body,
                        dateMs = date,
                        unreadCount = unread,
                    )
                } else if (unread > 0) {
                    byThread[threadId] =
                        existing.copy(unreadCount = existing.unreadCount + unread)
                }
            }
        }
        return byThread.values.toList()
    }

    fun messages(threadId: Long): List<ThreadMessage> {
        val result = mutableListOf<ThreadMessage>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
            ),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += ThreadMessage(
                    id = cursor.getLong(0),
                    address = cursor.getString(1) ?: "",
                    body = cursor.getString(2) ?: "",
                    dateMs = cursor.getLong(3),
                    isOutgoing = cursor.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX,
                )
            }
        }
        return result
    }

    /** Sends [body] to [address] and records it in the Sent box. */
    fun send(address: String, body: String): Long {
        context.getSystemService(SmsManager::class.java).let { smsManager ->
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(address, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(address, null, parts, null, null)
            }
        }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
        }
        context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        return Telephony.Threads.getOrCreateThreadId(context, address)
    }

    fun markThreadRead(threadId: Long) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString()),
        )
    }

    /** Contacts whose name or number matches [query], for compose-time autocomplete. */
    fun searchContacts(query: String, limit: Int = 8): List<ContactMatch> {
        if (query.isBlank()) return emptyList()
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(query.trim()),
        )
        val result = mutableListOf<ContactMatch>()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )?.use { cursor ->
                while (cursor.moveToNext() && result.size < limit) {
                    val number = cursor.getString(1) ?: continue
                    result += ContactMatch(name = cursor.getString(0) ?: number, number = number)
                }
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS not granted; no suggestions.
        }
        return result.distinctBy { it.number.filter(Char::isDigit) }
    }

    /** Contact display name for [address], falling back to the number itself. */
    fun displayName(address: String): String {
        nameCache[address]?.let { return it }
        val name = try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: SecurityException) {
            null // READ_CONTACTS not granted; show the raw number.
        } ?: address
        nameCache[address] = name
        return name
    }
}
