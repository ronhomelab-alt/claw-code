package com.clawcode.smsfilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Handles notification actions: inline reply and mark-as-read. */
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
        }
    }

    companion object {
        const val ACTION_REPLY = "com.clawcode.smsfilter.action.REPLY"
        const val ACTION_MARK_READ = "com.clawcode.smsfilter.action.MARK_READ"
    }
}
