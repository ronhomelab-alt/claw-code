package com.clawcode.smsfilter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Theme preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What a swipe does on a conversation row. */
enum class SwipeAction { NONE, BLOCK, READ_UNREAD, DELETE, STAR }

/** User-facing switches, persisted across restarts. */
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

    /** Theme, as a reactive flow so the whole UI recolors immediately on change. */
    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val theme: StateFlow<ThemeMode> = _theme

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    /** Auto-delete one-time-passcode messages older than 24h (privacy). */
    var autoDeleteOtp: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE_OTP, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DELETE_OTP, value).apply()

    /** Render iPhone tapback reactions ("Loved ...") with an emoji. */
    var showIphoneReactionsAsEmoji: Boolean
        get() = prefs.getBoolean(KEY_IPHONE_REACTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_IPHONE_REACTIONS, value).apply()

    var swipeRightAction: SwipeAction
        get() = readSwipe(KEY_SWIPE_RIGHT, SwipeAction.BLOCK)
        set(value) = prefs.edit().putString(KEY_SWIPE_RIGHT, value.name).apply()

    var swipeLeftAction: SwipeAction
        get() = readSwipe(KEY_SWIPE_LEFT, SwipeAction.READ_UNREAD)
        set(value) = prefs.edit().putString(KEY_SWIPE_LEFT, value.name).apply()

    private fun readSwipe(key: String, default: SwipeAction): SwipeAction =
        runCatching { SwipeAction.valueOf(prefs.getString(key, null) ?: default.name) }
            .getOrDefault(default)

    companion object {
        private const val KEY_HIDE_PREVIEWS = "hide_notification_previews"
        private const val KEY_AUTO_DELETE_DAYS = "auto_delete_blocked_days"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_AUTO_DELETE_OTP = "auto_delete_otp"
        private const val KEY_IPHONE_REACTIONS = "iphone_reactions_emoji"
        private const val KEY_SWIPE_RIGHT = "swipe_right_action"
        private const val KEY_SWIPE_LEFT = "swipe_left_action"

        /** Label to days; 0 means never. */
        val AUTO_DELETE_CHOICES = listOf(
            "Never" to 0,
            "5 days" to 5,
            "30 days" to 30,
            "6 months" to 180,
        )

        /** Human labels for swipe actions. */
        val SWIPE_LABELS = mapOf(
            SwipeAction.NONE to "Nothing",
            SwipeAction.BLOCK to "Block",
            SwipeAction.READ_UNREAD to "Read/Unread",
            SwipeAction.DELETE to "Delete",
            SwipeAction.STAR to "Star",
        )
    }
}
