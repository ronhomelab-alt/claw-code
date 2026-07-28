package com.clawcode.smsfilter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
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
                writeToInbox(context, sender, body)
                notify(context, sender, body)
            }
        }
    }

    private fun writeToInbox(context: Context, sender: String, body: String) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 0)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    private fun notify(context: Context, sender: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(sender)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(sender.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "incoming_sms"
    }
}
