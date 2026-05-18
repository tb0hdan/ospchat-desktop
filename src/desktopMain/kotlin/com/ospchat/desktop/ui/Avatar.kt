package com.ospchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.io.File

/**
 * Either renders a custom-avatar JPEG from disk, or a deterministic initials
 * avatar derived from [nickname] + [uuid]. Mirrors the Android approach so
 * the same peer renders identically on both clients.
 */
@Composable
fun Avatar(
    nickname: String,
    uuid: String,
    localPath: String?,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    if (localPath.isNullOrBlank()) {
        InitialsAvatar(nickname = nickname, uuid = uuid, size = size, modifier = modifier)
    } else {
        FileAvatar(path = localPath, size = size, modifier = modifier, fallback = {
            InitialsAvatar(nickname = nickname, uuid = uuid, size = size)
        })
    }
}

@Composable
fun InitialsAvatar(
    nickname: String,
    uuid: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val initials = remember(nickname) { computeInitials(nickname) }
    // Background color is keyed on UUID — that way renaming doesn't change the
    // bubble color and the same peer looks consistent across UI revisits.
    val color = remember(uuid) { colorForKey(uuid.ifBlank { nickname }) }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(color),
        contentAlignment = Alignment.Center,
    ) {
        // Scale font with avatar size — 40dp avatar → 16sp text.
        val fontSize = (size.value * 0.4f).sp
        Text(
            text = initials,
            color = Color.White,
            style =
                TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
    }
}

@Composable
private fun FileAvatar(
    path: String,
    size: Dp,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val bitmapState = produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value =
            runCatching {
                withContext(Dispatchers.IO) {
                    SkiaImage.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
                }
            }.getOrNull()
    }
    val bitmap = bitmapState.value
    if (bitmap == null) {
        fallback()
    } else {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    }
}

private fun computeInitials(nickname: String): String {
    val trimmed = nickname.trim()
    if (trimmed.isEmpty()) return "?"
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        trimmed.length >= 2 -> trimmed.take(2).uppercase()
        else -> trimmed.first().uppercaseChar().toString()
    }
}

/**
 * Deterministic color from a key string — same algorithm as the Android
 * client so a peer's avatar bubble matches across both apps.
 */
private fun colorForKey(key: String): Color {
    if (key.isEmpty()) return Color(0xFF607D8B)
    val hash = key.fold(0) { acc, c -> acc * 31 + c.code }
    val index = ((hash and 0x7FFFFFFF) % PALETTE.size)
    return PALETTE[index]
}

private val PALETTE =
    listOf(
        Color(0xFFE57373), Color(0xFFF06292), Color(0xFFBA68C8), Color(0xFF9575CD),
        Color(0xFF7986CB), Color(0xFF64B5F6), Color(0xFF4FC3F7), Color(0xFF4DD0E1),
        Color(0xFF4DB6AC), Color(0xFF81C784), Color(0xFFAED581), Color(0xFFFF8A65),
        Color(0xFFA1887F), Color(0xFF90A4AE), Color(0xFFFFB74D), Color(0xFFDCE775),
    )
