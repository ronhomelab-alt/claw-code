package com.clawcode.smsfilter

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Handles notification actions: inline reply, mark-as-read, and remind-in-1hr. */
class MessageActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = App.from(context)
        val threadId = intent.getLongExtra(Notifications.EXTRA_THREAD_ID, -1L)
        if (threadId == -1L) return

        when (intent.action) {
            ACTION_REPLY -> {
                val address = intent.getStringExtra(Notifications.EXTRA_ADDRESS) ?: return
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifications.KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                if (!text.isNullOrEmpty()) {
                    app.messagingRepository.send(address, text)
                }
                app.messagingRepository.markThreadRead(threadId)
                Notifications.cancel(context, threadId)
            }
            ACTION_MARK_READ -> {
                app.messagingRepository.markThreadRead(threadId)
                Notifications.cancel(context, threadId)
            }
            ACTION_REMIND -> {
                // Dismiss now; re-post the same notification in one hour.
                Notifications.cancel(context, threadId)
                val fireIntent = Intent(context, MessageActionReceiver::class.java)
                    .setAction(ACTION_REMIND_FIRE)
                    .putExtra(Notifications.EXTRA_THREAD_ID, threadId)
                    .putExtra(Notifications.EXTRA_ADDRESS, intent.getStringExtra(Notifications.EXTRA_ADDRESS))
                    .putExtra(Notifications.EXTRA_NAME, intent.getStringExtra(Notifications.EXTRA_NAME))
                    .putExtra(Notifications.EXTRA_BODY, intent.getStringExtra(Notifications.EXTRA_BODY))
                val pending = PendingIntent.getBroadcast(
                    context,
                    threadId.toInt() + 3_000_000,
                    fireIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                // Inexact alarm — no special permission needed and ~1h is fine.
                context.getSystemService(AlarmManager::class.java)?.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 60L * 60 * 1000,
                    pending,
                )
            }
            ACTION_REMIND_FIRE -> {
                Notifications.notifyIncoming(
                    context = context,
                    threadId = threadId,
                    address = intent.getStringExtra(Notifications.EXTRA_ADDRESS) ?: "",
                    displayName = intent.getStringExtra(Notifications.EXTRA_NAME) ?: "",
                    body = intent.getStringExtra(Notifications.EXTRA_BODY) ?: "",
                )
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.clawcode.smsfilter.action.REPLY"
        const val ACTION_MARK_READ = "com.clawcode.smsfilter.action.MARK_READ"
        const val ACTION_REMIND = "com.clawcode.smsfilter.action.REMIND"
        const val ACTION_REMIND_FIRE = "com.clawcode.smsfilter.action.REMIND_FIRE"
    }
}
