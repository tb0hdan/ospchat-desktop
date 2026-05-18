package com.ospchat.desktop.ui

import com.ospchat.shared.data.discovery.Peer

sealed interface Screen {
    data object Nickname : Screen

    /** Tabbed shell hosting Contacts / Groups / About. */
    data object Main : Screen

    data class Chat(val peer: Peer) : Screen

    data class GroupChat(val groupId: String) : Screen
}

enum class Tab(val label: String) {
    Contacts("Contacts"),
    Groups("Groups"),
    About("About"),
}
