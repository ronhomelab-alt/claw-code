package com.clawcode.smsfilter

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.clawcode.smsfilter.core.Verdict

/**
 * Companion mode: works *with* Google Messages instead of replacing it.
 *
 * Android does not let a non-default app stop SMS delivery, so while Google
 * Messages remains your default app this listener does the next best thing:
 * when Messages posts a notification whose sender/text matches the block
 * rules, the notification is cancelled immediately and the message is added
 * to this app's quarantine log. The message still exists inside Google
 * Messages (archive/delete it there), but it never interrupts you.
 *
 * Requires the user to grant Notification Access in system settings.
 */
class MessagesNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            screen(sbn)
        } catch (e: Exception) {
            android.util.Log.e("MessagesNotifListener", "failed to screen notification", e)
        }
    }

    private fun screen(sbn: StatusBarNotification) {
        if (sbn.packageName !in MESSAGING_PACKAGES) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        val app = App.from(this)
        // The notification title is the sender: a contact NAME for saved
        // contacts, or a number / alphanumeric sender ID otherwise. Only a
        // VERIFIED contact (by number or exact display-name match) is exempt
        // from text rules — "contains letters" alone would let alphanumeric
        // spam sender IDs bypass the filter.
        val isContact = app.messagingRepository.isContact(title) ||
            app.messagingRepository.isContactName(title)
        when (val verdict = app.ruleStore.engine().evaluate(title, text, isContact)) {
            is Verdict.Block -> {
                cancelNotification(sbn.key)
                app.blockedLog.append(
                    BlockedMessage(
                        timestampMs = System.currentTimeMillis(),
                        sender = title,
                        body = text,
                        reason = "${verdict.reason} (notification dismissed; " +
                            "message remains in ${sbn.packageName})",
                    )
                )
            }
            is Verdict.Allow -> Unit
        }
    }

    companion object {
        /** Messaging apps whose notifications we screen in companion mode. */
        private val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging", // Samsung Messages
        )
    }
}
