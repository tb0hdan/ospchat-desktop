package com.ospchat.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ospchat.shared.data.groups.GroupKind
import com.ospchat.shared.data.groups.GroupRecord

@Composable
fun GroupsScreen(
    groups: List<GroupRecord>,
    onGroupClick: (GroupRecord) -> Unit,
    onNewGroup: () -> Unit,
) {
    val chats = groups.filter { it.kind == GroupKind.CHAT }
    val broadcasts = groups.filter { it.kind == GroupKind.BROADCAST }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewGroup,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New group") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = "Groups", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Group chats and broadcast channels you're a member of",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No groups yet. Tap “New group” to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (chats.isNotEmpty()) {
                        item { SectionHeader(label = "Group chats", count = chats.size) }
                        items(chats, key = { it.id }) { group ->
                            GroupRow(group = group, onClick = { onGroupClick(group) })
                            HorizontalDivider()
                        }
                    }
                    if (broadcasts.isNotEmpty()) {
                        item { SectionHeader(label = "Broadcast channels", count = broadcasts.size) }
                        items(broadcasts, key = { it.id }) { group ->
                            GroupRow(group = group, onClick = { onGroupClick(group) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
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
        AssistChip(
            onClick = {},
            label = { Text("$count") },
            colors = AssistChipDefaults.assistChipColors(),
        )
    }
}

@Composable
private fun GroupRow(
    group: GroupRecord,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = group.name, style = MaterialTheme.typography.bodyLarge)
                if (group.unreadCount > 0) {
                    Text(
                        text = "(${group.unreadCount})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (group.isCreator) {
                    AssistChip(onClick = {}, label = { Text("creator") })
                }
            }
            Text(
                text = "${group.memberCount} member${if (group.memberCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
