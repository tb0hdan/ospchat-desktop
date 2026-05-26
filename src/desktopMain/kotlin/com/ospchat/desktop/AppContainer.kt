package com.ospchat.desktop

import com.ospchat.desktop.attachments.ExifAwareImageCompressor
import com.ospchat.desktop.media.JvmAudioCallSessionFactory
import com.ospchat.desktop.notifications.DesktopCallRinger
import com.ospchat.desktop.notifications.DesktopMessageNotifier
import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.attachments.FileAttachmentStore
import com.ospchat.shared.data.attachments.ImageBounds
import com.ospchat.shared.data.attachments.ImageCompressor
import com.ospchat.shared.data.attachments.ImageIoImageBounds
import com.ospchat.shared.data.avatar.AvatarStore
import com.ospchat.shared.data.avatar.FileAvatarStore
import com.ospchat.shared.data.calls.CallRepository
import com.ospchat.shared.data.db.OspChatDatabase
import com.ospchat.shared.data.db.ospChatDatabase
import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.JmDnsPeerDiscovery
import com.ospchat.shared.data.discovery.PeerDiscoveryService
import com.ospchat.shared.data.groups.GroupMessageRepository
import com.ospchat.shared.data.groups.GroupRepository
import com.ospchat.shared.data.groups.GroupSyncer
import com.ospchat.shared.data.identity.IdentityRepository
import com.ospchat.shared.data.identity.createIdentityDataStore
import com.ospchat.shared.data.messages.MessageRepository
import com.ospchat.shared.data.peers.GossipedPeerStore
import com.ospchat.shared.data.peers.PeerAvatarSync
import com.ospchat.shared.data.peers.PeerHistoryRecorder
import com.ospchat.shared.data.peers.PeerInfoNotifier
import com.ospchat.shared.data.peers.PeerRepository
import com.ospchat.shared.data.peers.PeerRouter
import com.ospchat.shared.data.peers.RelayBridgeRegistry
import com.ospchat.shared.turn.OspChatTurnServer
import com.ospchat.shared.data.reactions.ReactionRepository
import com.ospchat.shared.domain.groups.GroupBroadcaster
import com.ospchat.shared.domain.groups.LeaveGroupUseCase
import com.ospchat.shared.net.client.MessageClient
import com.ospchat.shared.net.server.MessageServer
import com.ospchat.shared.notifications.ActiveChatTracker
import com.ospchat.shared.platform.dataDir
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Hand-wired dependency graph. One instance lives for the lifetime of the
 * desktop app. All shared classes use constructor injection (no annotations
 * in commonMain), so each binding is a straight call into the appropriate
 * factory.
 *
 * Notification surface is [DesktopMessageNotifier]; its [sender] callback is
 * wired by `Main` once the Compose `TrayState` is available.
 */
class AppContainer {
    // --- Platform infra -----------------------------------------------------

