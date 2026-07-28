package com.clawcode.smsfilter

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.clawcode.smsfilter.core.Verdict

/**
 * Fires only while this app is the default SMS app (SMS_DELIVER action).
 *
 * This is the one place Android lets a third-party app filter a text
 * *before* any messaging UI sees it: blocked messages go to the quarantine
 * log and never enter the SMS inbox; allowed messages are written to the
 * system SMS provider and notified normally.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // A crash here would lose an incoming message; log and continue instead.
        try {
            handle(context, intent)
        } catch (e: Exception) {
            android.util.Log.e("SmsDeliverReceiver", "failed to process incoming SMS", e)
        }
    }

    private fun handle(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().displayOriginatingAddress ?: ""
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        val app = App.from(context)

        val isContact = app.messagingRepository.isContact(sender)
        when (val verdict = app.ruleStore.engine().evaluate(sender, body, isContact)) {
            is Verdict.Block -> {
                // Quarantine, Google-Messages-style: the message is stored
                // (marked read, no notification) so the Blocked screen shows
                // a real, replyable conversation — but the inbox never
                // surfaces it and the user is never interrupted.
                writeToInbox(context, sender, body, read = true)
                app.blockedLog.append(
                    BlockedMessage(
                        timestampMs = System.currentTimeMillis(),
                        sender = sender,
                        body = body,
                        reason = verdict.reason,
                    )
                )
            }
            is Verdict.Allow -> {
                val threadId = writeToInbox(context, sender, body, read = false)
                Notifications.notifyIncoming(
                    context = context,
                    threadId = threadId,
                    address = sender,
                    displayName = app.messagingRepository.displayName(sender),
                    body = body,
                )
            }
        }
    }

    private fun writeToInbox(
        context: Context,
        sender: String,
        body: String,
        read: Boolean,
    ): Long {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, if (read) 1 else 0)
            if (read) put(Telephony.Sms.SEEN, 1)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        return Telephony.Threads.getOrCreateThreadId(context, sender)
    }
}
