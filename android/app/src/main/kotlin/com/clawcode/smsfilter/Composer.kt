package com.clawcode.smsfilter

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/**
 * "SMS · used / limit" (plus part count when the text splits into several
 * messages). Null until the text is long enough for the counter to matter.
 */
internal fun smsCounterLabel(text: String): String? {
    if (text.isEmpty()) return null
    return try {
        val info = android.telephony.SmsMessage.calculateLength(text, false)
        val parts = info[0]
        val used = info[1]
        val remaining = info[2]
        if (parts > 1 || used >= 120) {
            buildString {
                append("SMS · ").append(used).append(" / ").append(used + remaining)
                if (parts > 1) append(" · ").append(parts).append(" parts")
            }
        } else {
            null
        }
    } catch (e: Exception) {
        if (text.length >= 120) "SMS · ${text.length} / 160" else null
    }
}

/**
 * Google-Messages-style compose bar: one rounded pill holding a text field
 * that grows up to 8 lines, next to a circular send button that only lights
 * up in the accent color once there's something to send.
 */
@Composable
fun PillComposer(
    value: String,
    onValueChange: (String) -> Unit,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 8,
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    "Text message",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                smsCounterLabel(value)?.let { counter ->
                    Text(
                        counter,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        val active = sendEnabled && value.isNotBlank()
        val sendBg by animateColorAsState(
            if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            label = "sendBg",
        )
        val sendFg by animateColorAsState(
            if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.outline,
            label = "sendFg",
        )
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(sendBg)
                .clickable(enabled = active) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = sendFg)
        }
    }
}
