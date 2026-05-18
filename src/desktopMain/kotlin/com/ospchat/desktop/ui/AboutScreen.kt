package com.ospchat.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

private const val PROJECT_HOME_URL = "https://ospchat.com"

@Composable
fun AboutScreen(
    nickname: String,
    selfUuid: String,
    selfAvatarPath: String?,
    boundPort: Int,
    onRenameNickname: (String) -> Unit,
    onPickAvatar: (ByteArray) -> Unit,
    onClearAvatar: () -> Unit,
    onExit: () -> Unit,
) {
    var draft by remember(nickname) { mutableStateOf(nickname) }
    val trimmed = draft.trim()
    val dirty = trimmed.isNotEmpty() && trimmed != nickname

    var confirmExit by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "About OSPChat", style = MaterialTheme.typography.titleLarge)

        // Avatar preview + actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                nickname = nickname,
                uuid = selfUuid,
                localPath = selfAvatarPath,
                size = 64.dp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (selfAvatarPath.isNullOrBlank()) "Initials avatar" else "Custom avatar",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val bytes = pickAvatarBytes()
                        if (bytes != null) onPickAvatar(bytes)
                    }) { Text("Change…") }
                    if (!selfAvatarPath.isNullOrBlank()) {
                        OutlinedButton(onClick = onClearAvatar) { Text("Remove") }
                    }
                }
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LabelValue("Desktop version", com.ospchat.desktop.BuildInfo.VERSION)
            LabelValue("Wire API", "v1 (OpenAPI 0.8.0)")
            LabelValue(
                "Embedded server",
                if (boundPort > 0) "port $boundPort" else "starting…",
            )
        }

        HorizontalDivider()

        Text(
            text = "Nickname",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text =
                "Other peers see this name. Renaming bounces nothing — peers pick up the change on next discovery.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier.widthIn(min = 280.dp),
            )
            Button(onClick = { onRenameNickname(trimmed) }, enabled = dirty) { Text("Save") }
        }

        HorizontalDivider()

        Text(
            text = "Project",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = { openInBrowser(PROJECT_HOME_URL) }) {
            Text(PROJECT_HOME_URL)
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        Button(
            onClick = { confirmExit = true },
            modifier = Modifier.fillMaxWidth(fraction = 0.4f),
        ) { Text("Exit OSPChat") }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Exit OSPChat?") },
            text = { Text("This stops mDNS discovery and the embedded server, then closes the window.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onExit()
                }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

private fun pickAvatarBytes(): ByteArray? {
    val dialog = FileDialog(null as Frame?, "Pick an avatar image", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        val n = name.lowercase()
        n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    val target = File(dir, file)
    if (!target.isFile) return null
    return runCatching { target.readBytes() }.getOrNull()
}
