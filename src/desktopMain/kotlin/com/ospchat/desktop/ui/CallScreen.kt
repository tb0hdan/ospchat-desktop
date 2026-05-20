package com.ospchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ospchat.shared.data.calls.Call
import com.ospchat.shared.data.calls.statusLabel
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/**
 * Full-screen in-call UI. Renders the call state (Ringing / Connecting /
 * mm:ss elapsed) plus mute + hangup controls. Covers both outbound
 * "Calling…" and the connected phase of one call.
 *
 * Incoming-call presentation is handled by [IncomingCallDialog], not this
 * screen.
 */
@Composable
fun CallScreen(
    call: Call,
    onMuteToggle: (Boolean) -> Unit,
    onHangUp: () -> Unit,
) {
    var muted by remember { mutableStateOf(false) }
    val stateLabel = call.composeStatusLabel()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                nickname = call.peerNickname,
                uuid = call.peerUuid,
                localPath = null,
                size = 128.dp,
            )
            Text(
                text = call.peerNickname,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.size(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            ) {
                IconButton(
                    onClick = {
                        muted = !muted
                        onMuteToggle(muted)
                    },
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (muted) "Unmute" else "Mute",
                    )
                }
                IconButton(
                    onClick = onHangUp,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "Hang up")
                }
            }
        }
    }
}

/**
 * Renders a per-second-updated "Connected, 0:42" label while the call is
 * CONNECTED. Falls back to direction-aware "Calling…" / "Ringing" /
 * "Connecting…" copy for the other states.
 */
@Composable
private fun Call.composeStatusLabel(): String {
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(id, state) {
        while (state == Call.State.CONNECTED) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    return statusLabel(now)
}

/**
 * Modal incoming-call presentation. Rendered as an overlay over whatever
 * screen the user was on, so the context isn't ripped away while they
 * decide to accept or decline.
 */
@Composable
fun IncomingCallDialog(
    call: Call,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDecline) {
        Box(
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape =
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(12.dp),
                    ).padding(24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Avatar(
                    nickname = call.peerNickname,
                    uuid = call.peerUuid,
                    localPath = null,
                    size = 88.dp,
                )
                Text(
                    text = call.peerNickname,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Incoming voice call",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    IconButton(
                        onClick = onDecline,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                        modifier =
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "Decline")
                    }
                    IconButton(
                        onClick = onAccept,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                        modifier =
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2E7D32)),
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Accept")
                    }
                }
            }
        }
    }
}
