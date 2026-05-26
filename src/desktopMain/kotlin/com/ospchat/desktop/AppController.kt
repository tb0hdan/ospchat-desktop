package com.ospchat.desktop

import com.ospchat.shared.data.calls.Call
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.util.Base64Util
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
            // Phase 2a multi-network bridging: per-install Ed25519 keypair,
            // pubkey is broadcast via mDNS TXT (pk=) and served from
            // GET /v1/info for TOFU pinning. ensureSigningKeyPair persists
            // the seed on first use and returns the same key on every call.
            val publicKeyB64 =
                runCatching {
                    Base64Util.encode(
                        container.identityRepository.ensureSigningKeyPair().publicKeyBytes(),
                    )
                }.getOrElse {
                    Log.w(TAG, "ensureSigningKeyPair failed; starting without pubkey", it)
                    null
                }
            val preferredPort = container.identityRepository.lastServerPort() ?: 0
            // Phase 4 multi-network bridging: read the user's relay opt-in
            // and pass it into MessageServer.start. Flipping the toggle in
            // About requires a restart to take effect.
            val relayEnabled =
                runCatching { container.identityRepository.currentRelayEnabled() }
                    .getOrElse {
                        Log.w(TAG, "currentRelayEnabled failed; defaulting to false", it)
                        false
                    }
            val port =
                runCatching {
                    container.messageServer.start(
                        uuid = uuid,
                        nickname = nickname,
                        preferredPort = preferredPort,
                        publicKeyB64 = publicKeyB64,
                        relayEnabled = relayEnabled,
                    )
                }.getOrElse {
                    Log.e(TAG, "MessageServer.start failed", it)
                    return@launch
                }
            _boundPort.value = port
            runCatching { container.identityRepository.setLastServerPort(port) }
                .onFailure { Log.w(TAG, "setLastServerPort($port) failed", it) }
            // Phase 3 multi-network bridging — start the embedded TURN
            // server when the user has opted into relay. Bound on every
            // non-loopback IPv4 interface (the server enumerates them
            // itself, matching JmDnsPeerDiscovery.pickLocalAddresses).
            // Flipping the toggle takes effect on next restart, same as
            // the phase-4 MessageServer.start path.
            if (relayEnabled) {
                runCatching { container.turnServer.start() }
                    .onFailure { Log.w(TAG, "TURN server start failed", it) }
            }
            // Phase 2b multi-network bridging: warm the discovery
            // service's TOFU pubkey pin map from the persistent peer
            // table BEFORE start(). This is what makes the F9 hijack
            // defence survive a process restart — an attacker that
            // wins the post-restart mDNS race with a different pubkey
            // is rejected because we recognise the legitimate peer's
            // pubkey from disk.
            val persistedPins =
                runCatching {
                    container.database
                        .peerDao()
                        .loadPinnedPubkeys()
                        .associate { it.uuid to it.pubKey }
                }.getOrElse {
                    Log.w(TAG, "loadPinnedPubkeys failed; starting without persistent pins", it)
                    emptyMap()
                }
            container.peerDiscovery.preloadPinnedPubkeys(persistedPins)

            // Phase 4 multi-network bridging cleanup: scrub any
            // self-row that might have been written by an earlier
            // gossip-collector bug (before the self-filter below was
            // in place). Safe no-op if no such row exists.
            runCatching {
                val existed = container.database.peerDao().findByUuid(uuid) != null
                container.database.peerDao().deleteByUuid(uuid)
                Log.d(TAG, "self-row cleanup: existed=$existed deleted=true uuid=$uuid")
            }.onFailure { Log.w(TAG, "deleteByUuid(self=$uuid) cleanup failed", it) }
            runCatching {
                container.peerDiscovery.start(
                    nickname = nickname,
                    uuid = uuid,
                    port = port,
                    publicKeyB64 = publicKeyB64,
                )
            }.onFailure { Log.e(TAG, "PeerDiscovery.start failed", it) }
            _running.value = true
            Log.d(
                TAG,
                "started: uuid=$uuid nickname=$nickname port=$port (preferred=$preferredPort) " +
                    "pk=${publicKeyB64?.take(16) ?: "<none>"} persistedPins=${persistedPins.size} " +
                    "relayEnabled=$relayEnabled",
            )

            // Phase 4 multi-network bridging — persist gossiped peers as
            // phantom PeerEntity rows so they show up in the contacts UI
            // alongside directly-discovered peers. The phantom row has
            // `lastHost = ""` (the gossip sentinel); outbound code (see
            // `sendText`) detects this and routes through PeerRouter
            // instead of attempting a direct connection.
            //
            // Gate on PeerRouter — gossip can reach us from a bridge whose
            // `relayEnabled = false` (or that's offline), in which case
            // we have no way to actually message the gossiped peer.
            // Recording a phantom row in that state would surface a
            // contact the user can never reach. Skip until a viable route
            // appears; re-evaluation happens whenever gossip / discovery /
            // the relay registry emits.
            launch {
                kotlinx.coroutines.flow
                    .combine(
                        container.gossipedPeerStore.peers,
                        container.relayBridgeRegistry.bridges,
                        container.discoveryRepository.peerSnapshot,
                    ) { gossiped, _, _ -> gossiped }
                    .collect { gossiped ->
                        gossiped.values.forEach { gp ->
                            // Defensive self-filter: never record a phantom
                            // row for the local user's own UUID. The
                            // server-side gossip filter in /v1/info should
                            // already exclude us, but if a bridge bugs out
                            // we don't want our own row leaking back into
                            // the contacts list as "via bridge".
                            if (gp.uuid == uuid) return@forEach
                            val hasRoute = container.peerRouter.routeTo(gp.uuid)?.toUuid != null
                            if (!hasRoute) return@forEach
                            val phantom =
                                com.ospchat.shared.data.discovery.Peer(
                                    uuid = gp.uuid,
                                    nickname = gp.nickname,
                                    candidates =
                                        listOf(
                                            com.ospchat.shared.data.discovery.Endpoint(
                                                host = "",
                                                port = 0,
                                            ),
                                        ),
                                    publicKey = gp.publicKey,
                                )
                            runCatching { container.peerRepository.recordSeen(phantom) }
                                .onFailure { Log.w(TAG, "recordSeen(phantom ${gp.uuid}) failed", it) }
                        }
                    }
            }

            // Phase 4 multi-network bridging — periodic liveness probe.
            // Android NSD (and JmDNS to a lesser extent) hangs onto stale
            // mDNS records for hours when a peer goes down without
            // sending an explicit goodbye, so peerSnapshot membership
            // alone is not a reliable signal of bridge reachability.
            // Every 30 s we re-pull /v1/info from each directly-discovered
            // peer; PeerAvatarSync.sync drops the peer from
            // RelayBridgeRegistry on failure, which collapses gossiped-
            // via-that-bridge peers to offline in the UI.
            launch {
                while (true) {
                    kotlinx.coroutines.delay(LIVENESS_PROBE_MS)
                    container.discoveryRepository.peerSnapshot.value.values.forEach { peer ->
                        launch { container.peerAvatarSync.sync(peer) }
                    }
                }
            }

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
     * the message flow. Phase 4 multi-network bridging — when [peer]'s host
     * is the gossip phantom sentinel (i.e. the target isn't directly
     * discoverable), routes the send through the gossip-vouching relay
     * bridge via [MessageRepository.sendToUuid].
     */
    fun sendText(
        peer: Peer,
        body: String,
    ) {
        if (body.isBlank()) return
        scope.launch {
            if (peer.host.isEmpty()) {
                container.messageRepository
                    .sendToUuid(targetUuid = peer.uuid, body = body)
                    .onFailure { Log.w(TAG, "sendToUuid(${peer.uuid}) failed", it) }
            } else {
                container.messageRepository.send(peer = peer, body = body)
            }
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
     * Phase 4 multi-network bridging — observe the user's relay opt-in
     * flag. The About UI shows this as a toggle so the desktop user can
     * choose to bridge messages between unrouted networks.
     */
    val relayEnabledFlow: kotlinx.coroutines.flow.Flow<Boolean> =
        container.identityRepository.relayEnabledFlow

    /**
     * Phase 4 — flip the relay opt-in. Takes effect on next process
     * restart (the route handler reads the flag at server start).
     */
    fun setRelayEnabled(enabled: Boolean) {
        scope.launch {
            runCatching { container.identityRepository.setRelayEnabled(enabled) }
                .onFailure { Log.w(TAG, "setRelayEnabled($enabled) failed", it) }
        }
    }

    /**
     * Persist [bytes] as the local user's custom avatar. Compresses the
     * picked image to [AVATAR_MAX_EDGE] pixels on the longest edge first
     * — without this step the source resolution leaks through to other
     * peers, which then reject the download because it exceeds their
     * `ImageBounds.AVATAR_MAX_EDGE` cap. After compression: hash, write
     * via [AvatarStore.writeSelf], update IdentityRepository.avatarHash,
     * cleanup any prior self-avatar files, then notify peers via
     * /v1/notify-refresh.
     */
    fun setSelfAvatar(bytes: ByteArray) {
        scope.launch {
            runCatching {
                val compressed = container.imageCompressor.compress(bytes, maxEdge = AVATAR_MAX_EDGE)
                val hash = sha256Hex(compressed.bytes)
                container.avatarStore.writeSelf(bytes = compressed.bytes, hash = hash)
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

        /**
         * Max edge in pixels for the local user's avatar after upload-time
         * compression. Matches the Android consumer's `AvatarRepository`
         * value so both clients produce comparably-sized JPEGs, and sits
         * well under `ImageBounds.AVATAR_MAX_EDGE = 1024` (the receiver's
         * upper-bound cap) so peer-side validation never rejects.
         */
        const val AVATAR_MAX_EDGE = 256

        /**
         * Phase 4 multi-network bridging — interval between periodic
         * liveness probes. 30 s strikes a balance between "fast enough
         * for the UI to feel responsive when a bridge drops" and
         * "rare enough that we don't flood the network with /v1/info
         * fetches." Tunable.
         */
        const val LIVENESS_PROBE_MS = 30_000L

        /** Upper bound on how long we let the backend finish cleaning up. */
        const val SHUTDOWN_DEADLINE_MS = 800L
    }
}
