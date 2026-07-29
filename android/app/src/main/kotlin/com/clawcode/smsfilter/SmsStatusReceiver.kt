package com.clawcode.smsfilter

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Receives SMS delivery reports (the deliveryIntent passed to sendTextMessage)
 * and updates the sent message's STATUS column, so the thread can show
 * "Delivered" / "Not delivered".
 */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val status = if (resultCode == Activity.RESULT_OK) {
            Telephony.Sms.STATUS_COMPLETE
        } else {
            Telephony.Sms.STATUS_FAILED
        }
        try {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(Telephony.Sms.STATUS, status) },
                null,
                null,
            )
        } catch (e: Exception) {
            android.util.Log.w("SmsStatusReceiver", "could not update delivery status", e)
        }
    }
}
