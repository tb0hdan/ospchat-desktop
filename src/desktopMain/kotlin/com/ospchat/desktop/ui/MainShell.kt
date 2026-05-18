package com.ospchat.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tabbed shell — vertical NavigationRail on the left, content area on the right.
 * Three tabs match Android's bottom-tab shell: Contacts / Groups / About.
 *
 * [tab] is hoisted so the parent ([AppRoot]) can deep-link a tab from a
 * notification or menu action.
 */
@Composable
fun MainShell(
    selected: Tab,
    onTabChange: (Tab) -> Unit,
    content: @Composable (Tab) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail {
            Tab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = tab == selected,
                    onClick = { onTabChange(tab) },
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
        Box(modifier = Modifier.fillMaxSize().padding(start = 0.dp)) {
            content(selected)
        }
    }
}

/** Convenience overload that owns the tab selection state. */
@Composable
fun MainShell(content: @Composable (Tab) -> Unit) {
    var selected by remember { mutableStateOf(Tab.Contacts) }
    MainShell(selected = selected, onTabChange = { selected = it }, content = content)
}
