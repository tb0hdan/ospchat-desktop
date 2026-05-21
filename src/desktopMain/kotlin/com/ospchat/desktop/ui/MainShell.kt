package com.ospchat.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Tabbed shell — vertical NavigationRail on the left, content area on the right.
 * Three tabs match Android's bottom-tab shell: Contacts / Groups / About.
 *
 * The rail is now persistent chrome — visible on every screen, including
 * Chat / GroupChat / InCall. The parent ([com.ospchat.desktop.MainKt]) wires
 * every rail click to set the tab + pop the active sub-screen back to
 * `Screen.Main`, so the rail doubles as a mid-Chat / mid-Call "return to the
 * shell" affordance. The CallStatusBar lives above this composable so it
 * keeps surfacing the active call once the user pops out of `Screen.InCall`.
 */
@Composable
fun MainShell(
    selectedTab: Tab,
    onTabClick: (Tab) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail {
            Tab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = tab == selectedTab,
                    onClick = { onTabClick(tab) },
                    icon = {
                        Icon(
                            imageVector =
                                when (tab) {
                                    Tab.Contacts -> Icons.Filled.Person
                                    Tab.Groups -> Icons.Filled.Group
                                    Tab.About -> Icons.Filled.Info
                                },
                            contentDescription = tab.label,
                        )
                    },
                    label = { Text(tab.label) },
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
