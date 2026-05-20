package com.ospchat.desktop

import com.ospchat.shared.data.calls.Call
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * App-level lifecycle owner. Starts the embedded server, advertises via mDNS,
 * and persists newly-seen peers into Room via [PeerRepository.recordSeen].
 *
 * Boot order:
 *   1. resolve / generate UUID
 *   2. wait for a nickname (set by the UI)
 *   3. start the HTTP server on an ephemeral port
 *   4. advertise (nickname, uuid, port) via mDNS
 *
 * [running] flips to true once the discovery service is advertising.
 */
class AppController(
    val container: AppContainer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    /**
     * Starts the server + discovery once [nickname] is non-blank. Safe to
     * call repeatedly — only the first invocation does work.
     */
    fun start(nickname: String) {
        if (_running.value || nickname.isBlank()) return
        scope.launch {
            val uuid = container.identityRepository.ensureUuid()
            val preferredPort = container.identityRepository.lastServerPort() ?: 0
            val port =
                runCatching {
                    container.messageServer.start(
                        uuid = uuid,
                        nickname = nickname,
                        preferredPort = preferredPort,
                    )
                }.getOrElse {
                    Log.e(TAG, "MessageServer.start failed", it)
                    return@launch
                }
            _boundPort.value = port
            runCatching { container.identityRepository.setLastServerPort(port) }
                .onFailure { Log.w(TAG, "setLastServerPort($port) failed", it) }
            runCatching { container.peerDiscovery.start(nickname = nickname, uuid = uuid, port = port) }
                .onFailure { Log.e(TAG, "PeerDiscovery.start failed", it) }
            _running.value = true
            Log.d(TAG, "started: uuid=$uuid nickname=$nickname port=$port (preferred=$preferredPort)")

            // Persist every newly-seen peer (and their address/nickname history).
            container.discoveryRepository.peerSnapshot.collect { snapshot ->
                snapshot.values.forEach { peer ->
                    runCatching { container.peerRepository.recordSeen(peer) }
                        .onFailure { Log.w(TAG, "recordSeen(${peer.uuid}) failed", it) }
                }
            }
        }
    }

    /**
     * Send a text message to [peer]. Fire-and-forget; UI observes status via
     * the message flow.
     */
    fun sendText(
        peer: Peer,
        body: String,
    ) {
        if (body.isBlank()) return
        scope.launch {
            container.messageRepository.send(peer = peer, body = body)
        }
    }

    /** Post [body] to the group identified by [groupId]. Fire-and-forget. */
    fun sendGroupText(
        groupId: String,
        body: String,
    ) {
        if (body.isBlank()) return
        scope.launch {
            runCatching { container.groupMessageRepository.send(groupId = groupId, body = body) }
                .onFailure { Log.w(TAG, "groupMessageRepository.send failed", it) }
        }
    }

    /**
     * Mark the chat with [peerUuid] as on-screen so the notifier suppresses
     * incoming pings for that conversation. Pair with [onPeerChatHidden].
     */
    fun onPeerChatVisible(peerUuid: String) {
        container.activeChatTracker.activePeerUuid = peerUuid
    }

    fun onPeerChatHidden(peerUuid: String) {
        if (container.activeChatTracker.activePeerUuid == peerUuid) {
            container.activeChatTracker.activePeerUuid = null
        }
    }

    fun onGroupChatVisible(groupId: String) {
        container.activeChatTracker.activeGroupId = groupId
    }

    fun onGroupChatHidden(groupId: String) {
        if (container.activeChatTracker.activeGroupId == groupId) {
            container.activeChatTracker.activeGroupId = null
        }
    }

    /** Mark all unread group messages as read up to now. */
    fun markGroupRead(groupId: String) {
        scope.launch {
            runCatching { container.groupRepository.markRead(groupId) }
        }
    }

    fun addToContacts(peerUuid: String) {
        scope.launch {
            runCatching { container.peerRepository.setIsContact(peerUuid, true) }
                .onFailure { Log.w(TAG, "addToContacts($peerUuid) failed", it) }
        }
    }

    fun removeFromContacts(peerUuid: String) {
        scope.launch {
            runCatching { container.peerRepository.setIsContact(peerUuid, false) }
                .onFailure { Log.w(TAG, "removeFromContacts($peerUuid) failed", it) }
        }
    }

    suspend fun reactToMessage(
        peer: com.ospchat.shared.data.discovery.Peer,
        messageId: String,
        emoji: String?,
    ) {
        runCatching { container.reactionRepository.react(peer = peer, messageId = messageId, emoji = emoji) }
            .onFailure { Log.w(TAG, "reactToMessage failed", it) }
    }

    /**
     * React to a group message. Persists locally and fans out to every other
     * current member. Fire-and-forget; offline members catch up on next sync.
     */
    fun reactToGroupMessage(
        groupId: String,
        messageId: String,
        emoji: String?,
    ) {
        scope.launch {
            runCatching {
                container.reactionRepository.reactToGroup(
                    groupId = groupId,
                    messageId = messageId,
                    emoji = emoji,
                )
            }.onFailure { Log.w(TAG, "reactToGroupMessage failed", it) }
        }
    }

    fun createGroup(
        name: String,
        kind: com.ospchat.shared.data.groups.GroupKind,
        memberUuids: List<String>,
        onCreated: (String) -> Unit,
    ) {
        scope.launch {
            runCatching {
                val id = container.groupRepository.createGroup(name = name, kind = kind, memberUuids = memberUuids)
                container.groupBroadcaster.broadcastSnapshot(id)
                onCreated(id)
            }.onFailure { Log.w(TAG, "createGroup failed", it) }
        }
    }

    /**
     * Self-removal from [groupId]. Fire-and-forget: broadcasts the leave to
     * remaining members on a best-effort basis and purges the local copy
     * (group entity + messages). Callers should pop the chat screen
     * synchronously rather than waiting on this — otherwise the
     * `observeOne(groupId)` Flow emits null on `applyLocalLeave` and the
     * "Group no longer exists" fallback flashes for a frame.
     */
    fun leaveGroup(groupId: String) {
        scope.launch {
            runCatching { container.leaveGroupUseCase(groupId) }
                .onFailure { Log.w(TAG, "leaveGroup($groupId) failed", it) }
        }
    }

    fun sendImageAttachment(
        peer: com.ospchat.shared.data.discovery.Peer,
        body: String,
        bytes: ByteArray,
    ) {
        scope.launch {
            runCatching { container.messageRepository.send(peer = peer, body = body, attachmentBytes = bytes) }
                .onFailure { Log.w(TAG, "sendImageAttachment failed", it) }
        }
    }

    /** Marks all inbound messages from [peerUuid] as read at the current moment. */
    fun markPeerRead(peerUuid: String) {
        scope.launch {
            val nowMillis =
                kotlinx.datetime.Clock.System
                    .now()
                    .toEpochMilliseconds()
            container.peerRepository.markRead(peerUuid = peerUuid, readAt = nowMillis)
            // Best-effort read receipt
            container.discoveryRepository.findPeer(peerUuid)?.let { peer ->
                runCatching {
                    container.messageRepository.sendReadReceipt(toPeer = peer, upToSentAt = nowMillis)
                }.onFailure { Log.w(TAG, "sendReadReceipt failed", it) }
            }
        }
    }

    // ---- Voice calls -------------------------------------------------------

    /**
     * Place an outbound voice call to [peer]. Fire-and-forget — the UI
     * observes call state via `container.callRepository.activeCall` and
     * navigates to the in-call screen as soon as the row appears.
     * Invokes [onStarted] with the freshly-minted call id so the caller can
     * push the in-call screen synchronously.
     */
    fun startCall(
        peer: Peer,
        onStarted: (String) -> Unit = {},
    ) {
        scope.launch {
            runCatching {
                val callId = container.callRepository.startCall(peer)
                onStarted(callId)
            }.onFailure { Log.w(TAG, "startCall failed", it) }
        }
    }

    fun acceptCall(callId: String) {
        scope.launch {
            runCatching { container.callRepository.acceptCall(callId) }
                .onFailure { Log.w(TAG, "acceptCall failed", it) }
        }
    }

    fun hangUp(
        callId: String,
        reason: Call.EndReason = Call.EndReason.HANGUP,
    ) {
        scope.launch {
            runCatching { container.callRepository.hangUp(callId, reason) }
                .onFailure { Log.w(TAG, "hangUp failed", it) }
        }
    }

    fun setCallMuted(
        callId: String,
        muted: Boolean,
    ) {
        scope.launch {
            runCatching { container.callRepository.setMuted(callId, muted) }
                .onFailure { Log.w(TAG, "setCallMuted failed", it) }
        }
    }

    suspend fun ensureUuid(): String = container.identityRepository.ensureUuid()

    suspend fun currentNickname(): String? = container.identityRepository.nicknameFlow.first()

    suspend fun setNickname(nickname: String) {
        container.identityRepository.setNickname(nickname)
    }

    /**
     * Persist [bytes] as the local user's custom avatar: SHA-256 hash, write
     * the file via [AvatarStore.writeSelf], update IdentityRepository.avatarHash,
     * cleanup any prior self-avatar files, then notify peers via /v1/notify-refresh.
     */
    fun setSelfAvatar(bytes: ByteArray) {
        scope.launch {
            runCatching {
                val hash = sha256Hex(bytes)
                container.avatarStore.writeSelf(bytes = bytes, hash = hash)
                container.avatarStore.cleanupSelfExcept(hash)
                container.identityRepository.setAvatarHash(hash)
                container.peerInfoNotifier.broadcastRefresh()
            }.onFailure { Log.w(TAG, "setSelfAvatar failed", it) }
        }
    }

    fun clearSelfAvatar() {
        scope.launch {
            runCatching {
                container.avatarStore.cleanupSelfExcept(null)
                container.identityRepository.setAvatarHash(null)
                container.peerInfoNotifier.broadcastRefresh()
            }.onFailure { Log.w(TAG, "clearSelfAvatar failed", it) }
        }
    }

    suspend fun currentAvatarHash(): String? = container.identityRepository.currentAvatarHash()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Tear the backend down without making the user wait.
     *
     * JmDNS' `close()` blocks ~5s flushing mDNS goodbye packets, and its
     * SocketListener / Timer threads are non-daemon so they keep the JVM
     * alive even after Compose has dismissed the window. The user clicking
     * Exit shouldn't have to watch that play out — peers will time the
     * mDNS record out on their next discovery query regardless of whether
     * we sent goodbyes cleanly.
     *
     * Plan:
     *  1. Run the actual cleanup on a daemon thread (best-effort).
     *  2. Start a *non-daemon* killer thread that joins cleanup with a
     *     short deadline and then calls `exitProcess(0)`. Non-daemon so
     *     it keeps the JVM alive for the deadline if Compose tries to
     *     exit naturally; `exitProcess` then guarantees we're gone by
     *     then regardless of any straggling JmDNS threads.
     *
     * Net effect: perceived shutdown ≤ [SHUTDOWN_DEADLINE_MS] ms.
     */
    fun shutdown() {
        val cleanup =
            Thread(
                {
                    runCatching { container.shutdown() }
                        .onFailure { Log.w(TAG, "container.shutdown failure", it) }
                },
                "ospchat-shutdown",
            ).apply { isDaemon = true }
        cleanup.start()

        Thread(
            {
                cleanup.join(SHUTDOWN_DEADLINE_MS)
                kotlin.system.exitProcess(0)
            },
            "ospchat-shutdown-killer",
        ).start()
    }

    private companion object {
        const val TAG = "AppController"

        /** Upper bound on how long we let the backend finish cleaning up. */
        const val SHUTDOWN_DEADLINE_MS = 800L
    }
}
