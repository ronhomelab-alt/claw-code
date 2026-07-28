package com.clawcode.smsfilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required to hold the default-SMS-app role. MMS filtering is not yet
 * implemented — MMS payloads arrive as WAP push PDUs that need a full
 * download/decode pipeline. For now they are accepted untouched.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
