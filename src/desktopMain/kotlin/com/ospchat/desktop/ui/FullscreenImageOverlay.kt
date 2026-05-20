package com.ospchat.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * Full-window image viewer overlaid on the current screen. Dismissable by
 * clicking anywhere or pressing Escape. Renders as a `Popup` (in-window
 * overlay) rather than a `Dialog` (separate OS window) so it covers the
 * chat content seamlessly.
 *
 * `PopupProperties(focusable = true)` makes the overlay grab key focus
 * and forward Escape / system-back to `onDismissRequest`, so we don't
 * need an explicit key handler.
 */
@Composable
fun FullscreenImageOverlay(
    path: String,
    onDismiss: () -> Unit,
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

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties =
            PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
