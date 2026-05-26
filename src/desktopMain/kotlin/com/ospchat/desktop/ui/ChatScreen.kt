@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.ospchat.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.messages.Message
import com.ospchat.shared.data.reactions.Reaction
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun ChatScreen(
    peer: Peer,
    avatarLocalPath: String?,
    messages: List<Message>,
    reactions: List<Reaction>,
    selfUuid: String,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendAttachment: (bytes: ByteArray) -> Unit,
    onReact: (Message, String?) -> Unit,
    onCall: () -> Unit = {},
    onVisible: () -> Unit = {},
    onHidden: () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var emojiTarget by remember { mutableStateOf<Message?>(null) }
    var showComposerEmoji by remember { mutableStateOf(false) }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }

    DisposableEffect(peer.uuid) {
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
            Avatar(
                nickname = peer.nickname,
                uuid = peer.uuid,
                localPath = avatarLocalPath,
                size = 36.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = peer.nickname, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = peer.displayAddress(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCall) {
                Icon(Icons.Filled.Call, contentDescription = "Voice call")
            }
        }
        HorizontalDivider()

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Say hello",
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
                items(messages, key = { it.id }) { msg ->
                    val msgReactions = reactions.filter { it.messageId == msg.id }
                    MessageBubble(
                        message = msg,
                        reactions = msgReactions,
                        selfUuid = selfUuid,
                        onContextMenu = { emojiTarget = msg },
                        onReactionToggle = { emoji ->
                            val mine = msgReactions.firstOrNull { it.fromUuid == selfUuid }
                            val newEmoji = if (mine?.emoji == emoji) null else emoji
                            onReact(msg, newEmoji)
                        },
                        onImageTap = { path -> fullscreenImagePath = path },
                    )
                }
            }
        }

        HorizontalDivider()
        ChatComposer(
            draft = draft,
            onDraftChange = { draft = it },
            onSend = {
                onSend(it)
                draft = ""
            },
            onAttachImage = { bytes -> onSendAttachment(bytes) },
            onEmojiClick = { showComposerEmoji = true },
        )
    }

    emojiTarget?.let { target ->
        EmojiPickerDialog(
            title = "React",
            onDismiss = { emojiTarget = null },
            onPick = { emoji ->
                onReact(target, emoji)
                emojiTarget = null
            },
        )
    }

    if (showComposerEmoji) {
        EmojiPickerDialog(
            title = "Insert emoji",
            onDismiss = { showComposerEmoji = false },
            onPick = { emoji -> draft += emoji },
        )
    }

    fullscreenImagePath?.let { path ->
        FullscreenImageOverlay(path = path, onDismiss = { fullscreenImagePath = null })
    }
}

@Composable
private fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onAttachImage: (ByteArray) -> Unit,
    onEmojiClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = {
            val bytes = pickImageBytes()
            if (bytes != null) onAttachImage(bytes)
        }) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Attach image")
        }
        IconButton(onClick = onEmojiClick) {
            Text(
                text = "😊",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = EmojiFont.family,
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Message") },
            singleLine = false,
            textStyle = LocalTextStyle.current.copy(fontFamily = EmojiFont.family),
            modifier = Modifier.weight(1f),
        )
        val trimmed = draft.trim()
        IconButton(
            onClick = { onSend(trimmed) },
            enabled = trimmed.isNotEmpty(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

/** Opens the OS file picker filtered to JPEG/PNG and returns the picked bytes. */
private fun pickImageBytes(): ByteArray? {
    val dialog = FileDialog(null as Frame?, "Pick an image", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        val n = name.lowercase()
        n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp")
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    val target = File(dir, file)
    if (!target.isFile) return null
    return runCatching { target.readBytes() }.getOrNull()
}

@Composable
private fun MessageBubble(
    message: Message,
    reactions: List<Reaction>,
    selfUuid: String,
    onContextMenu: () -> Unit,
    onReactionToggle: (String) -> Unit,
    onImageTap: (String) -> Unit,
) {
    val mine = message.direction == Message.Direction.OUT
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
                    .onClick(
                        matcher = PointerMatcher.mouse(PointerButton.Secondary),
                        onClick = onContextMenu,
                    ).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) {
                Text(
                    text = message.fromNickname,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
            }
            if (message.body.isNotBlank()) {
                Text(
                    text = emojiAware(message.body),
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Attachment preview (if any).
            message.attachment?.let { att ->
                Spacer(modifier = Modifier.height(4.dp))
                val path = att.localPath
                if (path == null) {
                    Text(
                        "[image — downloading…]",
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                } else {
                    FileImage(
                        path = path,
                        width = att.width,
                        height = att.height,
                        modifier = Modifier.clickable { onImageTap(path) },
                    )
                }
            }
            if (reactions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                ReactionChips(
                    reactions = reactions,
                    selfUuid = selfUuid,
                    onToggle = onReactionToggle,
                )
            }
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
                            Message.Status.SENDING -> "Sending…" to faded
                            Message.Status.DELIVERED -> "✓" to faded
                            Message.Status.READ -> "✓✓" to MaterialTheme.colorScheme.primary
                            Message.Status.FAILED -> "⚠ Not delivered" to MaterialTheme.colorScheme.error
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

/**
 * Aggregate reactions by emoji, render as chips. Self's chip is highlighted;
 * tapping any chip toggles the local user's reaction on that emoji.
 */
@Composable
private fun ReactionChips(
    reactions: List<Reaction>,
    selfUuid: String,
    onToggle: (String) -> Unit,
) {
    val grouped = reactions.groupBy { it.emoji }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        grouped.forEach { (emoji, list) ->
            val mine = list.any { it.fromUuid == selfUuid }
            val bg =
                if (mine) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            Surface(
                shape = CircleShape,
                color = bg,
                modifier =
                    Modifier
                        .padding(end = 2.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .clickable(onClick = { onToggle(emoji) })
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = EmojiFont.family,
                    )
                    Text("${list.size}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
