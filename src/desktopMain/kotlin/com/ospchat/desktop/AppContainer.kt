package com.ospchat.desktop

import com.ospchat.desktop.attachments.ExifAwareImageCompressor
import com.ospchat.desktop.media.JvmAudioCallSessionFactory
import com.ospchat.desktop.notifications.DesktopCallRinger
import com.ospchat.desktop.notifications.DesktopMessageNotifier
import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.attachments.FileAttachmentStore
import com.ospchat.shared.data.attachments.ImageCompressor
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
import com.ospchat.shared.data.peers.PeerAvatarSync
import com.ospchat.shared.data.peers.PeerHistoryRecorder
import com.ospchat.shared.data.peers.PeerInfoNotifier
import com.ospchat.shared.data.peers.PeerRepository
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

    // --- Notifications ------------------------------------------------------

    val activeChatTracker = ActiveChatTracker()
    val messageNotifier = DesktopMessageNotifier(activeChatTracker)
    val callRinger = DesktopCallRinger()

    // --- Media (WebRTC) -----------------------------------------------------

    val audioCallSessionFactory = JvmAudioCallSessionFactory()

    // --- Discovery ----------------------------------------------------------

    val peerDiscovery: PeerDiscoveryService = JmDnsPeerDiscovery()
    val discoveryRepository = DiscoveryRepository(peerDiscovery)

    // --- Identity -----------------------------------------------------------

    val identityRepository = IdentityRepository(identityDataStore)

    // --- Network client -----------------------------------------------------

    val messageClient = MessageClient(http = http, discoveryRepository = discoveryRepository)

    // --- Repositories -------------------------------------------------------

    val peerHistoryRecorder by lazy { PeerHistoryRecorder(database.peerHistoryDao()) }

    val peerRepository by lazy {
        PeerRepository(
            peerDao = database.peerDao(),
            messageDao = database.messageDao(),
            historyDao = database.peerHistoryDao(),
            historyRecorder = peerHistoryRecorder,
            discoveryRepository = discoveryRepository,
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
        )
    }

    fun shutdown() {
        runCatching { messageServer.stop() }
        runCatching { peerDiscovery.stop() }
        runCatching { audioCallSessionFactory.shutdown() }
        runCatching { http.close() }
        runCatching { database.close() }
    }
}
