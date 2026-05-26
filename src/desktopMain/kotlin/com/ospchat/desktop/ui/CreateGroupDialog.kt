package com.ospchat.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ospchat.shared.data.groups.GroupKind
import com.ospchat.shared.data.peers.PeerRecord

@Composable
fun CreateGroupDialog(
    candidates: List<PeerRecord>,
    onDismiss: () -> Unit,
    onCreate: (name: String, kind: GroupKind, memberUuids: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(GroupKind.CHAT) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    val trimmed = name.trim()
    val canCreate = trimmed.isNotEmpty() && selected.any { it.value }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create group") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == GroupKind.CHAT,
                        onClick = { kind = GroupKind.CHAT },
                        label = { Text("Group chat") },
                    )
                    FilterChip(
                        selected = kind == GroupKind.BROADCAST,
                        onClick = { kind = GroupKind.BROADCAST },
                        label = { Text("Broadcast channel") },
                    )
                }
                Text(
                    text =
                        if (kind == GroupKind.BROADCAST) {
                            "Only you (as creator) will be able to post; members are read-only."
                        } else {
                            "Any member can post."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Members", style = MaterialTheme.typography.titleSmall)
                    val count = selected.count { it.value }
                    AssistChip(onClick = {}, label = { Text("$count selected") })
                }

                if (candidates.isEmpty()) {
                    Text(
                        "No peers known yet — discover someone on the LAN first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(candidates, key = { it.uuid }) { peer ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = selected[peer.uuid] == true,
                                    onCheckedChange = { selected[peer.uuid] = it },
                                )
                                Column {
                                    Text(
                                        peer.nickname,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        peer.displayAddress() + if (!peer.isOnline) " · offline" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = {
                    val members = selected.filterValues { it }.keys.toList()
                    onCreate(trimmed, kind, members)
                },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
