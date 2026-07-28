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
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().displayOriginatingAddress ?: ""
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        val app = App.from(context)

        when (val verdict = app.ruleStore.engine().evaluate(sender, body)) {
            is Verdict.Block -> {
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
                val threadId = writeToInbox(context, sender, body)
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

    private fun writeToInbox(context: Context, sender: String, body: String): Long {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 0)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        return Telephony.Threads.getOrCreateThreadId(context, sender)
    }
}
