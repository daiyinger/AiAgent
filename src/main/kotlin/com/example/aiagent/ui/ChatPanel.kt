package com.example.aiagent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.aiagent.service.ChatStateService
import com.example.aiagent.service.ChatStateService.MessageState
import com.example.aiagent.service.ChatStateService.SessionState
import com.example.aiagent.service.LangChainAgentService
import com.example.aiagent.service.toLocalDateTime
import com.example.aiagent.service.toStateString
import com.example.aiagent.settings.AiAgentSettings
import com.example.aiagent.tools.ToolResult
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.example.aiagent.service.LogService

private fun log(message: String) {
    LogService.log(message)
}

@Composable
fun ChatPanel() {
    val settings = AiAgentSettings.instance.state
    val currentProject = remember { ProjectManager.getInstance().openProjects.firstOrNull() }
    val langChainService = remember { currentProject?.let { LangChainAgentService(it) } }
    val chatStateService = remember { ChatStateService.instance }
    
    val sessions = remember { mutableStateOf(chatStateService.sessions.toMutableList()) }
    var currentSessionIndex by remember { mutableStateOf(chatStateService.currentSessionIndex) }
    
    val currentSessionState = sessions.value.getOrElse(currentSessionIndex) { 
        sessions.value.firstOrNull() ?: SessionState(
            id = System.currentTimeMillis().toString(),
            title = "新会话"
        ).also { 
            sessions.value.add(it) 
        }
    }
    
    val messages = remember { 
        mutableStateOf(currentSessionState.messages.map { it.toChatMessage() }.toMutableList()) 
    }
    
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isSessionManagerOpen by remember { mutableStateOf(false) }
    var settingsVersion by remember { mutableStateOf(0) }
    
    // 文本选择颜色配置
    val textSelectionColors = TextSelectionColors(
        handleColor = Color(0xFF007ACC),
        backgroundColor = Color(0xFF007ACC).copy(alpha = 0.4f)
    )
    
    val listState = rememberLazyListState()
    
    // 滚动到最新消息
    LaunchedEffect(messages.value.size) {
        if (messages.value.isNotEmpty()) {
            listState.animateScrollToItem(messages.value.size - 1)
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部标题栏，带设置图标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D2D))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🤖",
                    style = JewelTheme.defaultTextStyle
                )
                Text(
                    text = "AI Agent",
                    style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, color = Color.White)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 历史会话按钮
                IconButton(
                    onClick = { isSessionManagerOpen = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        text = "☰",
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 16.sp)
                    )
                }
                
                // 设置按钮
                IconButton(
                    onClick = { isSettingsOpen = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        text = "⚙",
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 16.sp)
                    )
                }
            }
        }
        
        // 聊天区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(Color(0xFF1E1E1E))
                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (messages.value.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "开始与AI对话...",
                        style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                    )
                }
            } else {
                LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages.value) {
                    when (it) {
                        is UserMessage -> UserMessageItem(it)
                        is AiMessage -> AiMessageItem(it)
                        is ToolCallMessage -> ToolCallMessageItem(it)
                        is TokenUsageMessage -> TokenUsageMessageItem(it)
                    }
                }
            }
            }
        }
        
        // 输入区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                .background(Color(0xFF2D2D2D))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // 输入框
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = false,
                    maxLines = 3,
                    textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Column {
                                Text(
                                    text = "Ask AI, use @prompt to recall saved prompts",
                                    style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                )
                                Text(
                                    text = "AI can read files, edit files, build project, etc.",
                                    style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray, fontSize = 12.sp)
                                )
                            }
                        }
                        innerTextField()
                    }
                )
                
                // 底部栏：模型选择和发送按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 服务选择
                    // 模型选择
                    ModelSelector(settingsVersion)
                    
                    // 发送/停止按钮
                    OutlinedButton(
                        onClick = {
                            if (isSending) {
                                isSending = false
                                log("用户停止发送消息")
                            } else if (inputText.trim().isNotEmpty()) {
                                val userMessage = UserMessage(
                                    id = System.currentTimeMillis().toString(),
                                    content = inputText.trim(),
                                    timestamp = LocalDateTime.now()
                                )
                                
                                val updatedMessages = messages.value.toMutableList()
                                updatedMessages.add(userMessage)
                                messages.value = updatedMessages
                                
                                chatStateService.addMessageToCurrentSession(userMessage.toMessageState())
                                
                                val originalInput = inputText
                                inputText = ""
                                isSending = true
                                
                                val aiMessageId = (System.currentTimeMillis() + 1).toString()
                                val aiMessageTimestamp = LocalDateTime.now()
                                
                                val initialAiMessage = AiMessage(
                                    id = aiMessageId,
                                    content = "",
                                    timestamp = aiMessageTimestamp,
                                    isGenerating = true
                                )
                                
                                val updatedMessagesWithAi = messages.value.toMutableList()
                                updatedMessagesWithAi.add(initialAiMessage)
                                messages.value = updatedMessagesWithAi
                                
                                chatStateService.addMessageToCurrentSession(initialAiMessage.toMessageState())
                                
                                log("开始发送消息: $originalInput")
                                
                                var tokenUsage: Pair<Int, Int>? = null
                                
                                if (langChainService != null) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        var currentContent = ""
                                        var isGenerating = true
                                        
                                        val result = langChainService.sendMessage(
                                            message = originalInput,
                                            onChunk = { chunk ->
                                                if (!isSending) return@sendMessage
                                                log("收到LangChain4j消息chunk: ${chunk.take(50)}...")
                                                currentContent += chunk
                                                isGenerating = true
                                                
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val newMessages = messages.value.toMutableList()
                                                    val updatedMessage = AiMessage(
                                                        id = aiMessageId,
                                                        content = currentContent,
                                                        timestamp = aiMessageTimestamp,
                                                        isGenerating = isGenerating
                                                    )
                                                    newMessages[newMessages.size - 1] = updatedMessage
                                                    messages.value = newMessages
                                                }
                                            },
                                            onToolCall = { toolCall ->
                                                if (!isSending) return@sendMessage
                                                log("收到LangChain4j工具调用: ${toolCall.toolName}, ID: ${toolCall.id}")
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val newMessages = messages.value.toMutableList()
                                                    
                                                    val existingIndex = newMessages.indexOfFirst { it is ToolCallMessage && it.id == toolCall.id }
                                                    
                                                    if (existingIndex >= 0) {
                                                        newMessages[existingIndex] = toolCall
                                                        log("更新工具调用消息: ${toolCall.toolName}")
                                                        chatStateService.updateToolCallMessage(toolCall.id, toolCall.isExecuting, toolCall.result, toolCall.output)
                                                    } else {
                                                        newMessages.add(toolCall)
                                                        log("添加新工具调用消息: ${toolCall.toolName}")
                                                        chatStateService.addMessageToCurrentSession(toolCall.toMessageState())
                                                    }
                                                    
                                                    messages.value = newMessages
                                                }
                                            },
                                            onTokenUsage = { inputTokens, outputTokens ->
                                                tokenUsage = Pair(inputTokens, outputTokens)
                                                log("Token使用情况: 输入=$inputTokens, 输出=$outputTokens")
                                            },
                                            onToolOutput = { toolName, output ->
                                                if (!isSending) return@sendMessage
                                                log("收到工具输出: $toolName - ${output.take(50)}...")
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val newMessages = messages.value.toMutableList()
                                                    val toolCallIndex = newMessages.indexOfFirst { 
                                                        it is ToolCallMessage && it.toolName == toolName && it.isExecuting 
                                                    }
                                                    if (toolCallIndex >= 0) {
                                                        val existingToolCall = newMessages[toolCallIndex] as ToolCallMessage
                                                        val updatedToolCall = existingToolCall.copy(
                                                            output = existingToolCall.output + output + "\n"
                                                        )
                                                        newMessages[toolCallIndex] = updatedToolCall
                                                        messages.value = newMessages
                                                        chatStateService.updateToolCallMessage(
                                                            updatedToolCall.id,
                                                            updatedToolCall.isExecuting,
                                                            updatedToolCall.result,
                                                            updatedToolCall.output
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                        
                                        CoroutineScope(Dispatchers.Main).launch {
                                            isGenerating = false
                                            isSending = false
                                            log("LangChain4j消息发送完成")
                                            
                                            sessions.value = chatStateService.sessions.toMutableList()
                                            
                                            val newMessages = messages.value.toMutableList()
                                            val updatedMessage = AiMessage(
                                                id = aiMessageId,
                                                content = currentContent,
                                                timestamp = aiMessageTimestamp,
                                                isGenerating = isGenerating
                                            )
                                            newMessages[newMessages.size - 1] = updatedMessage
                                            
                                            chatStateService.updateLastAiMessageContent(currentContent)
                                            chatStateService.setLastAiMessageGenerating(false)
                                            
                                            tokenUsage?.let {
                                                val tokenMessage = TokenUsageMessage(
                                                    id = (System.currentTimeMillis() + 3).toString(),
                                                    inputTokens = it.first,
                                                    outputTokens = it.second,
                                                    totalTokens = it.first + it.second,
                                                    timestamp = LocalDateTime.now()
                                                )
                                                newMessages.add(tokenMessage)
                                                chatStateService.addMessageToCurrentSession(tokenMessage.toMessageState())
                                            }
                                            
                                            messages.value = newMessages
                                            
                                            result.onFailure { e ->
                                                log("LangChain4j发送失败: ${e.message}")
                                                NotificationGroupManager.getInstance()
                                                    .getNotificationGroup("AI Agent Notifications")
                                                    .createNotification("发送失败: ${e.message}", NotificationType.ERROR)
                                                    .notify(null)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        enabled = inputText.trim().isNotEmpty() || isSending,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(if (isSending) "停止" else "→")
                    }
                }
            }
        }
    }
    
    // 设置对话框
    if (isSettingsOpen) {
        Dialog(
            onDismissRequest = { isSettingsOpen = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(600.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "设置",
                        style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                    OutlinedButton(
                        onClick = { 
                            isSettingsOpen = false
                            settingsVersion++
                        }
                    ) {
                        Text("关闭")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                SettingsPanel {}
            }
        }
    }
    
    // 会话管理对话框
    if (isSessionManagerOpen) {
        Dialog(
            onDismissRequest = { isSessionManagerOpen = false }
        ) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .height(500.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 16.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recent Chats",
                        style = JewelTheme.defaultTextStyle.copy(
                            fontWeight = FontWeight.Bold, 
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 新建会话按钮
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clickable {
                                    val newSession = chatStateService.createNewSession()
                                    sessions.value = chatStateService.sessions.toMutableList()
                                    currentSessionIndex = sessions.value.size - 1
                                    chatStateService.currentSessionIndex = currentSessionIndex
                                    messages.value = mutableListOf()
                                    isSessionManagerOpen = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", style = JewelTheme.defaultTextStyle.copy(fontSize = 20.sp, color = Color.White))
                        }
                        // 关闭按钮
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clickable { isSessionManagerOpen = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", style = JewelTheme.defaultTextStyle.copy(fontSize = 16.sp, color = Color.Gray))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 会话列表
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(sessions.value.sortedByDescending { it.timestamp }) { sessionState ->
                        val isActive = sessionState.id == currentSessionState.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) Color(0xFF2D5A8A) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                .clickable {
                                    val newIndex = sessions.value.indexOf(sessionState)
                                    currentSessionIndex = newIndex
                                    chatStateService.currentSessionIndex = newIndex
                                    messages.value = sessionState.messages.map { it.toChatMessage() }.toMutableList()
                                    isSessionManagerOpen = false
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                sessionState.title,
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.White,
                                    fontSize = 14.sp
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isActive) {
                                    Text(
                                        "Active",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = Color(0xFF4CAF50),
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable {
                                            if (sessions.value.size > 1) {
                                                val index = sessions.value.indexOf(sessionState)
                                                chatStateService.removeSession(index)
                                                sessions.value = chatStateService.sessions.toMutableList()
                                                if (sessionState.id == currentSessionState.id) {
                                                    currentSessionIndex = 0
                                                    if (sessions.value.isNotEmpty()) {
                                                        messages.value = sessions.value[0].messages.map { it.toChatMessage() }.toMutableList()
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "🗑",
                                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSelector(settingsVersion: Int = 0) {
    key(settingsVersion) {
        val settings = AiAgentSettings.instance.state
        var expanded by remember { mutableStateOf(false) }
        
        val currentProviderId = settings.currentProviderId
        val currentProvider = settings.providers.find { it.id == currentProviderId } ?: settings.providers.firstOrNull()
        val allSelectedModels = currentProvider?.selectedModels ?: emptyList()
        val currentModel = settings.currentModel
        
        Box(modifier = Modifier.wrapContentSize(Alignment.BottomStart)) {
            OutlinedButton(
                onClick = { expanded = true }
            ) {
                Text(currentModel)
            }
            
            if (expanded) {
                Box(
                    modifier = Modifier
                        .absoluteOffset(y = (-4).dp)
                        .width(200.dp)
                        .heightIn(max = 150.dp)
                        .background(Color(0xFF2D2D2D), RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        reverseLayout = true
                    ) {
                        allSelectedModels.reversed().forEach { model ->
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (model == currentModel) Color(0xFF007ACC) else Color.Transparent,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable {
                                            settings.currentModel = model
                                            expanded = false
                                        }
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = model,
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = if (model == currentModel) Color.White else Color.LightGray,
                                            fontWeight = if (model == currentModel) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserMessageItem(message: UserMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 350.dp)
                .background(Color(0xFF007ACC), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = JewelTheme.defaultTextStyle.copy(color = Color.White)
                )
            }
            Text(
                text = message.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun AiMessageItem(message: AiMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 350.dp)
                .background(Color(0xFF2D2D2D), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = JewelTheme.defaultTextStyle.copy(color = Color.White)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (message.isGenerating) {
                    Text(
                        text = "生成中...",
                        style = JewelTheme.defaultTextStyle.copy(color = Color.Gray, fontSize = 12.sp)
                    )
                }
                Text(
                    text = message.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray, fontSize = 12.sp)
                )
            }
        }
    }
}

@Composable
private fun ToolCallMessageItem(message: ToolCallMessage) {
    var isExpanded by remember { mutableStateOf(false) }
    
    val fileName: String? = (message.parameters["filePath"] 
        ?: message.parameters["fileName"] 
        ?: message.parameters["path"]
        ?: message.parameters["directory"])?.toString()
    
    val statusText = when {
        message.isExecuting -> "执行中"
        message.result == "成功" || message.result?.startsWith("Success") == true -> "完成"
        else -> "失败"
    }
    
    val statusColor = when {
        message.isExecuting -> Color(0xFFFF9800)
        message.result == "成功" || message.result?.startsWith("Success") == true -> Color(0xFF4CAF50)
        else -> Color(0xFFF44336)
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(
                    when {
                        message.isExecuting -> Color(0xFF2D3748)
                        message.result == "成功" || message.result?.startsWith("Success") == true -> Color(0xFF1A2A1A)
                        else -> Color(0xFF2A1A1A)
                    }, 
                    RoundedCornerShape(8.dp)
                )
                .border(
                    1.dp, 
                    statusColor, 
                    RoundedCornerShape(8.dp)
                )
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = when {
                            message.isExecuting -> "⏳"
                            message.result == "成功" || message.result?.startsWith("Success") == true -> "✓"
                            else -> "✗"
                        },
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
                    )
                    Text(
                        text = message.toolName,
                        style = JewelTheme.defaultTextStyle.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    )
                    if (fileName != null) {
                        val displayFileName = if (fileName.length > 30) fileName.substring(0, 30) + "..." else fileName
                        Text(
                            text = displayFileName,
                            style = JewelTheme.defaultTextStyle.copy(
                                color = Color.LightGray,
                                fontSize = 12.sp
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = statusText,
                        style = JewelTheme.defaultTextStyle.copy(
                            color = statusColor,
                            fontSize = 12.sp
                        )
                    )
                    Text(
                        text = if (isExpanded) "▼" else "▶",
                        style = JewelTheme.defaultTextStyle.copy(
                            color = Color.LightGray,
                            fontSize = 10.sp
                        )
                    )
                }
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                
                if (message.parameters.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "参数",
                            style = JewelTheme.defaultTextStyle.copy(
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        SelectionContainer {
                            Column {
                                message.parameters.forEach { (key, value) ->
                                    Text(
                                        text = "$key: $value",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                
                if (message.result != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (message.result == "成功" || message.result.startsWith("Success")) Color(0xFF1A2A1A) else Color(0xFF2A1A1A),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "结果",
                            style = JewelTheme.defaultTextStyle.copy(
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        SelectionContainer {
                            Text(
                                text = message.result,
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = if (message.result == "成功" || message.result.startsWith("Success")) Color(0xFF4CAF50) else Color(0xFFF44336),
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
                
                if (message.output.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "输出",
                            style = JewelTheme.defaultTextStyle.copy(
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        SelectionContainer {
                            Text(
                                text = message.output,
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.White,
                                    fontSize = 11.sp
                                ),
                                maxLines = 20,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                Text(
                    text = message.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray, fontSize = 10.sp),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TokenUsageMessageItem(message: TokenUsageMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .background(Color(0xFF1A2D3A), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF2196F3), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color(0xFF2196F3), RoundedCornerShape(4.dp))
                            .padding(2.dp)
                    ) {
                        Text(
                            text = "📊",
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
                        )
                    }
                    Text(
                        text = "Token使用情况",
                        style = JewelTheme.defaultTextStyle.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2196F3)
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        text = "输入Token: ${message.inputTokens}",
                        style = JewelTheme.defaultTextStyle.copy(
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    )
                    Text(
                        text = "输出Token: ${message.outputTokens}",
                        style = JewelTheme.defaultTextStyle.copy(
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    )
                    Text(
                        text = "总Token: ${message.totalTokens}",
                        style = JewelTheme.defaultTextStyle.copy(
                            color = Color(0xFF4CAF50),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            
            Text(
                text = message.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray, fontSize = 10.sp),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

// 会话数据类
data class ChatSession(
    val id: String,
    var title: String,
    val messages: MutableList<ChatMessage>
)

// 消息基类
open class ChatMessage(
    open val id: String,
    open val content: String,
    open val timestamp: LocalDateTime
)

// 用户消息
class UserMessage(
    override val id: String,
    override val content: String,
    override val timestamp: LocalDateTime
) : ChatMessage(id, content, timestamp)

// AI消息
class AiMessage(
    override val id: String,
    override val content: String,
    override val timestamp: LocalDateTime,
    var isGenerating: Boolean = false
) : ChatMessage(id, content, timestamp)

// 工具调用消息
data class ToolCallMessage(
    override val id: String,
    val toolName: String,
    val parameters: Map<String, Any>,
    override val timestamp: LocalDateTime,
    val isExecuting: Boolean = false,
    val result: String? = null,
    val output: String = ""
) : ChatMessage(id, "", timestamp)

data class TokenUsageMessage(
    override val id: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    override val timestamp: LocalDateTime
) : ChatMessage(id, "", timestamp)

fun ChatStateService.MessageState.toChatMessage(): ChatMessage {
    val dateTime = if (timestamp.isNotEmpty()) timestamp.toLocalDateTime() else LocalDateTime.now()
    return when (type) {
        "user" -> UserMessage(id, content, dateTime)
        "ai" -> AiMessage(id, content, dateTime, isGenerating)
        "tool" -> ToolCallMessage(
            id = id,
            toolName = toolName,
            parameters = parameters.mapValues { it.value as Any },
            timestamp = dateTime,
            isExecuting = isExecuting,
            result = result,
            output = output
        )
        "token" -> TokenUsageMessage(
            id = id,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            timestamp = dateTime
        )
        else -> UserMessage(id, content, dateTime)
    }
}

fun ChatMessage.toMessageState(): ChatStateService.MessageState {
    return when (this) {
        is UserMessage -> ChatStateService.MessageState(
            id = id,
            type = "user",
            content = content,
            timestamp = timestamp.toStateString()
        )
        is AiMessage -> ChatStateService.MessageState(
            id = id,
            type = "ai",
            content = content,
            timestamp = timestamp.toStateString(),
            isGenerating = isGenerating
        )
        is ToolCallMessage -> ChatStateService.MessageState(
            id = id,
            type = "tool",
            timestamp = timestamp.toStateString(),
            toolName = toolName,
            parameters = parameters.mapValues { it.toString() },
            isExecuting = isExecuting,
            result = result,
            output = output
        )
        is TokenUsageMessage -> ChatStateService.MessageState(
            id = id,
            type = "token",
            timestamp = timestamp.toStateString(),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens
        )
        else -> ChatStateService.MessageState(
            id = id,
            type = "user",
            content = content,
            timestamp = timestamp.toStateString()
        )
    }
}