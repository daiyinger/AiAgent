package com.example.aiagent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.aiagent.ui.ChatPanel
import com.example.aiagent.ui.SettingsPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab("", focusOnClickInside = true) {
            LaunchedEffect(Unit) {
                // initial data loading
            }

            MyToolWindowContent()
        }
    }
}

@Composable
private fun MyToolWindowContent() {
    Column(Modifier.fillMaxSize()) {
        ChatPanel()
    }
}