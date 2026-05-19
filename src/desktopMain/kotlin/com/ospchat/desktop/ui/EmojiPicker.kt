package com.ospchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tabbed scrollable emoji picker over the full Android-bundled set
 * (~1,889 base emojis across 10 categories). Used for both message reactions
 * and inline composer insertion.
 */
@Composable
fun EmojiPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = {
            Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                EmojiPicker(onPick = onPick)
            }
        },
    )
}

@Composable
fun EmojiPicker(onPick: (String) -> Unit) {
    val categories = remember { EmojiCatalog.categories }
    var tabIndex by remember { mutableStateOf(0) }
    val current = categories[tabIndex]

    Column(modifier = Modifier.fillMaxWidth()) {
        ScrollableTabRow(
            selectedTabIndex = tabIndex,
            edgePadding = 0.dp,
        ) {
            categories.forEachIndexed { i, c ->
                Tab(
                    selected = i == tabIndex,
                    onClick = { tabIndex = i },
                    text = { Text(c.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 40.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(current.emojis, key = { it.base }) { emoji ->
                EmojiCell(emoji = emoji.base, onClick = { onPick(emoji.base) })
            }
        }
    }
}

@Composable
private fun EmojiCell(
    emoji: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clickable(onClick = onClick)
                .background(MaterialTheme.colorScheme.surface)
                .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = EmojiFont.family,
        )
    }
}
