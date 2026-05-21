package com.ospchat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ospchat.shared.data.calls.Call
import com.ospchat.shared.data.calls.statusLabel
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/**
 * Thin horizontal banner surfacing the currently-active voice call from any
 * screen except [Screen.InCall] (whose full UI has its own controls). Shows
 * the peer nickname, the live status / duration label, and a hangup button.
 * Tapping the bar (outside the hangup button) expands the full in-call
 * screen so the user can return to mute / etc.
 */
@Composable
fun CallStatusBar(
    call: Call,
    onClick: () -> Unit,
    onHangUp: () -> Unit,
) {
    // Drive the per-second redraw of the "Connected · m:ss" timer. Mirrors
    // the same approach used inside CallScreen.composeStatusLabel().
    var nowMillis by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(call.id, call.state) {
        while (call.state == Call.State.CONNECTED) {
            nowMillis = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                nickname = call.peerNickname,
                uuid = call.peerUuid,
                localPath = null,
                size = 32.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = call.peerNickname,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = call.statusLabel(nowMillis),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Plain Box+clickable instead of IconButton: Material3's IconButton
            // enforces a 48 dp minimum interactive size, so .size(28.dp) only
            // shrinks the visible red surface and the actual click footprint
            // (and padded layout slot) stays at 48 dp — which made the button
            // look much bigger than 28 dp in the bar.
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .clickable(onClick = onHangUp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CallEnd,
                    contentDescription = "Hang up",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
