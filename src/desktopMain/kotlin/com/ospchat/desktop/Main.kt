package com.ospchat.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.ospchat.desktop.ui.AboutScreen
import com.ospchat.desktop.ui.CallScreen
import com.ospchat.desktop.ui.ChatScreen
import com.ospchat.desktop.ui.CreateGroupDialog
import com.ospchat.desktop.ui.GroupChatScreen
import com.ospchat.desktop.ui.GroupsScreen
import com.ospchat.desktop.ui.IncomingCallDialog
import com.ospchat.desktop.ui.MainShell
import com.ospchat.desktop.ui.NicknameScreen
import com.ospchat.desktop.ui.PeerInfoDialog
import com.ospchat.desktop.ui.PeersScreen
import com.ospchat.desktop.ui.Screen
import com.ospchat.desktop.ui.Tab
import com.ospchat.shared.data.calls.Call
import com.ospchat.shared.data.groups.GroupMessage
import com.ospchat.shared.data.messages.Message
import com.ospchat.shared.data.peers.PeerRecord
import com.ospchat.shared.data.reactions.Reaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() =
    application {
        val container = remember { AppContainer() }
        val controller = remember { AppController(container) }
        var windowVisible by remember { mutableStateOf(true) }

        val trayState = rememberTrayState()
        val trayPainter = rememberVectorPainter(Icons.Filled.Forum)

        // Plumb the Compose tray into the notifier. The notifier is constructed
        // inside AppContainer before any Compose state exists; here we hand it a
        // callback so inbound messages can surface as tray notifications.
        LaunchedEffect(trayState) {
            if (isTraySupported) {
                container.messageNotifier.sender = { title, body ->
                    trayState.sendNotification(
                        Notification(title = title, message = body, type = Notification.Type.Info),
                    )
                }
            }
        }

        val exitFully = {
            // UI dismisses instantly; backend cleanup runs in a daemon thread and
            // a 700 ms hard-exit guarantee runs alongside it (see AppController.shutdown).
            controller.shutdown()
            exitApplication()
        }

        if (isTraySupported) {
            Tray(
                state = trayState,
                icon = trayPainter,
                tooltip = "OSPChat",
                menu = {
                    Item("Show window", enabled = !windowVisible, onClick = { windowVisible = true })
                    Item("Hide window", enabled = windowVisible, onClick = { windowVisible = false })
                    Separator()
                    Item("Exit", onClick = exitFully)
                },
            )
        }
        // If the tray isn't supported (e.g. GNOME/Wayland without an indicator
        // extension), closing the window must fully exit — otherwise the user
        // has no way to bring it back.
        val onWindowClose: () -> Unit =
            if (isTraySupported) {
                { windowVisible = false }
            } else {
                exitFully
            }

        if (windowVisible) {
            Window(
                // Close (X): hide-to-tray when the tray is available, full exit otherwise.
                onCloseRequest = onWindowClose,
                title = "OSPChat",
                state = rememberWindowState(size = DpSize(960.dp, 720.dp)),
            ) {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppRoot(controller = controller, onExit = exitFully)
                    }
                }
            }
        }
    }

@Composable
private fun AppRoot(
    controller: AppController,
    onExit: () -> Unit,
) {
    var nickname by remember { mutableStateOf<String?>(null) }
    var selfUuid by remember { mutableStateOf<String?>(null) }
    var bootChecked by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        selfUuid = controller.ensureUuid()
        val stored = controller.currentNickname()
        if (!stored.isNullOrBlank()) {
            nickname = stored
            controller.start(stored)
        }
        bootChecked = true
    }

    if (!bootChecked) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        return
    }

    val current = nickname
    if (current.isNullOrBlank()) {
        NicknameScreen(
            initial = "",
            onSubmit = { entered ->
                uiScope.launch {
                    controller.setNickname(entered)
                    nickname = entered
                    controller.start(entered)
                }
            },
        )
        return
    }

    MainRoot(
        controller = controller,
        nickname = current,
        selfUuid = selfUuid.orEmpty(),
        onRenameNickname = { newName ->
            uiScope.launch {
                controller.setNickname(newName)
                nickname = newName
            }
        },
        onExit = onExit,
    )
}

