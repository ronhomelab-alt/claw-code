package com.clawcode.smsfilter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/** Incoming-message notifications with Google-Messages-style reply/mark-read actions. */
object Notifications {

    const val CHANNEL_ID = "incoming_sms"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_THREAD_ID = "thread_id"
    const val KEY_REPLY_TEXT = "reply_text"

    fun notifyIncoming(
        context: Context,
        threadId: Long,
        address: String,
        displayName: String,
        body: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
        )

        val notificationId = threadId.toInt()

        val openIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_ADDRESS, address)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val replyIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, MessageActionReceiver::class.java)
                .setAction(MessageActionReceiver.ACTION_REPLY)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_THREAD_ID, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyIntent,
        )
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()

        val markReadIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1_000_000,
            Intent(context, MessageActionReceiver::class.java)
                .setAction(MessageActionReceiver.ACTION_MARK_READ)
                .putExtra(EXTRA_THREAD_ID, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Mark as read",
            markReadIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()

        val sender = Person.Builder().setName(displayName).setKey(address).build()
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("You").setKey("self").build()
        ).addMessage(body, System.currentTimeMillis(), sender)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setContentIntent(openIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        manager.notify(notificationId, notification)
    }

    fun cancel(context: Context, threadId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(threadId.toInt())
    }
}
