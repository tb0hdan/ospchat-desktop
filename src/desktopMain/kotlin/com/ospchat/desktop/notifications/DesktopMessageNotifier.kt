package com.ospchat.desktop.notifications

import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.groups.GroupEntity
import com.ospchat.shared.data.groups.GroupMessage
import com.ospchat.shared.data.messages.Message
import com.ospchat.shared.notifications.ActiveChatTracker
import com.ospchat.shared.notifications.MessageNotifier

/**
 * Desktop [MessageNotifier]. The actual delivery surface (tray notification,
 * etc.) is plugged in later via [sender] — the notifier exists at container
 * construction time, but the Compose tray state isn't available until the
 * window has been composed, so [Main] wires it in once and we hold the
 * callback here.
 *
 * Suppression matches the Android impl: if the user is currently looking at
 * the originating chat (tracked by [ActiveChatTracker]), the notification is
 * dropped. No DND check on desktop — there's no portable Linux/Mac/Win API.
 *
 * When [sender] is `null` (e.g. on a desktop session without tray support, or
 * before Main has wired one in), notifications are silently dropped — the
 * message itself is already persisted by the time the notifier is invoked.
 */
class DesktopMessageNotifier(
    private val activeChatTracker: ActiveChatTracker,
) : MessageNotifier {
    @Volatile
    var sender: ((title: String, body: String) -> Unit)? = null

    override fun notifyIncoming(
        fromPeer: Peer,
        message: Message,
    ) {
        if (activeChatTracker.activePeerUuid == fromPeer.uuid) return
        val body = message.body.ifBlank { "[image]" }
        sender?.invoke(fromPeer.nickname, body)
    }

    override fun notifyIncomingGroup(
        group: GroupEntity,
        message: GroupMessage,
    ) {
        if (activeChatTracker.activeGroupId == group.id) return
        val body = message.body.ifBlank { "[image]" }
        sender?.invoke(group.name, "${message.fromNickname}: $body")
    }
}
