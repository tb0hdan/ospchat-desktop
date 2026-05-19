package com.ospchat.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * Decode an image from a local file path off the composition thread (Skia
 * `Image.makeFromEncoded` is CPU-bound). Renders a tiny placeholder until
 * the bytes are decoded; switches to the bitmap once ready.
 */
@Composable
fun FileImage(
    path: String,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
) {
    val bitmapState =
        produceState<ImageBitmap?>(initialValue = null, key1 = path) {
            value =
                runCatching {
                    withContext(Dispatchers.IO) {
                        SkiaImage.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
                    }
                }.getOrNull()
        }
    val bitmap = bitmapState.value

    val maxW = 320.dp
    val aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
    val maxH = if (aspect > 0f) (maxW / aspect) else maxW

    Box(
        modifier =
            modifier
                .widthIn(max = maxW)
                .heightIn(max = maxH)
                .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(
                "[image…]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
