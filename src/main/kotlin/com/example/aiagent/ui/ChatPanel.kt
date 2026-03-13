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
import com.example.aiagent.service.AiAgentService
import com.example.aiagent.service.LangChainAgentService
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
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter

// 日志记录函数
private fun log(message: String) {
    try {
        val logFile = File("C:\\AiAgent\\logs", "chat_panel.log")
        logFile.parentFile?.mkdirs()
        
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val timestamp = LocalDateTime.now().format(formatter)
        val logMessage = "[$timestamp] $message\n"
        
        BufferedWriter(FileWriter(logFile, true)).use { writer ->
            writer.write(logMessage)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
fun ChatPanel() {
    val settings = AiAgentSettings.instance.state
    val aiService = remember { AiAgentService() }
    val currentProject = remember { ProjectManager.getInstance().openProjects.firstOrNull() }
    val langChainService = remember { currentProject?.let { LangChainAgentService(it) } }
    
    // 选择使用哪个服务
    var useLangChain by remember { mutableStateOf(true) }
    
    // 会话管理
    val sessions = remember { mutableStateOf(mutableListOf<ChatSession>()) }
    var currentSessionIndex by remember { mutableStateOf(0) }
    
    // 确保至少有一个会话
    LaunchedEffect(Unit) {
        if (sessions.value.isEmpty()) {
            sessions.value.add(ChatSession(
                id = System.currentTimeMillis().toString(),
                title = "新会话",
                messages = mutableListOf()
            ))
        }
    }
    
    val currentSession = sessions.value.getOrElse(currentSessionIndex) { sessions.value.firstOrNull() ?: run {
        val newSession = ChatSession(
            id = System.currentTimeMillis().toString(),
            title = "新会话",
            messages = mutableListOf()
        )
        sessions.value.add(newSession)
        newSession
    }}
    
    val messages = remember { mutableStateOf(currentSession.messages) }
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isSessionManagerOpen by remember { mutableStateOf(false) }
    
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
                // 会话管理按钮
                IconButton(
                    onClick = { isSessionManagerOpen = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        text = "📁",
                        style = JewelTheme.defaultTextStyle
                    )
                }
                
                // 设置按钮
                IconButton(
                    onClick = { isSettingsOpen = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        text = "⚙️",
                        style = JewelTheme.defaultTextStyle
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
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "服务:",
                            style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray, fontSize = 12.sp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        OutlinedButton(
                            onClick = { useLangChain = !useLangChain },
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (useLangChain) "LangChain4j" else "Original",
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
                            )
                        }
                    }
                    
                    // 模型选择
                    ModelSelector()
                    
                    // 发送/停止按钮
                    OutlinedButton(
                        onClick = {
                            if (isSending) {
                                // 停止发送
                                isSending = false
                                log("用户停止发送消息")
                            } else if (inputText.trim().isNotEmpty()) {
                                val userMessage = UserMessage(
                                    id = System.currentTimeMillis().toString(),
                                    content = inputText.trim(),
                                    timestamp = LocalDateTime.now()
                                )
                                
                                // 更新消息列表
                                val updatedMessages = messages.value.toMutableList()
                                updatedMessages.add(userMessage)
                                messages.value = updatedMessages
                                
                                val originalInput = inputText
                                inputText = ""
                                isSending = true
                                
                                // 生成AI回复
                                val aiMessageId = (System.currentTimeMillis() + 1).toString()
                                val aiMessageTimestamp = LocalDateTime.now()
                                
                                // 创建初始AI消息
                                val initialAiMessage = AiMessage(
                                    id = aiMessageId,
                                    content = "",
                                    timestamp = aiMessageTimestamp,
                                    isGenerating = true
                                )
                                
                                // 更新消息列表
                                val updatedMessagesWithAi = messages.value.toMutableList()
                                updatedMessagesWithAi.add(initialAiMessage)
                                messages.value = updatedMessagesWithAi
                                
                                log("开始发送消息: $originalInput, 使用服务: ${if (useLangChain) "LangChain4j" else "Original"}")
                                
                                // 添加token使用状态
                                var tokenUsage: Pair<Int, Int>? = null
                                
                                if (useLangChain && langChainService != null) {
                                    // 使用LangChain4j服务
                                    CoroutineScope(Dispatchers.IO).launch {
                                        var currentContent = ""
                                        var isGenerating = true
                                        
                                        val result = langChainService.sendMessage(
                                            message = originalInput,
                                            onChunk = { chunk ->
                                                if (!isSending) return@sendMessage // 检查是否已停止
                                                log("收到LangChain4j消息chunk: ${chunk.take(50)}...")
                                                currentContent += chunk
                                                isGenerating = true
                                                
                                                // 在UI线程中更新消息
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
                                                if (!isSending) return@sendMessage // 检查是否已停止
                                                log("收到LangChain4j工具调用: ${toolCall.toolName}, ID: ${toolCall.id}")
                                                // 在UI线程中更新或添加工具调用消息
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val newMessages = messages.value.toMutableList()
                                                    
                                                    // 查找是否存在相同ID的工具调用消息
                                                    val existingIndex = newMessages.indexOfFirst { it is ToolCallMessage && it.id == toolCall.id }
                                                    
                                                    if (existingIndex >= 0) {
                                                        // 更新现有消息
                                                        newMessages[existingIndex] = toolCall
                                                        log("更新工具调用消息: ${toolCall.toolName}")
                                                    } else {
                                                        // 添加新消息
                                                        newMessages.add(toolCall)
                                                        log("添加新工具调用消息: ${toolCall.toolName}")
                                                    }
                                                    
                                                    messages.value = newMessages
                                                }
                                            },
                                            onTokenUsage = { inputTokens, outputTokens ->
                                                // 保存token使用情况
                                                tokenUsage = Pair(inputTokens, outputTokens)
                                                log("Token使用情况: 输入=$inputTokens, 输出=$outputTokens")
                                            }
                                        )
                                        
                                        // 发送完成后更新状态
                                        CoroutineScope(Dispatchers.Main).launch {
                                            isGenerating = false
                                            isSending = false
                                            log("LangChain4j消息发送完成")
                                            
                                            val newMessages = messages.value.toMutableList()
                                            val updatedMessage = AiMessage(
                                                id = aiMessageId,
                                                content = currentContent,
                                                timestamp = aiMessageTimestamp,
                                                isGenerating = isGenerating
                                            )
                                            newMessages[newMessages.size - 1] = updatedMessage
                                            
                                            // 添加token使用消息
                                            tokenUsage?.let {
                                                val tokenMessage = TokenUsageMessage(
                                                    id = (System.currentTimeMillis() + 3).toString(),
                                                    inputTokens = it.first,
                                                    outputTokens = it.second,
                                                    totalTokens = it.first + it.second,
                                                    timestamp = LocalDateTime.now()
                                                )
                                                newMessages.add(tokenMessage)
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
                                } else {
                                    // 使用原始服务
                                    // 构建包含工具定义的提示
                                    val promptWithTools = aiService.buildPromptWithTools(originalInput)
                                    
                                    // 使用局部变量来追踪消息内容和状态
                                    var currentContent = ""
                                    var isGenerating = true
                                    
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val result = aiService.sendMessageStream(promptWithTools, 
                                            onChunk = { chunk ->
                                                if (!isSending) return@sendMessageStream // 检查是否已停止
                                                log("收到Original消息chunk: ${chunk.take(50)}...")
                                                // 更新局部变量
                                                currentContent += chunk
                                                isGenerating = true
                                                log("更新AI消息内容，当前长度: ${currentContent.length}")
                                                
                                                // 在UI线程中更新消息
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    // 替换整个消息列表，确保Compose检测到变化
                                                    val newMessages = messages.value.toMutableList()
                                                    val updatedMessage = AiMessage(
                                                        id = aiMessageId,
                                                        content = currentContent,
                                                        timestamp = aiMessageTimestamp,
                                                        isGenerating = isGenerating
                                                    )
                                                    newMessages[newMessages.size - 1] = updatedMessage
                                                    messages.value = newMessages
                                                    log("消息更新后: ${currentContent}")
                                                }
                                            },
                                            onToolCall = { toolCall ->
                                                if (!isSending) return@sendMessageStream // 检查是否已停止
                                                log("收到工具调用: ${toolCall.toolName}")
                                                // 在UI线程中处理工具调用
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    // 添加工具调用消息
                                                    val toolCallMessage = ToolCallMessage(
                                                        id = (System.currentTimeMillis() + 2).toString(),
                                                        toolName = toolCall.toolName,
                                                        parameters = toolCall.parameters,
                                                        timestamp = LocalDateTime.now(),
                                                        isExecuting = true
                                                    )
                                                    val newMessages = messages.value.toMutableList()
                                                    newMessages.add(toolCallMessage)
                                                    messages.value = newMessages
                                                    
                                                    // 执行工具调用
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        if (!isSending) return@launch // 检查是否已停止
                                                        val toolResult = aiService.executeToolCall(toolCall)
                                                        log("工具执行结果: $toolResult")
                                                        
                                                        // 更新工具调用消息
                                                        CoroutineScope(Dispatchers.Main).launch {
                                                            val updatedMessages = messages.value.toMutableList()
                                                            val index = updatedMessages.indexOfLast { it.id == toolCallMessage.id }
                                                            if (index >= 0) {
                                                                val updatedToolCallMessage = toolCallMessage.copy(
                                                                    isExecuting = false,
                                                                    result = when (toolResult) {
                                                                        is com.example.aiagent.tools.ToolResult.Success -> "Success: ${toolResult.data}"
                                                                        is com.example.aiagent.tools.ToolResult.Error -> "Error: ${toolResult.message}"
                                                                        is com.example.aiagent.tools.ToolResult.Progress -> "Progress: ${toolResult.message}"
                                                                    }
                                                                )
                                                                updatedMessages[index] = updatedToolCallMessage
                                                                messages.value = updatedMessages
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        
                                        // 发送完成后更新状态
                                        CoroutineScope(Dispatchers.Main).launch {
                                            isGenerating = false
                                            isSending = false
                                            log("Original消息发送完成，生成状态: ${isGenerating}")
                                            
                                            // 替换整个消息列表，确保Compose检测到变化
                                            val newMessages = messages.value.toMutableList()
                                            val updatedMessage = AiMessage(
                                                id = aiMessageId,
                                                content = currentContent,
                                                timestamp = aiMessageTimestamp,
                                                isGenerating = isGenerating
                                            )
                                            newMessages[newMessages.size - 1] = updatedMessage
                                            messages.value = newMessages
                                            
                                            result.onFailure { e ->
                                                log("Original发送失败: ${e.message}")
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
                        onClick = { isSettingsOpen = false }
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
                    .width(400.dp)
                    .height(400.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "会话管理",
                        style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                    OutlinedButton(
                        onClick = { isSessionManagerOpen = false }
                    ) {
                        Text("关闭")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 会话列表
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                        .background(Color(0xFF2D2D2D))
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(sessions.value) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .background(
                                        if (it.id == currentSession.id) Color(0xFF007ACC) else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (it.id == currentSession.id) Color(0xFF007ACC) else Color.Transparent,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(8.dp)
                                    .clickable {
                                        currentSessionIndex = sessions.value.indexOf(it)
                                        // 更新当前会话的消息列表
                                        messages.value = it.messages
                                        isSessionManagerOpen = false
                                    }
                            ) {
                                Text(
                                    it.title,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = if (it.id == currentSession.id) Color.White else Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable {
                                            if (sessions.value.size > 1) {
                                                val updatedSessions = sessions.value.toMutableList()
                                                updatedSessions.remove(it)
                                                sessions.value = updatedSessions
                                                if (it.id == currentSession.id) {
                                                    currentSessionIndex = 0
                                                    // 更新当前会话的消息列表
                                                    if (sessions.value.isNotEmpty()) {
                                                        messages.value = sessions.value[0].messages
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    Text(
                                        text = "🗑️",
                                        style = JewelTheme.defaultTextStyle
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 新建会话按钮
                OutlinedButton(
                    onClick = {
                        val newSession = ChatSession(
                            id = System.currentTimeMillis().toString(),
                            title = "新会话",
                            messages = mutableListOf()
                        )
                        val updatedSessions = sessions.value.toMutableList()
                        updatedSessions.add(newSession)
                        sessions.value = updatedSessions
                        currentSessionIndex = sessions.value.size - 1
                        // 更新当前会话的消息列表
                        messages.value = newSession.messages
                        isSessionManagerOpen = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("新建会话")
                }
            }
        }
    }
}

@Composable
private fun ModelSelector() {
    val settings = AiAgentSettings.instance.state
    var expanded by remember { mutableStateOf(false) }
    
    val currentProvider = settings.providers.find { it.id == settings.currentProviderId } ?: settings.providers.firstOrNull()
    val allSelectedModels = currentProvider?.selectedModels ?: emptyList()
    
    Box {
        OutlinedButton(
            onClick = { expanded = true }
        ) {
            Text(settings.currentModel)
        }
        
        if (expanded) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF2D2D2D), RoundedCornerShape(4.dp))
                    .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                Column {
                    allSelectedModels.forEach { model ->
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .clickable {
                                    settings.currentModel = model
                                    expanded = false
                                }
                        ) {
                            Text(
                                text = model,
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = if (model == settings.currentModel) Color(0xFF007ACC) else Color.White
                                )
                            )
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .background(
                    when {
                        message.isExecuting -> Color(0xFF2D3748) // 执行中 - 深蓝色
                        message.result == "成功" || message.result?.startsWith("Success") == true -> Color(0xFF1A2A1A) // 成功 - 深绿色
                        else -> Color(0xFF2A1A1A) // 失败 - 深红色
                    }, 
                    RoundedCornerShape(12.dp)
                )
                .border(
                    1.dp, 
                    when {
                        message.isExecuting -> Color(0xFFFF9800) // 执行中 - 橙色
                        message.result == "成功" || message.result?.startsWith("Success") == true -> Color(0xFF4CAF50) // 成功 - 绿色
                        else -> Color(0xFFF44336) // 失败 - 红色
                    }, 
                    RoundedCornerShape(12.dp)
                )
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
                            .background(
                                when {
                                    message.isExecuting -> Color(0xFFFF9800)
                                    message.result == "成功" || message.result?.startsWith("Success") == true -> Color(0xFF4CAF50)
                                    else -> Color(0xFFF44336)
                                },
                                RoundedCornerShape(4.dp)
                            )
                            .padding(2.dp)
                    ) {
                        Text(
                            text = when {
                                message.isExecuting -> "⏳"
                                message.result == "成功" || message.result?.startsWith("Success") == true -> "✓"
                                else -> "✗"
                            },
                            style = JewelTheme.defaultTextStyle.copy(
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        )
                    }
                    Text(
                        text = message.toolName,
                        style = JewelTheme.defaultTextStyle.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Text(
                    text = when {
                        message.isExecuting -> "执行中"
                        message.result == "成功" || message.result?.startsWith("Success") == true -> "完成"
                        else -> "失败"
                    },
                    style = JewelTheme.defaultTextStyle.copy(
                        color = when {
                            message.isExecuting -> Color(0xFFFF9800)
                            message.result == "成功" || message.result?.startsWith("Success") == true -> Color(0xFF4CAF50)
                            else -> Color(0xFFF44336)
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (message.parameters.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
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
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (message.result != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (message.result == "成功" || message.result.startsWith("Success")) Color(0xFF1A2A1A) else Color(0xFF2A1A1A),
                            RoundedCornerShape(8.dp)
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
                            ),
                            maxLines = 8,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
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
    val result: String? = null
) : ChatMessage(id, "", timestamp)

data class TokenUsageMessage(
    override val id: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    override val timestamp: LocalDateTime
) : ChatMessage(id, "", timestamp)