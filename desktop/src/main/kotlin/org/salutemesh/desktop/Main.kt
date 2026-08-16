package org.salutemesh.desktop

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SALUTE Mesh — Command Center",
        state = rememberWindowState(width = 980.dp, height = 860.dp),
    ) {
        MaterialTheme {
            CommandCenterApp()
        }
    }
}
