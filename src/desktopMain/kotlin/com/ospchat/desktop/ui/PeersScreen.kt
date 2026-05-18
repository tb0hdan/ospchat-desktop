package com.ospchat.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ospchat.shared.data.peers.PeerRecord

@Composable
fun PeersScreen(
    peers: List<PeerRecord>,
    selfNickname: String,
    selfPort: Int,
    onPeerClick: (PeerRecord) -> Unit,
    onAddContact: (PeerRecord) -> Unit,
    onRemoveContact: (PeerRecord) -> Unit,
    onPeerInfo: (PeerRecord) -> Unit,
) {
    val contacts = peers.filter { it.isContact }
    val visible = peers.filter { !it.isContact && it.isOnline }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "OSPChat — $selfNickname",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (selfPort > 0) "Listening on port $selfPort" else "Starting…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        if (contacts.isEmpty() && visible.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No peers on this network yet. Open OSPChat on another device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (contacts.isNotEmpty()) {
                    item { SectionLabel("Contacts", contacts.size) }
                    items(contacts, key = { "c-${it.uuid}" }) { peer ->
                        PeerRow(
                            peer = peer,
                            onClick = { onPeerClick(peer) },
                            onAddContact = { onAddContact(peer) },
                            onRemoveContact = { onRemoveContact(peer) },
                            onInfo = { onPeerInfo(peer) },
                        )
                        HorizontalDivider()
                    }
                }
                if (visible.isNotEmpty()) {
                    item { SectionLabel("Peers", visible.size) }
                    items(visible, key = { "v-${it.uuid}" }) { peer ->
                        PeerRow(
                            peer = peer,
                            onClick = { onPeerClick(peer) },
                            onAddContact = { onAddContact(peer) },
                            onRemoveContact = { onRemoveContact(peer) },
                            onInfo = { onPeerInfo(peer) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AssistChip(onClick = {}, label = { Text("$count") })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerRow(
    peer: PeerRecord,
    onClick: () -> Unit,
    onAddContact: () -> Unit,
    onRemoveContact: () -> Unit,
    onInfo: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // combinedClickable: tap = open chat, long-press OR right-click = menu.
                // Compose Desktop maps secondary mouse button to onLongClick when
                // combinedClickable is used. Backup: keep the menu also openable
                // explicitly from PeerInfo dialog if needed.
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Avatar(
                nickname = peer.nickname,
                uuid = peer.uuid,
                localPath = peer.avatarLocalPath,
                size = 40.dp,
            )
            // Online dot anchored to the bottom-right of the avatar.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                        .background(
                            color = if (peer.isOnline) Color(0xFF34A853) else Color(0xFFBDBDBD),
                            shape = CircleShape,
                        ),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = peer.nickname, style = MaterialTheme.typography.bodyLarge)
                if (peer.unreadCount > 0) {
                    Text(
                        text = "(${peer.unreadCount})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = "${peer.host}:${peer.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (peer.isContact) {
                DropdownMenuItem(
                    text = { Text("Remove from contacts") },
                    onClick = {
                        menuOpen = false
                        onRemoveContact()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Add to contacts") },
                    onClick = {
                        menuOpen = false
                        onAddContact()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Info…") },
                onClick = {
                    menuOpen = false
                    onInfo()
                },
            )
        }
    }
}
