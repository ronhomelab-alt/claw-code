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

    companion object {
        private const val KEY_HIDE_PREVIEWS = "hide_notification_previews"
    }
}