    val http: HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }

    val database: OspChatDatabase by lazy { ospChatDatabase() }

    val identityDataStore = createIdentityDataStore()

    // --- Stores -------------------------------------------------------------

    val attachmentStore: AttachmentStore = FileAttachmentStore(parentDir = dataDir())
    val avatarStore: AvatarStore = FileAvatarStore(parentDir = dataDir())
    val imageCompressor: ImageCompressor = ExifAwareImageCompressor()
    val imageBounds: ImageBounds = ImageIoImageBounds()

    // --- Notifications ------------------------------------------------------

    val activeChatTracker = ActiveChatTracker()
    val messageNotifier = DesktopMessageNotifier(activeChatTracker)
    val callRinger = DesktopCallRinger()

    // --- Media (WebRTC) -----------------------------------------------------

    val audioCallSessionFactory = JvmAudioCallSessionFactory()

    // --- Discovery ----------------------------------------------------------

    val peerDiscovery: PeerDiscoveryService = JmDnsPeerDiscovery()
    val discoveryRepository = DiscoveryRepository(peerDiscovery)

    // --- Phase 4 multi-network bridging -------------------------------------
    //
    // GossipedPeerStore caches peers learned via /v1/info gossip from
    // bridges. RelayBridgeRegistry remembers which directly-discovered
    // peers advertised relayEnabled=true. PeerRouter combines them with
    // the live discovery snapshot to resolve a target UUID to either a
    // direct send (peer in discovery) or a bridged send (POST to a
    // relay-enabled bridge with toUuid set to the final recipient).

    val gossipedPeerStore = GossipedPeerStore()
    val relayBridgeRegistry = RelayBridgeRegistry()

    // --- Phase 3 multi-network bridging -------------------------------------
    //
    // Embedded TURN server for voice-call ICE relay. Runs only when the user
    // has set relayEnabled=true (the existing phase-4 flag gates both message
    // relay and voice relay). AppController owns the start/stop lifecycle.
    // PR 2 wires the TurnCredentialService surface into /v1/call/relay-cred
    // and CallRepository.fetchRelayIceServers.

    val turnServer = OspChatTurnServer()
    val peerRouter by lazy {
        PeerRouter(
            discoveryRepository = discoveryRepository,
            gossipedPeerStore = gossipedPeerStore,
            relayBridgeRegistry = relayBridgeRegistry,
        )
    }

    // --- Identity -----------------------------------------------------------

    val identityRepository = IdentityRepository(identityDataStore)

    // --- Network client -----------------------------------------------------

    val messageClient =
        MessageClient(
            http = http,
            discoveryRepository = discoveryRepository,
            // Phase 2b: every outbound DTO gets signed as soon as the
            // keypair is loaded by AppController.start. The lambda hits
            // IdentityRepository's cache on every call (cheap volatile
            // read); returns null until the first ensureSigningKeyPair
            // completes, at which point all subsequent sends are signed.
            signingKeyProvider = { identityRepository.signingKeyPairOrNull() },
        )

    // --- Repositories -------------------------------------------------------

    val peerHistoryRecorder by lazy { PeerHistoryRecorder(database.peerHistoryDao()) }

    val peerRepository by lazy {
        PeerRepository(
            peerDao = database.peerDao(),
            messageDao = database.messageDao(),
            historyDao = database.peerHistoryDao(),
            historyRecorder = peerHistoryRecorder,
            discoveryRepository = discoveryRepository,
            // Phase 4: lets toRecord compute "via <bridge-nickname>" and
            // mark peers offline when the bridge route disappears.
            peerRouter = peerRouter,
            gossipedPeerStore = gossipedPeerStore,
            relayBridgeRegistry = relayBridgeRegistry,
        )
    }

    val reactionRepository by lazy {
        ReactionRepository(
            dao = database.reactionDao(),
            client = messageClient,
            identityRepository = identityRepository,
            groupDao = database.groupDao(),
            peerDao = database.peerDao(),
            discoveryRepository = discoveryRepository,
        )
    }

    val messageRepository by lazy {
        MessageRepository(
            messageDao = database.messageDao(),
            peerDao = database.peerDao(),
            client = messageClient,
            identityRepository = identityRepository,
            notifier = messageNotifier,
            attachmentStore = attachmentStore,
            attachmentCompressor = imageCompressor,
            attachmentBounds = imageBounds,
            // Phase 4: PeerRouter enables sendToUuid(targetUuid, ...) to
            // pick a bridge for cross-LAN sends. GossipedPeerStore lets
            // receive() auto-create a PeerEntity for gossip-only senders.
            peerRouter = peerRouter,
            gossipedPeerStore = gossipedPeerStore,
        )
    }

    val groupRepository by lazy {
        GroupRepository(
            groupDao = database.groupDao(),
            groupMessageDao = database.groupMessageDao(),
            peerDao = database.peerDao(),
            identityRepository = identityRepository,
        )
    }

    val groupMessageRepository by lazy {
        GroupMessageRepository(
            groupDao = database.groupDao(),
            groupMessageDao = database.groupMessageDao(),
            peerDao = database.peerDao(),
            client = messageClient,
            identityRepository = identityRepository,
            discoveryRepository = discoveryRepository,
            groupRepository = groupRepository,
            notifier = messageNotifier,
        )
    }

    val groupSyncer by lazy {
        GroupSyncer(
            groupDao = database.groupDao(),
            groupMessageDao = database.groupMessageDao(),
            groupRepository = groupRepository,
            client = messageClient,
            identityRepository = identityRepository,
            reactionRepository = reactionRepository,
        )
    }

    val groupBroadcaster by lazy {
        GroupBroadcaster(
            groupDao = database.groupDao(),
            groupRepository = groupRepository,
            peerDao = database.peerDao(),
            discoveryRepository = discoveryRepository,
            client = messageClient,
        )
    }

    val leaveGroupUseCase by lazy {
        LeaveGroupUseCase(
            groupRepository = groupRepository,
            groupDao = database.groupDao(),
            groupBroadcaster = groupBroadcaster,
        )
    }

    val peerAvatarSync by lazy {
        PeerAvatarSync(
            client = messageClient,
            peerDao = database.peerDao(),
            avatarStore = avatarStore,
            avatarBounds = imageBounds,
            // Phase 4: feed every /v1/info response into the gossip cache
            // and relay-bridge registry. Without these, PeerRouter has
            // nothing to route through.
            gossipedPeerStore = gossipedPeerStore,
            relayBridgeRegistry = relayBridgeRegistry,
            // Phase 4 defence: filter self.uuid out of every inbound
            // gossip list before it enters GossipedPeerStore.
            identityRepository = identityRepository,
        )
    }

    val peerInfoNotifier by lazy {
        PeerInfoNotifier(
            client = messageClient,
            discoveryRepository = discoveryRepository,
        )
    }

    val callRepository by lazy {
        CallRepository(
            dao = database.callDao(),
            client = messageClient,
            identityRepository = identityRepository,
            discoveryRepository = discoveryRepository,
            sessionFactory = audioCallSessionFactory,
            notifier = callRinger,
            peerDao = database.peerDao(),
            // Phase 3 multi-network bridging: speculative TURN cred prefetch
            // from a relay-capable bridge before sessionFactory.create.
            relayBridgeRegistry = relayBridgeRegistry,
            // Phase 5 multi-network bridging: outbound call signaling DTOs
            // route via PeerRouter — direct when target is in discovery,
            // bridged with `toUuid` set when target is only in gossip.
            peerRouter = peerRouter,
        )
    }

    // --- Embedded HTTP server -----------------------------------------------

    val messageServer by lazy {
        MessageServer(
            discoveryRepository = discoveryRepository,
            messageRepository = messageRepository,
            messageDao = database.messageDao(),
            attachmentStore = attachmentStore,
            identityRepository = identityRepository,
            reactionRepository = reactionRepository,
            avatarStore = avatarStore,
            peerAvatarSync = peerAvatarSync,
            groupMessageRepository = groupMessageRepository,
            groupRepository = groupRepository,
            groupSyncer = groupSyncer,
            callRepository = callRepository,
            // Phase 4: forwarded to installMessageRoutes. messageClient
            // becomes the relay forwarder when toUuid != self; the gossip
            // store backs signer-pubkey lookup for relayed-in messages.
            messageClient = messageClient,
            gossipedPeerStore = gossipedPeerStore,
            // Phase 4: lets /v1/info gossip carry each peer's avatarHash
            // and lets /v1/peer-avatar/{uuid} serve the cached bytes for
            // phantom-peer consumers across an unrouted LAN.
            peerDao = database.peerDao(),
            // Phase 3: backs POST /v1/call/relay-cred when this node is a
            // bridge. Returns 503 relay_unavailable while the TURN server
            // hasn't bound (i.e. relayEnabled=false at boot).
            turnCredentialService = turnServer,
        )
    }

    fun shutdown() {
        runCatching { turnServer.stop() }
        runCatching { messageServer.stop() }
        runCatching { peerDiscovery.stop() }
        runCatching { audioCallSessionFactory.shutdown() }
        runCatching { http.close() }
        runCatching { database.close() }
    }
}
