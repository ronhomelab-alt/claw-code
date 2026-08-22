package com.clawcode.smsfilter

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stable pastel avatar palette — a contact always hashes to the same color,
 * Signal/Google-Messages style. Pastel backgrounds read fine in both themes.
 */
private val AVATAR_COLORS = listOf(
    Color(0xFFFFD8E4) to Color(0xFF31111D), // pink
    Color(0xFFC8E6C9) to Color(0xFF10381A), // green
    Color(0xFFFFE0B2) to Color(0xFF4A2800), // orange
    Color(0xFFB3E5FC) to Color(0xFF00344A), // blue
    Color(0xFFD1C4E9) to Color(0xFF241435), // purple
    Color(0xFFB2DFDB) to Color(0xFF00332E), // teal
    Color(0xFFFFF9C4) to Color(0xFF3D3200), // yellow
    Color(0xFFFFCDD2) to Color(0xFF4A0D10), // red
)

internal fun avatarColorsFor(name: String): Pair<Color, Color> {
    var hash = 0
    for (ch in name) hash = hash * 31 + ch.code
    val index = ((hash % AVATAR_COLORS.size) + AVATAR_COLORS.size) % AVATAR_COLORS.size
    return AVATAR_COLORS[index]
}

/** Decoded-thumbnail cache so list scrolling doesn't re-decode photos. */
private val avatarBitmapCache = LruCache<String, ImageBitmap>(64)

/**
 * Circular avatar: the contact-book photo when [photoUri] resolves, otherwise
 * a colored circle with the first letter of [name].
 */
@Composable
fun ContactAvatar(name: String, photoUri: String?, size: Dp = 44.dp) {
    val context = LocalContext.current
    val photo by produceState(
        initialValue = photoUri?.let { avatarBitmapCache.get(it) },
        photoUri,
    ) {
        if (photoUri == null) {
            value = null
            return@produceState
        }
        val cached = avatarBitmapCache.get(photoUri)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (e: Exception) {
                null
            }
        }?.also { avatarBitmapCache.put(photoUri, it) }
    }
    val bitmap = photo
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        val (bg, fg) = avatarColorsFor(name)
        Box(
            Modifier.size(size).background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                color = fg,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}
