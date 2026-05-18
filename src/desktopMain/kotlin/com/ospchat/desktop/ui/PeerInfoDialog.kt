package com.ospchat.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ospchat.shared.data.peers.PeerInfo
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun PeerInfoDialog(
    info: PeerInfo,
    onDismiss: () -> Unit,
) {
    val rec = info.record
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text(rec.nickname) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabelValue("UUID", rec.uuid)
                LabelValue(
                    "Status",
                    if (rec.isOnline) "online at ${rec.host}:${rec.port}"
                    else "offline (last seen ${formatTs(rec.lastSeenAt)})",
                )
                LabelValue("First seen", formatTs(rec.firstSeenAt))
                LabelValue("Saved contact", if (rec.isContact) "yes" else "no")

                HorizontalDivider()
                Text("Addresses", fontWeight = FontWeight.SemiBold)
                if (info.addresses.isEmpty()) {
                    Text("(none recorded)", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(info.addresses, key = { "a-${it.host}:${it.port}" }) { addr ->
                            Text(
                                "${addr.host}:${addr.port} — last ${formatTs(addr.lastSeenAt)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                HorizontalDivider()
                Text("Nicknames", fontWeight = FontWeight.SemiBold)
                if (info.nicknames.isEmpty()) {
                    Text("(none recorded)", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(info.nicknames, key = { "n-${it.nickname}" }) { nick ->
                            Text(
                                "${nick.nickname} — last ${formatTs(nick.lastSeenAt)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
    )
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

private fun formatTs(epochMillis: Long): String =
    Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .toString()
        .substringBefore('.')
