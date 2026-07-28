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
        ruleStore = RuleStore(this)
        blockedLog = BlockedLog(this)
        messagingRepository = MessagingRepository(this)
        settings = AppSettings(this)
    }

    companion object {
        fun from(context: android.content.Context): App =
            context.applicationContext as App
    }
}
