package com.ospchat.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NicknameScreen(
    initial: String,
    onSubmit: (String) -> Unit,
) {
    var nickname by remember(initial) { mutableStateOf(initial) }
    val trimmed = nickname.trim()

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pick a nickname",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Other devices on this Wi-Fi will see this name.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("Nickname") },
            singleLine = true,
            modifier = Modifier.widthIn(min = 280.dp),
        )
        Button(
            onClick = { onSubmit(trimmed) },
            enabled = trimmed.isNotEmpty(),
        ) { Text("Continue") }
    }
}
