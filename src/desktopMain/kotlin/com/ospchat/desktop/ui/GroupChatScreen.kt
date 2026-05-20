package com.ospchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ospchat.shared.data.groups.GroupKind
import com.ospchat.shared.data.groups.GroupMessage
import com.ospchat.shared.data.groups.GroupRecord

@Composable
fun GroupChatScreen(
    group: GroupRecord,
    messages: List<GroupMessage>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onLeave: () -> Unit,
    onVisible: () -> Unit = {},
    onHidden: () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showEmojiPicker by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val canPost = group.kind != GroupKind.BROADCAST || group.isCreator

    DisposableEffect(group.id) {
        onVisible()
        onDispose { onHidden() }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = group.name, style = MaterialTheme.typography.titleMedium)
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (group.kind == GroupKind.BROADCAST) "broadcast" else "chat",
                            )
                        },
                    )
                    if (group.isCreator) {
                        AssistChip(onClick = {}, label = { Text("creator") })
                    }
                }
                Text(
                    text =
                        "${group.memberCount} member${if (group.memberCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!group.isCreator) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Group actions")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Leave group") },
                            onClick = {
                                menuExpanded = false
                                onLeave()
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text =
                        if (canPost) "Say hello" else "Read-only — only the creator can post in this channel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(vertical = 16.dp),
            ) {
                items(messages, key = { it.id }) { msg -> GroupBubble(msg) }
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { showEmojiPicker = true }, enabled = canPost) {
                Text(
                    text = "😊",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = EmojiFont.family,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(if (canPost) "Message" else "Only the creator can post here")
                },
                singleLine = false,
                enabled = canPost,
                textStyle = LocalTextStyle.current.copy(fontFamily = EmojiFont.family),
                modifier = Modifier.weight(1f),
            )
            val trimmed = draft.trim()
            IconButton(
                onClick = {
                    onSend(trimmed)
                    draft = ""
                },
                enabled = canPost && trimmed.isNotEmpty(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }

    if (showEmojiPicker) {
        EmojiPickerDialog(
            title = "Insert emoji",
            onDismiss = { showEmojiPicker = false },
            onPick = { emoji -> draft += emoji },
        )
    }
}

@Composable
private fun GroupBubble(message: GroupMessage) {
    val mine = message.direction == GroupMessage.Direction.OUT
    val containerColor =
        if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .background(color = containerColor, shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) {
                Text(
                    text = message.fromNickname,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
            }
            Text(
                text = emojiAware(message.body),
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        kotlinx.datetime.Instant
                            .fromEpochMilliseconds(message.sentAt)
                            .toString()
                            .substringAfter('T')
                            .substringBefore('.'),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f),
                )
                if (mine) {
                    val faded = textColor.copy(alpha = 0.6f)
                    val (statusText, statusColor) =
                        when (message.status) {
                            GroupMessage.Status.SENDING -> "Sending…" to faded
                            GroupMessage.Status.DELIVERED -> "✓" to faded
                            GroupMessage.Status.FAILED -> "⚠ Not delivered" to MaterialTheme.colorScheme.error
                        }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
            }
        }
    }
}