@Composable
private fun MainRoot(
    controller: AppController,
    nickname: String,
    selfUuid: String,
    onRenameNickname: (String) -> Unit,
    onExit: () -> Unit,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var infoPeer by remember { mutableStateOf<PeerRecord?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    when (val current = screen) {
        Screen.Nickname,
        Screen.Main,
        -> {
            MainShell { tab ->
                when (tab) {
                    Tab.Contacts -> {
                        ContactsTab(
                            controller = controller,
                            nickname = nickname,
                            onPeerClick = { peerRecord ->
                                screen = Screen.Chat(peerRecord.toPeer())
                                controller.markPeerRead(peerRecord.uuid)
                            },
                            onAddContact = { controller.addToContacts(it.uuid) },
                            onRemoveContact = { controller.removeFromContacts(it.uuid) },
                            onInfo = { infoPeer = it },
                        )
                    }

                    Tab.Groups -> {
                        GroupsTab(
                            controller = controller,
                            onGroupClick = { group ->
                                screen = Screen.GroupChat(group.id)
                                controller.markGroupRead(group.id)
                            },
                            onNewGroup = { showCreateGroup = true },
                        )
                    }

                    Tab.About -> {
                        AboutTab(
                            controller = controller,
                            nickname = nickname,
                            selfUuid = selfUuid,
                            onRenameNickname = onRenameNickname,
                            onExit = onExit,
                        )
                    }
                }
            }
        }

        is Screen.Chat -> {
            val peerRec by controller.container.peerRepository
                .observeOne(current.peer.uuid)
                .collectAsState(initial = null)
            val messages by controller.container.messageRepository
                .messagesFor(current.peer.uuid)
                .collectAsState(initial = emptyList<Message>())
            val reactions by controller.container.reactionRepository
                .reactionsForPeer(current.peer.uuid)
                .collectAsState(initial = emptyList<Reaction>())
            ChatScreen(
                peer = current.peer,
                avatarLocalPath = peerRec?.avatarLocalPath,
                messages = messages,
                reactions = reactions,
                selfUuid = selfUuid,
                onBack = { screen = Screen.Main },
                onSend = { body -> controller.sendText(current.peer, body) },
                onSendAttachment = { bytes ->
                    controller.sendImageAttachment(current.peer, body = "", bytes = bytes)
                },
                onReact = { message, emoji ->
                    uiScope.launch { controller.reactToMessage(current.peer, message.id, emoji) }
                },
                onCall = {
                    controller.startCall(current.peer) { callId ->
                        screen = Screen.InCall(callId)
                    }
                },
                onVisible = { controller.onPeerChatVisible(current.peer.uuid) },
                onHidden = { controller.onPeerChatHidden(current.peer.uuid) },
            )
        }

        is Screen.InCall -> {
            val activeCall by controller.container.callRepository.activeCall
                .collectAsState(initial = null)
            val call = activeCall
            if (call != null && call.id == current.callId) {
                CallScreen(
                    call = call,
                    onMuteToggle = { muted -> controller.setCallMuted(call.id, muted) },
                    onHangUp = {
                        controller.hangUp(call.id)
                        screen = Screen.Main
                    },
                )
            } else {
                // The active call disappeared (remote hangup, ICE failure,
                // no-answer timeout). Pop back to where the user was.
                LaunchedEffect(current.callId, activeCall) {
                    screen = Screen.Main
                }
            }
        }

        is Screen.GroupChat -> {
            val group by controller.container.groupRepository
                .observeOne(current.groupId)
                .collectAsState(initial = null)
            val messages by controller.container.groupMessageRepository
                .messagesFor(current.groupId)
                .collectAsState(initial = emptyList<GroupMessage>())
            val groupReactions by controller.container.reactionRepository
                .reactionsForGroup(current.groupId)
                .collectAsState(initial = emptyList<Reaction>())
            val groupSnapshot = group

            // If the group disappears while we're on this screen — common
            // when leaveGroup's background broadcast takes seconds to time
            // out unreachable peers and applyLocalLeave only deletes the
            // row afterwards, while the user has navigated back into the
            // same group — pop to the main shell instead of rendering a
            // dead-end full-area error. The Screen.GroupChat branch
            // doesn't include the NavigationRail, so a stuck null state
            // really does strand the user with no way out.
            var hasLoaded by remember(current.groupId) { mutableStateOf(false) }
            LaunchedEffect(groupSnapshot, current.groupId) {
                if (groupSnapshot != null) {
                    hasLoaded = true
                } else if (hasLoaded) {
                    screen = Screen.Main
                } else {
                    // Initial null from collectAsState(initial = null) before
                    // the Flow loads. Give it a moment; if still null, the
                    // group was already gone when we navigated in.
                    delay(200)
                    if (group == null) screen = Screen.Main
                }
            }

            if (groupSnapshot != null) {
                GroupChatScreen(
                    group = groupSnapshot,
                    messages = messages,
                    reactions = groupReactions,
                    selfUuid = selfUuid,
                    onBack = { screen = Screen.Main },
                    onSend = { body -> controller.sendGroupText(current.groupId, body) },
                    onReact = { message, emoji ->
                        controller.reactToGroupMessage(current.groupId, message.id, emoji)
                    },
                    onLeave = {
                        screen = Screen.Main
                        controller.leaveGroup(current.groupId)
                    },
                    onVisible = { controller.onGroupChatVisible(current.groupId) },
                    onHidden = { controller.onGroupChatHidden(current.groupId) },
                )
            }
        }
    }

    infoPeer?.let { peerRec ->
        val info by controller.container.peerRepository
            .observeInfo(peerRec.uuid)
            .collectAsState(initial = null)
        info?.let { peerInfo ->
            PeerInfoDialog(info = peerInfo, onDismiss = { infoPeer = null })
        }
    }

    // Incoming-call overlay. Active whenever an incoming RINGING call exists
    // and we're not already on the InCall screen for it (which only happens
    // after the user accepts and the row transitions to CONNECTING).
    val activeCall by controller.container.callRepository.activeCall
        .collectAsState(initial = null)
    val incoming =
        activeCall?.takeIf {
            it.direction == Call.Direction.INCOMING && it.state == Call.State.RINGING
        }
    incoming?.let { call ->
        IncomingCallDialog(
            call = call,
            onAccept = {
                controller.acceptCall(call.id)
                screen = Screen.InCall(call.id)
            },
            onDecline = { controller.hangUp(call.id) },
        )
    }

    if (showCreateGroup) {
        val candidates by controller.container.peerRepository
            .observeAll()
            .collectAsState(initial = emptyList())
        CreateGroupDialog(
            candidates = candidates.filter { it.uuid != selfUuid },
            onDismiss = { showCreateGroup = false },
            onCreate = { name, kind, members ->
                showCreateGroup = false
                controller.createGroup(name = name, kind = kind, memberUuids = members) { newId ->
                    screen = Screen.GroupChat(newId)
                }
            },
        )
    }
}

@Composable
private fun ContactsTab(
    controller: AppController,
    nickname: String,
    onPeerClick: (PeerRecord) -> Unit,
    onAddContact: (PeerRecord) -> Unit,
    onRemoveContact: (PeerRecord) -> Unit,
    onInfo: (PeerRecord) -> Unit,
) {
    val boundPort by controller.boundPort.collectAsState()
    val peers by controller.container.peerRepository
        .observeAll()
        .collectAsState(initial = emptyList())
    PeersScreen(
        peers = peers,
        selfNickname = nickname,
        selfPort = boundPort,
        onPeerClick = onPeerClick,
        onAddContact = onAddContact,
        onRemoveContact = onRemoveContact,
        onPeerInfo = onInfo,
    )
}

@Composable
private fun GroupsTab(
    controller: AppController,
    onGroupClick: (com.ospchat.shared.data.groups.GroupRecord) -> Unit,
    onNewGroup: () -> Unit,
) {
    val groups by controller.container.groupRepository
        .observeAll()
        .collectAsState(initial = emptyList())
    GroupsScreen(
        groups = groups,
        onGroupClick = onGroupClick,
        onNewGroup = onNewGroup,
        // No screen pop here (unlike the GroupChat onLeave above) — the user is
        // on the groups tab, so the row simply disappears when observeAll emits.
        onLeaveGroup = { group -> controller.leaveGroup(group.id) },
    )
}

@Composable
private fun AboutTab(
    controller: AppController,
    nickname: String,
    selfUuid: String,
    onRenameNickname: (String) -> Unit,
    onExit: () -> Unit,
) {
    val boundPort by controller.boundPort.collectAsState()
    val avatarHash by controller.container.identityRepository
        .avatarHashFlow
        .collectAsState(initial = null)
    val selfAvatarPath =
        avatarHash?.let { hash ->
            controller.container.avatarStore.selfPath(hash).takeIf {
                controller.container.avatarStore.selfExists(hash)
            }
        }
    AboutScreen(
        nickname = nickname,
        selfUuid = selfUuid,
        selfAvatarPath = selfAvatarPath,
        boundPort = boundPort,
        onRenameNickname = onRenameNickname,
        onPickAvatar = { bytes -> controller.setSelfAvatar(bytes) },
        onClearAvatar = { controller.clearSelfAvatar() },
        onExit = onExit,
    )
}
