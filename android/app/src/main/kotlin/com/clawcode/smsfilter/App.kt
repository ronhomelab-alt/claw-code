package com.clawcode.smsfilter

import android.app.Application

class App : Application() {

    lateinit var ruleStore: RuleStore
        private set

    lateinit var blockedLog: BlockedLog
        private set

    lateinit var messagingRepository: MessagingRepository
        private set

    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        ruleStore = RuleStore(this)
        blockedLog = BlockedLog(this)
        messagingRepository = MessagingRepository(this)
        settings = AppSettings(this)
    }

    /**
     * Records any crash's stack trace to a file shown in the Setup tab, so a
     * "keeps stopping" dialog always leaves behind an exact diagnosis.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.util.Date()}\n${android.util.Log.getStackTraceString(throwable)}"
                )
            } catch (_: Exception) {
                // Never let crash reporting cause its own crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"

        fun from(context: android.content.Context): App =
            context.applicationContext as App
    }
}
