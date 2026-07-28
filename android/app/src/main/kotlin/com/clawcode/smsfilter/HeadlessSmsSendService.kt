package com.clawcode.smsfilter

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager

/**
 * Required to hold the default-SMS-app role: handles RESPOND_VIA_MESSAGE
 * (e.g. "reply with message" when declining a call).
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recipient = intent?.dataString
            ?.removePrefix("smsto:")
            ?.removePrefix("sms:")
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!recipient.isNullOrBlank() && !body.isNullOrBlank()) {
            getSystemService(SmsManager::class.java)
                .sendTextMessage(recipient, null, body, null, null)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
