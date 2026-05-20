package com.ospchat.desktop.ui

import com.ospchat.shared.data.discovery.Peer

sealed interface Screen {
    data object Nickname : Screen

    /** Tabbed shell hosting Contacts / Groups / About. */
    data object Main : Screen

    data class Chat(
        val peer: Peer,
    ) : Screen

    data class GroupChat(
        val groupId: String,
    ) : Screen

    /**
     * In-call screen — covers both the outbound "Calling…" and the answered
     * "Connected, 0:32" phases of one call. Incoming-call presentation is
     * an overlay dialog rendered in `MainRoot`, not a screen variant, so the
     * user can still see what they were doing before deciding to accept.
     */
    data class InCall(
        val callId: String,
    ) : Screen
}

enum class Tab(
    val label: String,
) {
    Contacts("Contacts"),
    Groups("Groups"),
    About("About"),
}
