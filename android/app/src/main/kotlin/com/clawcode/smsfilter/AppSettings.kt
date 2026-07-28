package com.clawcode.smsfilter

import android.content.Context

/** User-facing privacy/security switches, persisted across restarts. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * When true, incoming-message notifications show only the sender —
     * never the message text — so scam content can't appear on the lock
     * screen or notification shade.
     */
    var hideNotificationPreviews: Boolean
        get() = prefs.getBoolean(KEY_HIDE_PREVIEWS, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_PREVIEWS, value).apply()

    /** Days after which blocked messages auto-delete; 0 = keep forever. */
    var autoDeleteBlockedDays: Int
        get() = prefs.getInt(KEY_AUTO_DELETE_DAYS, 0)
        set(value) = prefs.edit().putInt(KEY_AUTO_DELETE_DAYS, value).apply()

    companion object {
        private const val KEY_HIDE_PREVIEWS = "hide_notification_previews"
        private const val KEY_AUTO_DELETE_DAYS = "auto_delete_blocked_days"

        /** Label to days; 0 means never. */
        val AUTO_DELETE_CHOICES = listOf(
            "Never" to 0,
            "5 days" to 5,
            "30 days" to 30,
            "6 months" to 180,
        )
    }
}
