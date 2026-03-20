package com.example.aiagent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import com.example.aiagent.service.AiAgentService
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.example.aiagent.service.LogService

// Diff 结果数据类
data class DiffLine(
    val lineNumber: Int,
    val content: String,
    val type: DiffType
)

// Diff 类型枚举
enum class DiffType {
    ADD,    // 新增行
    DELETE, // 删除行
    KEEP    // 保持不变的行
}

// 简单的 Diff 算法实现
fun computeDiff(oldText: String, newText: String): List<DiffLine> {
    val oldLines = oldText.lines()
    val newLines = newText.lines()
    val result = mutableListOf<DiffLine>()
    
    var i = 0 // 旧文本行索引
    var j = 0 // 新文本行索引
    
    while (i < oldLines.size || j < newLines.size) {
        when {
            i >= oldLines.size -> {
                // 旧文本已遍历完，新文本剩余行都是新增
                for (k in j until newLines.size) {
                    result.add(DiffLine(k + 1, newLines[k], DiffType.ADD))
                }
                j = newLines.size
            }
            j >= newLines.size -> {
                // 新文本已遍历完，旧文本剩余行都是删除
                for (k in i until oldLines.size) {
                    result.add(DiffLine(k + 1, oldLines[k], DiffType.DELETE))
                }
                i = oldLines.size
            }
            oldLines[i] == newLines[j] -> {
                // 行内容相同，保持不变
                result.add(DiffLine(i + 1, oldLines[i], DiffType.KEEP))
                i++
                j++
            }
            else -> {
                // 行内容不同，标记为删除和新增
                result.add(DiffLine(i + 1, oldLines[i], DiffType.DELETE))
                result.add(DiffLine(j + 1, newLines[j], DiffType.ADD))
                i++
                j++
            }
        }
    }
    
    return result
}

private fun log(message: String) {
    LogService.log(message)
}

@Composable
fun ChatPanel() {
    val settings = AiAgentSettings.instance.state
    val currentProject = remember { ProjectManager.getInstance().openProjects.firstOrNull() }
    val aiAgentService = remember { AiAgentService() }
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
    
    val scrollState = rememberScrollState()
    
    // 滚动到最新消息
    LaunchedEffect(messages.value.size) {
        if (messages.value.isNotEmpty()) {
            log("滚动到最新消息，消息数量: ${messages.value.size}")
            // 延迟一下让Compose有时间计算新的布局
            delay(100)
            scrollState.animateScrollTo(scrollState.maxValue)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(end = 12.dp)  // 整体往左移动半个图标宽度
            ) {
                // 新建会话按钮（放在历史会话气泡左侧）
                IconButton(
                    onClick = {
                        // 停止当前正在进行的AI请求
                        langChainService?.stopCurrentSession()
                        // 清除消息缓存，避免新会话使用旧缓存
                        langChainService?.clearCache()
                        // 创建新会话
                        val newSession = chatStateService.createNewSession()
                        sessions.value = chatStateService.sessions.toMutableList()
                        currentSessionIndex = sessions.value.size - 1
                        chatStateService.currentSessionIndex = currentSessionIndex
                        messages.value = mutableListOf()
                        isSending = false
                        inputText = ""
                        log("创建新会话")
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    // 使用 Canvas 绘制会话气泡+号图标
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val width = size.width
                        val height = size.height
                        val strokeWidth = 1.2f
                        
                        // 气泡主体参数
                        val bubbleWidth = width * 0.85f
                        val bubbleHeight = height * 0.7f
                        val left = (width - bubbleWidth) / 2
                        val top = (height - bubbleHeight) / 2
                        val right = left + bubbleWidth
                        val bottom = top + bubbleHeight
                        val cornerRadius = 3f
                        
                        // 尾巴参数（右下角）
                        val tailSize = 4f
                        
                        // 计算关键点坐标
                        val rightLineEndY = bottom - cornerRadius - tailSize  // 右边线终点Y坐标
                        val bottomLineEndX = right - cornerRadius - tailSize  // 底边线终点X坐标
                        
                        // 绘制直线边
                        // 上边
                        drawLine(
                            color = Color.White,
                            start = Offset(left + cornerRadius, top),
                            end = Offset(right - cornerRadius, top),
                            strokeWidth = strokeWidth
                        )
                        // 左边
                        drawLine(
                            color = Color.White,
                            start = Offset(left, top + cornerRadius),
                            end = Offset(left, bottom - cornerRadius),
                            strokeWidth = strokeWidth
                        )
                        // 右边（从顶部圆角结束到尾巴开始）
                        drawLine(
                            color = Color.White,
                            start = Offset(right, top + cornerRadius),
                            end = Offset(right, rightLineEndY),
                            strokeWidth = strokeWidth
                        )
                        // 底边（从左边圆角结束到尾巴开始）
                        drawLine(
                            color = Color.White,
                            start = Offset(left + cornerRadius, bottom),
                            end = Offset(bottomLineEndX, bottom),
                            strokeWidth = strokeWidth
                        )
                        
                        // 绘制三个圆角（除了右下角）
                        // 左上
                        drawArc(
                            color = Color.White,
                            startAngle = 180f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(left, top),
                            size = Size(cornerRadius * 2, cornerRadius * 2),
                            style = Stroke(width = strokeWidth)
                        )
                        // 右上
                        drawArc(
                            color = Color.White,
                            startAngle = 270f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(right - cornerRadius * 2, top),
                            size = Size(cornerRadius * 2, cornerRadius * 2),
                            style = Stroke(width = strokeWidth)
                        )
                        // 左下
                        drawArc(
                            color = Color.White,
                            startAngle = 90f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(left, bottom - cornerRadius * 2),
                            size = Size(cornerRadius * 2, cornerRadius * 2),
                            style = Stroke(width = strokeWidth)
                        )
                        
                        // 绘制右下角尾巴（三角形）
                        val tailTipX = right - tailSize / 2
                        val tailTipY = bottom + tailSize
                        
                        // 尾巴左边：从底边终点到尾巴尖端
                        drawLine(
                            color = Color.White,
                            start = Offset(bottomLineEndX, bottom),
                            end = Offset(tailTipX, tailTipY),
                            strokeWidth = strokeWidth
                        )
                        // 尾巴右边：从尾巴尖端到右边终点
                        drawLine(
                            color = Color.White,
                            start = Offset(tailTipX, tailTipY),
                            end = Offset(right, rightLineEndY),
                            strokeWidth = strokeWidth
                        )
                        
                        // 绘制 + 号（居中）
                        val plusSize = 4.5f
                        val centerX = width / 2
                        val centerY = (top + bottom) / 2
                        
                        // 横线
                        drawLine(
                            color = Color.White,
                            start = Offset(centerX - plusSize / 2, centerY),
                            end = Offset(centerX + plusSize / 2, centerY),
                            strokeWidth = 1.5f,
                            cap = StrokeCap.Round
                        )
                        // 竖线
                        drawLine(
                            color = Color.White,
                            start = Offset(centerX, centerY - plusSize / 2),
                            end = Offset(centerX, centerY + plusSize / 2),
                            strokeWidth = 1.5f,
                            cap = StrokeCap.Round
                        )
                    }
                }
                
                // 历史会话下拉菜单
                Box {
                    var expanded by remember { mutableStateOf(false) }
                    
                    IconButton(
                        onClick = { expanded = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        // 使用 Canvas 绘制镂空时钟图标（与+号大小一致）
                        Canvas(modifier = Modifier.size(18.dp)) {
                            val centerX = size.width / 2
                            val centerY = size.height / 2
                            val radius = size.minDimension / 2 - 1
                            
                            // 绘制外圆（镂空，只有边框）
                            drawCircle(
                                color = Color.White,
                                radius = radius,
                                center = Offset(centerX, centerY),
                                style = Stroke(width = 1.2f)
                            )
                            
                            // 绘制时钟指针（12点方向到3点方向）
                            // 时针（指向12点方向）
                            drawLine(
                                color = Color.White,
                                start = Offset(centerX, centerY),
                                end = Offset(centerX, centerY - radius * 0.5f),
                                strokeWidth = 1.2f,
                                cap = StrokeCap.Round
                            )
                            
                            // 分针（指向3点方向）
                            drawLine(
                                color = Color.White,
                                start = Offset(centerX, centerY),
                                end = Offset(centerX + radius * 0.7f, centerY),
                                strokeWidth = 1.2f,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                    
                    // 自定义下拉菜单
                    if (expanded) {
                        Popup(
                            alignment = Alignment.TopEnd,
                            onDismissRequest = { expanded = false }
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(240.dp)
                                    .heightIn(max = 350.dp)
                                    .background(Color(0xFF2D2D2D), RoundedCornerShape(6.dp))
                                    .padding(4.dp)
                            ) {
                                // 历史会话列表（已移除新建会话按钮，使用顶部+号按钮）
                                // 过滤掉 New Chat/新会话 的空会话
                                val filteredSessions = sessions.value
                                    .filter { it.title != "New Chat" && it.title != "新会话" }
                                    .sortedByDescending { it.timestamp }
                                
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(filteredSessions) { sessionState ->
                                        val isActive = sessionState.id == currentSessionState.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isActive) Color(0xFF3D5A7A) else Color.Transparent,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .clickable {
                                                    val newIndex = sessions.value.indexOf(sessionState)
                                                    currentSessionIndex = newIndex
                                                    chatStateService.currentSessionIndex = newIndex
                                                    messages.value = sessionState.messages.map { it.toChatMessage() }.toMutableList()
                                                    expanded = false
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                sessionState.title,
                                                style = JewelTheme.defaultTextStyle.copy(
                                                    color = if (isActive) Color(0xFF4CAF50) else Color.White,
                                                    fontSize = 12.sp
                                                ),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            // 删除按钮
                                            IconButton(
                                                onClick = {
                                                    val index = sessions.value.indexOf(sessionState)
                                                    chatStateService.removeSession(index)
                                                    sessions.value = chatStateService.sessions.toMutableList()
                                                    if (currentSessionIndex >= sessions.value.size) {
                                                        currentSessionIndex = sessions.value.size - 1
                                                        chatStateService.currentSessionIndex = currentSessionIndex
                                                    }
                                                    messages.value = sessions.value.getOrNull(currentSessionIndex)?.messages?.map { it.toChatMessage() }?.toMutableList() ?: mutableListOf()
                                                },
                                                modifier = Modifier.size(16.dp)
                                            ) {
                                                Text(
                                                    text = "✕",
                                                    style = JewelTheme.defaultTextStyle.copy(
                                                        color = Color(0xFFE57373),
                                                        fontSize = 12.sp
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
                
                // 设置按钮
                IconButton(
                    onClick = { isSettingsOpen = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    // 使用 Canvas 绘制镂空齿轮图标
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val centerX = size.width / 2
                        val centerY = size.height / 2
                        val outerRadius = size.minDimension / 2 - 1
                        val innerRadius = outerRadius * 0.5f
                        val teethCount = 8
                        
                        // 绘制齿轮齿（外圈）
                        for (i in 0 until teethCount) {
                            val angle = (i * 360f / teethCount) * (kotlin.math.PI / 180f).toFloat()
                            val nextAngle = ((i + 1) * 360f / teethCount) * (kotlin.math.PI / 180f).toFloat()
                            
                            // 齿的外边缘
                            val x1 = centerX + kotlin.math.cos(angle.toDouble()).toFloat() * outerRadius
                            val y1 = centerY + kotlin.math.sin(angle.toDouble()).toFloat() * outerRadius
                            val x2 = centerX + kotlin.math.cos(nextAngle.toDouble()).toFloat() * outerRadius
                            val y2 = centerY + kotlin.math.sin(nextAngle.toDouble()).toFloat() * outerRadius
                            
                            // 齿的内边缘（稍微靠内）
                            val midAngle = (angle + nextAngle) / 2
                            val innerX1 = centerX + kotlin.math.cos(midAngle.toDouble() - 0.15).toFloat() * (outerRadius * 0.75f)
                            val innerY1 = centerY + kotlin.math.sin(midAngle.toDouble() - 0.15).toFloat() * (outerRadius * 0.75f)
                            val innerX2 = centerX + kotlin.math.cos(midAngle.toDouble() + 0.15).toFloat() * (outerRadius * 0.75f)
                            val innerY2 = centerY + kotlin.math.sin(midAngle.toDouble() + 0.15).toFloat() * (outerRadius * 0.75f)
                            
                            // 绘制齿的轮廓
                            drawLine(
                                color = Color.White,
                                start = Offset(x1, y1),
                                end = Offset(innerX1, innerY1),
                                strokeWidth = 1.2f
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(innerX1, innerY1),
                                end = Offset(innerX2, innerY2),
                                strokeWidth = 1.2f
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(innerX2, innerY2),
                                end = Offset(x2, y2),
                                strokeWidth = 1.2f
                            )
                        }
                        
                        // 绘制内圆（镂空）
                        drawCircle(
                            color = Color.White,
                            radius = innerRadius,
                            center = Offset(centerX, centerY),
                            style = Stroke(width = 1.2f)
                        )
                    }
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
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(8.dp)
                    ) {
                        messages.value.forEachIndexed { index, message ->
                            when (message) {
                                is UserMessage -> UserMessageItem(message)
                                is AiMessage -> AiMessageItem(message)
                                is ToolCallMessage -> ToolCallMessageItem(message, scrollState)
                                is TokenUsageMessage -> TokenUsageMessageItem(message)
                            }
                            if (index < messages.value.size - 1) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                    
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(scrollState),
                        style = androidx.compose.foundation.ScrollbarStyle(
                            minimalHeight = 16.dp,
                            thickness = 8.dp,
                            shape = RoundedCornerShape(4.dp),
                            hoverDurationMillis = 300,
                            unhoverColor = Color(0xFF3A3A3A),
                            hoverColor = Color(0xFF4A4A4A)
                        )
                    )
                }
            }
        }
        
        // 输入区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
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
                
                // 底部栏：系统提示词选择、模型选择和发送按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 系统提示词选择
                    SystemPromptSelector(settingsVersion)
                    
                    // 模型选择
                    ModelSelector(settingsVersion)
                    
                    // 发送/停止按钮
                    OutlinedButton(
                        onClick = {
                            if (isSending) {
                                isSending = false
                                log("用户停止发送消息")
                                // 停止当前正在进行的AI请求
                                langChainService?.stopCurrentSession()
                                // 注意：不要在这里调用 resetCancellation()，因为工具可能还在执行中
                                // 清除消息缓存，避免新会话使用旧缓存
                                langChainService?.clearCache()
                                
                                // 保存当前UI层的消息内容到ChatStateService
                                log("保存当前消息状态")
                                for (message in messages.value) {
                                    if (message is AiMessage) {
                                        chatStateService.updateMessageContent(message.id, message.content)
                                    }
                                }
                            } else if (inputText.trim().isNotEmpty()) {
                                // 重置取消标志，确保新请求可以正常进行
                                langChainService?.resetCancellation()
                                
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
                                    isGenerating = true,
                                    modelName = settings.currentModel
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
                                        var currentReasoningContent = ""
                                        var isGenerating = true
                                        var currentAiMessageId = aiMessageId
                                        var shouldCreateNewAiMessage = false
                                        val messageContents = mutableMapOf<String, String>()
                                        val reasoningContents = mutableMapOf<String, String>()
                                        // 保存初始消息的初始空内容
                                        messageContents[aiMessageId] = currentContent
                                        reasoningContents[aiMessageId] = currentReasoningContent
                                        
                                        val result = langChainService.sendMessage(
                                            message = originalInput,
                                            onChunk = { chunk ->
                                                // 检查是否已取消
                                                if (langChainService?.isCancelled() == true) {
                                                    log("已取消，跳过处理 chunk")
                                                    return@sendMessage
                                                }
                                                // 移除 isSending 检查，因为 invokeLater 可能在 isSending 变为 false 后执行
                                                isGenerating = true
                                                
                                                // 如果需要创建新的 AI 消息块，先创建
                                                if (shouldCreateNewAiMessage) {
                                                    CoroutineScope(Dispatchers.Main).launch {
                                                        val newMessages = messages.value.toMutableList()
                                                        val newAiMessageId = (System.currentTimeMillis() + 1).toString()
                                                        val newAiMessageTimestamp = LocalDateTime.now()
                                                        val newAiMessage = AiMessage(
                                                            id = newAiMessageId,
                                                            content = "",
                                                            timestamp = newAiMessageTimestamp,
                                                            isGenerating = false, // 中间的消息不显示生成中状态
                                                            modelName = settings.currentModel
                                                        )
                                                        // 简单地添加到消息列表末尾
                                                        val insertPosition = newMessages.size
                                                        newMessages.add(insertPosition, newAiMessage)
                                                        messages.value = newMessages
                                                        chatStateService.addMessageToCurrentSessionAtPosition(newAiMessage.toMessageState(), insertPosition)
                                                        log("创建新的 AI 消息块：$newAiMessageId, 插入位置: $insertPosition")
                                                        // 更新当前消息 ID 和内容
                                                        currentAiMessageId = newAiMessageId
                                                        currentContent = ""
                                                        currentReasoningContent = ""
                                                        // 保存新消息的初始空内容
                                                        messageContents[currentAiMessageId] = currentContent
                                                        reasoningContents[currentAiMessageId] = currentReasoningContent
                                                        shouldCreateNewAiMessage = false
                                                        
                                                        // 现在添加新的 chunk
                                                        currentContent += chunk
                                                        messageContents[currentAiMessageId] = currentContent
                                                        
                                                        val aiMessageIndex = newMessages.indexOfFirst { it is AiMessage && it.id == currentAiMessageId }
                                                        if (aiMessageIndex >= 0) {
                                                            val existingMessage = newMessages[aiMessageIndex] as AiMessage
                                                            val updatedMessage = existingMessage.copy(
                                                                content = currentContent
                                                            )
                                                            newMessages[aiMessageIndex] = updatedMessage
                                                            messages.value = newMessages
                                                        }
                                                    }
                                                } else {
                                                    currentContent += chunk
                                                    // 保存当前消息的内容
                                                    messageContents[currentAiMessageId] = currentContent
                                                    // 实时更新 AI 消息的内容
                                                    CoroutineScope(Dispatchers.Main).launch {
                                                        val newMessages = messages.value.toMutableList()
                                                        val aiMessageIndex = newMessages.indexOfFirst { it is AiMessage && it.id == currentAiMessageId }
                                                        if (aiMessageIndex >= 0) {
                                                            val existingMessage = newMessages[aiMessageIndex] as AiMessage
                                                            val updatedMessage = existingMessage.copy(
                                                                content = currentContent
                                                            )
                                                            newMessages[aiMessageIndex] = updatedMessage
                                                            messages.value = newMessages
                                                        }
                                                    }
                                                }
                                            },
                                            onReasoningChunk = { chunk ->
                                                // 检查是否已取消
                                                if (langChainService?.isCancelled() == true) {
                                                    log("已取消，跳过处理 reasoning chunk")
                                                    return@sendMessage
                                                }
                                                isGenerating = true
                                                
                                                // 如果需要创建新的 AI 消息块，先创建
                                                if (shouldCreateNewAiMessage) {
                                                    CoroutineScope(Dispatchers.Main).launch {
                                                        val newMessages = messages.value.toMutableList()
                                                        val newAiMessageId = (System.currentTimeMillis() + 1).toString()
                                                        val newAiMessageTimestamp = LocalDateTime.now()
                                                        val newAiMessage = AiMessage(
                                                            id = newAiMessageId,
                                                            content = "",
                                                            timestamp = newAiMessageTimestamp,
                                                            isGenerating = false, // 中间的消息不显示生成中状态
                                                            modelName = settings.currentModel
                                                        )
                                                        // 简单地添加到消息列表末尾
                                                        val insertPosition = newMessages.size
                                                        newMessages.add(insertPosition, newAiMessage)
                                                        messages.value = newMessages
                                                        chatStateService.addMessageToCurrentSessionAtPosition(newAiMessage.toMessageState(), insertPosition)
                                                        log("创建新的 AI 消息块：$newAiMessageId, 插入位置: $insertPosition")
                                                        // 更新当前消息 ID 和内容
                                                        currentAiMessageId = newAiMessageId
                                                        currentContent = ""
                                                        currentReasoningContent = ""
                                                        // 保存新消息的初始空内容
                                                        messageContents[currentAiMessageId] = currentContent
                                                        reasoningContents[currentAiMessageId] = currentReasoningContent
                                                        shouldCreateNewAiMessage = false
                                                        
                                                        // 现在添加新的 reasoning chunk
                                                        currentReasoningContent += chunk
                                                        reasoningContents[currentAiMessageId] = currentReasoningContent
                                                        
                                                        val aiMessageIndex = newMessages.indexOfFirst { it is AiMessage && it.id == currentAiMessageId }
                                                        if (aiMessageIndex >= 0) {
                                                            val existingMessage = newMessages[aiMessageIndex] as AiMessage
                                                            val updatedMessage = existingMessage.copy(
                                                                reasoningContent = currentReasoningContent
                                                            )
                                                            newMessages[aiMessageIndex] = updatedMessage
                                                            messages.value = newMessages
                                                        }
                                                    }
                                                } else {
                                                    currentReasoningContent += chunk
                                                    // 保存当前消息的 reasoning 内容
                                                    reasoningContents[currentAiMessageId] = currentReasoningContent
                                                    // 实时更新 AI 消息的 reasoning 内容
                                                    CoroutineScope(Dispatchers.Main).launch {
                                                        val newMessages = messages.value.toMutableList()
                                                        val aiMessageIndex = newMessages.indexOfFirst { it is AiMessage && it.id == currentAiMessageId }
                                                        if (aiMessageIndex >= 0) {
                                                            val existingMessage = newMessages[aiMessageIndex] as AiMessage
                                                            val updatedMessage = existingMessage.copy(
                                                                reasoningContent = currentReasoningContent
                                                            )
                                                            newMessages[aiMessageIndex] = updatedMessage
                                                            messages.value = newMessages
                                                        }
                                                    }
                                                }
                                            },
                                            onComplete = {
                                                log("响应完成，更新所有AI消息")
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val newMessages = messages.value.toMutableList()
                                                    // 更新所有AI消息的内容和状态
                                                    for (i in newMessages.indices) {
                                                        if (newMessages[i] is AiMessage) {
                                                            val aiMessage = newMessages[i] as AiMessage
                                                            // 只有当messageContents中存在该消息的内容时才更新，否则保持原有内容
                                                            val savedContent = messageContents[aiMessage.id] ?: aiMessage.content
                                                            val savedReasoningContent = reasoningContents[aiMessage.id] ?: aiMessage.reasoningContent
                                                            // 最后一个消息（总结消息）才设置token统计
                                                            val shouldHaveTokenUsage = i == newMessages.indexOfLast { it is AiMessage }
                                                            val updatedMessage = AiMessage(
                                                                id = aiMessage.id,
                                                                content = savedContent,
                                                                timestamp = aiMessage.timestamp,
                                                                isGenerating = false,
                                                                tokenUsage = if (shouldHaveTokenUsage) tokenUsage else aiMessage.tokenUsage,
                                                                modelName = settings.currentModel,
                                                                reasoningContent = savedReasoningContent
                                                            )
                                                            newMessages[i] = updatedMessage
                                                        }
                                                    }
                                                    messages.value = newMessages
                                                    
                                                    // 更新 ChatStateService 中的消息内容
                                                    for ((msgId, content) in messageContents) {
                                                        chatStateService.updateMessageContent(msgId, content)
                                                    }
                                                    for ((msgId, reasoningContent) in reasoningContents) {
                                                        chatStateService.updateMessageReasoningContent(msgId, reasoningContent)
                                                    }
                                                    
                                                    // 确保所有AI消息的内容都被保存到ChatStateService
                                                    for (message in newMessages) {
                                                        if (message is AiMessage) {
                                                            // 只有当messageContents中不存在时才需要更新，避免覆盖
                                                            if (!messageContents.containsKey(message.id)) {
                                                                chatStateService.updateMessageContent(message.id, message.content)
                                                            }
                                                            if (!reasoningContents.containsKey(message.id)) {
                                                                chatStateService.updateMessageReasoningContent(message.id, message.reasoningContent)
                                                            }
                                                        }
                                                    }
                                                    
                                                    log("更新消息后，消息数量: ${newMessages.size}")
                                                    
                                                    // 滚动到最新消息
                                                    log("响应完成后滚动到底部")
                                                    // 延迟一下让Compose有时间计算新的布局
                                                    delay(100)
                                                    // 使用 scrollTo 而不是 animateScrollTo 来避免 MonotonicFrameClock 问题
                                                    scrollState.scrollTo(scrollState.maxValue)
                                                }
                                            },
                                            onToolCall = { toolCall ->
                                                log("收到LangChain4j工具调用: ${toolCall.toolName}, ID: ${toolCall.id}")
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val newMessages = messages.value.toMutableList()
                                                    
                                                    val existingIndex = newMessages.indexOfFirst { it is ToolCallMessage && it.id == toolCall.id }
                                                    
                                                    if (existingIndex >= 0) {
                                                        // 保留之前的输出内容
                                                        val existingToolCall = newMessages[existingIndex] as ToolCallMessage
                                                        val updatedToolCall = toolCall.copy(
                                                            output = existingToolCall.output
                                                        )
                                                        newMessages[existingIndex] = updatedToolCall
                                                        log("更新工具调用消息: ${toolCall.toolName}")
                                                        // 将parameters转换为Map<String, String>
                                                        val paramsAsStringMap = updatedToolCall.parameters.mapValues { it.value.toString() }
                                                        chatStateService.updateToolCallMessage(updatedToolCall.id, updatedToolCall.isExecuting, updatedToolCall.result, updatedToolCall.output, paramsAsStringMap)
                                                    } else {
                                                        // 简单地添加到消息列表末尾
                                                        val insertPosition = newMessages.size
                                                        newMessages.add(insertPosition, toolCall)
                                                        chatStateService.addMessageToCurrentSessionAtPosition(toolCall.toMessageState(), insertPosition)
                                                        log("添加新工具调用消息: ${toolCall.toolName}, 插入位置: $insertPosition")
                                                    }
                                                    
                                                    // 添加工具调用消息后，标记需要创建新的 AI 消息块
                                                    // 但不立即创建，等待下一次 onChunk 调用时再创建
                                                    shouldCreateNewAiMessage = true
                                                    log("标记需要创建新的 AI 消息块")
                                                    
                                                    messages.value = newMessages
                                                }
                                            },
                                            onTokenUsage = { inputTokens, outputTokens ->
                                                tokenUsage = Pair(inputTokens, outputTokens)
                                                log("Token使用情况: 输入=$inputTokens, 输出=$outputTokens")
                                                
                                                // 更新最后一条 AI 消息的 token 统计信息
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val newMessages = messages.value.toMutableList()
                                                    val aiMessageIndex = newMessages.indexOfLast { it is AiMessage }
                                                    if (aiMessageIndex >= 0) {
                                                        val aiMessage = newMessages[aiMessageIndex] as AiMessage
                                                        val updatedAiMessage = AiMessage(
                                                            id = aiMessage.id,
                                                            content = aiMessage.content,
                                                            timestamp = aiMessage.timestamp,
                                                            isGenerating = aiMessage.isGenerating,
                                                            tokenUsage = Pair(inputTokens, outputTokens),
                                                            modelName = settings.currentModel
                                                        )
                                                        newMessages[aiMessageIndex] = updatedAiMessage
                                                        messages.value = newMessages
                                                        
                                                        // 滚动到最新的消息
                                                        // 使用 scrollTo 而不是 animateScrollTo 来避免 MonotonicFrameClock 问题
                                                        scrollState.scrollTo(scrollState.maxValue)
                                                        
                                                        // 更新 ChatStateService 中的 token 统计信息
                                                        chatStateService.updateMessageTokenUsage(aiMessage.id, inputTokens, outputTokens)
                                                    }
                                                }
                                            },
                                            onToolOutput = { toolName, output ->
                                                log("收到工具输出: $toolName - ${output.take(50)}...")
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val newMessages = messages.value.toMutableList()
                                                    // 查找最近的同名工具调用消息
                                                    val toolCallIndex = newMessages.indexOfLast {
                                                        it is ToolCallMessage && it.toolName == toolName
                                                    }

                                                    if (toolCallIndex >= 0) {
                                                        val existingToolCall = newMessages[toolCallIndex] as ToolCallMessage
                                                        val updatedToolCall = existingToolCall.copy(
                                                            output = existingToolCall.output + output + "\n"
                                                        )
                                                        newMessages[toolCallIndex] = updatedToolCall
                                                        messages.value = newMessages
                                                        // 保留现有的parameters
                                                        val paramsAsStringMap = existingToolCall.parameters.mapValues { it.value.toString() }
                                                        chatStateService.updateToolCallMessage(
                                                            updatedToolCall.id,
                                                            updatedToolCall.isExecuting,
                                                            updatedToolCall.result,
                                                            updatedToolCall.output,
                                                            paramsAsStringMap
                                                        )

                                                        // 滚动到更新的工具调用消息的底部
                                                        // 使用 scrollTo 而不是 animateScrollTo 来避免 MonotonicFrameClock 问题
                                                        scrollState.scrollTo(scrollState.maxValue)
                                                    } else {
                                                        // 如果没有找到，记录日志
                                                        log("未找到工具调用: $toolName")
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
                                            // 查找AI消息的索引，而不是使用size - 1
                                            val aiMessageIndex = newMessages.indexOfFirst { it is AiMessage && it.id == aiMessageId }
                                            if (aiMessageIndex >= 0) {
                                                val updatedMessage = AiMessage(
                                                    id = aiMessageId,
                                                    content = currentContent,
                                                    timestamp = aiMessageTimestamp,
                                                    isGenerating = isGenerating,
                                                    tokenUsage = tokenUsage,
                                                    modelName = settings.currentModel
                                                )
                                                newMessages[aiMessageIndex] = updatedMessage
                                                
                                                chatStateService.updateLastAiMessageContent(currentContent)
                                                chatStateService.setLastAiMessageGenerating(false)
                                                
                                                // 更新 ChatStateService 中的 token 统计信息
                                                if (tokenUsage != null) {
                                                    chatStateService.updateMessageTokenUsage(aiMessageId, tokenUsage.first, tokenUsage.second)
                                                }
                                            }
                                            
                                            // 不再创建单独的 TokenUsageMessage，而是在 AI 消息末尾添加 token 统计
                                            
                                            messages.value = newMessages
                                            
                                            // 延迟一下让Compose有时间计算新的布局，然后滚动到底部
                                            CoroutineScope(Dispatchers.Main).launch {
                                                delay(200)
                                                if (newMessages.isNotEmpty()) {
                                                    log("消息完成后滚动到底部")
                                                    // 使用 scrollTo 而不是 animateScrollTo 来避免 MonotonicFrameClock 问题
                                                    scrollState.scrollTo(scrollState.maxValue)
                                                }
                                            }
                                            
                                            result.onFailure { e ->
                                                // 检查是否是用户取消操作
                                                val isCancelled = e.message?.contains("cancelled", ignoreCase = true) == true ||
                                                          e.message?.contains("取消", ignoreCase = true) == true ||
                                                          e.message?.contains("Request cancelled") == true ||
                                                          e.message?.contains("Compilation cancelled by user") == true ||
                                                          e.message?.contains("Build cancelled by user") == true
                                                
                                                if (!isCancelled) {
                                                    log("LangChain4j发送失败: ${e.message}")
                                                    NotificationGroupManager.getInstance()
                                                        .getNotificationGroup("AI Agent Notifications")
                                                        .createNotification("发送失败: ${e.message}", NotificationType.ERROR)
                                                        .notify(null)
                                                } else {
                                                    log("用户取消操作，不显示错误提示")
                                                }
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
                    .width(300.dp)
                    .height(450.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(16.dp)
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
                            fontSize = 15.sp
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 新建会话按钮
                        Box(
                            modifier = Modifier
                                .size(24.dp)
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
                            Text("+", style = JewelTheme.defaultTextStyle.copy(fontSize = 18.sp, color = Color(0xFF4CAF50)))
                        }
                        // 关闭按钮
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { isSessionManagerOpen = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp, color = Color.Gray))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 会话列表
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(sessions.value.sortedByDescending { it.timestamp }) { sessionState ->
                        val isActive = sessionState.id == currentSessionState.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) Color(0xFF2D5A8A) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
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
                                    color = if (isActive) Color(0xFF4CAF50) else Color.White,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isActive) {
                                    Text(
                                        "●",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = Color(0xFF4CAF50),
                                            fontSize = 8.sp
                                        )
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
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
                                        style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp)
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
        val listState = rememberLazyListState()
        
        val currentModel = settings.currentModel
        
        // 构建所有提供者的模型列表，按提供者分组
        data class ModelItem(val model: String, val providerId: String, val providerName: String)
        val allModelsWithProvider = mutableListOf<ModelItem>()
        settings.providers.forEach { provider ->
            provider.selectedModels.forEach { model ->
                allModelsWithProvider.add(ModelItem(model, provider.id, provider.name))
            }
        }
        
        LaunchedEffect(expanded) {
            if (expanded && allModelsWithProvider.isNotEmpty()) {
                val currentIndex = allModelsWithProvider.indexOfFirst { it.model == currentModel }
                if (currentIndex >= 0) {
                    try {
                        listState.scrollToItem(currentIndex)
                    } catch (e: Exception) {
                        // 忽略滚动错误
                    }
                }
            }
        }
        
        Box(modifier = Modifier.wrapContentSize(Alignment.BottomStart)) {
            OutlinedButton(
                onClick = { expanded = true }
            ) {
                Text(currentModel)
            }
            
            if (expanded) {
                Popup(
                    alignment = Alignment.BottomStart,
                    onDismissRequest = { expanded = false },
                    offset = IntOffset(x = 0, y = -4)
                ) {
                    Box(
                        modifier = Modifier
                            .width(200.dp)
                            .heightIn(max = 200.dp)
                            .background(Color(0xFF2D2D2D), RoundedCornerShape(4.dp))
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(4.dp)
                    ) {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = listState
                        ) {
                            var lastProviderId: String? = null
                            allModelsWithProvider.forEach { item ->
                                // 如果切换了提供者，添加分隔符
                                if (lastProviderId != null && lastProviderId != item.providerId) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .height(1.dp)
                                                .background(Color.Gray.copy(alpha = 0.5f))
                                        )
                                    }
                                }
                                
                                // 如果是该提供者的第一个模型，显示提供者名称
                                if (lastProviderId != item.providerId) {
                                    item {
                                        Text(
                                            text = item.providerName,
                                            style = JewelTheme.defaultTextStyle.copy(
                                                color = Color.Gray,
                                                fontSize = 10.sp
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (item.model == currentModel) Color(0xFF007ACC) else Color.Transparent,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable {
                                                settings.currentModel = item.model
                                                settings.currentProviderId = item.providerId
                                                expanded = false
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = item.model,
                                            style = JewelTheme.defaultTextStyle.copy(
                                                color = if (item.model == currentModel) Color.White else Color.LightGray,
                                                fontWeight = if (item.model == currentModel) FontWeight.Bold else FontWeight.Normal
                                            )
                                        )
                                    }
                                }
                                
                                lastProviderId = item.providerId
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemPromptSelector(settingsVersion: Int = 0) {
    key(settingsVersion) {
        val settings = AiAgentSettings.instance.state
        var expanded by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        
        val currentPromptId = settings.currentSystemPromptId
        val currentPrompt = settings.systemPrompts.find { it.id == currentPromptId }
        
        LaunchedEffect(expanded) {
            if (expanded && settings.systemPrompts.isNotEmpty()) {
                val currentIndex = settings.systemPrompts.indexOfFirst { it.id == currentPromptId }
                if (currentIndex >= 0) {
                    try {
                        listState.scrollToItem(currentIndex)
                    } catch (e: Exception) {
                    }
                }
            }
        }
        
        Box(modifier = Modifier.wrapContentSize(Alignment.BottomStart)) {
            OutlinedButton(
                onClick = { expanded = true }
            ) {
                Text(currentPrompt?.name ?: "选择提示词")
            }
            
            if (expanded) {
                Popup(
                    alignment = Alignment.BottomStart,
                    onDismissRequest = { expanded = false },
                    offset = IntOffset(x = 0, y = -4)
                ) {
                    Box(
                        modifier = Modifier
                            .width(250.dp)
                            .heightIn(max = 200.dp)
                            .background(Color(0xFF2D2D2D), RoundedCornerShape(4.dp))
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(4.dp)
                    ) {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = listState
                        ) {
                            items(settings.systemPrompts.size) { index ->
                                val prompt = settings.systemPrompts[index]
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (prompt.id == currentPromptId) Color(0xFF3A6B99) else Color.Transparent,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable {
                                            settings.currentSystemPromptId = prompt.id
                                            expanded = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = prompt.name,
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = if (prompt.id == currentPromptId) Color.White else Color.LightGray,
                                            fontWeight = if (prompt.id == currentPromptId) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                    Text(
                                        text = prompt.content,
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = if (prompt.id == currentPromptId) Color.White.copy(alpha = 0.7f) else Color.Gray,
                                            fontSize = 10.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
                .background(Color(0xFF3A3A3A), RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = JewelTheme.defaultTextStyle.copy(color = Color.White)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                style = JewelTheme.defaultTextStyle.copy(color = Color(0xFF888888), fontSize = 10.sp),
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
                .fillMaxWidth(0.9f)
                .background(Color(0xFF2D2D2D), RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            // 显示思考内容（浅灰色）
            if (message.reasoningContent.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = message.reasoningContent,
                        style = JewelTheme.defaultTextStyle.copy(color = Color(0xFFBBBBBB)),
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            SelectionContainer {
                Text(
                    text = message.content,
                    style = JewelTheme.defaultTextStyle.copy(color = Color.White),
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 只在有token统计的文本框显示间距和状态信息
            if (message.tokenUsage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.height(16.dp)) {
                        if (message.isGenerating) {
                            Text(
                                text = "生成中...",
                                style = JewelTheme.defaultTextStyle.copy(color = Color.Gray, fontSize = 12.sp)
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                message.modelName?.let {
                                    Text(
                                        text = it,
                                        style = JewelTheme.defaultTextStyle.copy(color = Color(0xFF4CAF50), fontSize = 10.sp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                message.tokenUsage?.let {
                                    Text(
                                        text = "Tokens: in ${it.first}/out ${it.second}",
                                        style = JewelTheme.defaultTextStyle.copy(color = Color(0xFF888888), fontSize = 10.sp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                            }
                        }
                    }
                    // 只在有token统计的文本框显示时间戳
                    if (message.isGenerating) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = message.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = JewelTheme.defaultTextStyle.copy(color = Color(0xFF888888), fontSize = 10.sp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallMessageItem(message: ToolCallMessage, scrollState: androidx.compose.foundation.ScrollState? = null) {
    var isExpanded by remember { mutableStateOf(message.output.isNotEmpty() && message.toolName == "compileProject") }
    
    // 当编译工具产生新输出时自动展开，执行完成后自动折叠
    LaunchedEffect(message.output, message.isExecuting) {
        if (message.toolName == "compileProject" || message.toolName == "compile_project") {
            if (message.output.isNotEmpty() && message.isExecuting) {
                isExpanded = true
            } else if (!message.isExecuting) {
                isExpanded = false
            }
        }
    }
    
    // 当编译输出展开时，滚动到底部
    LaunchedEffect(isExpanded) {
        if (isExpanded && (message.toolName == "compileProject" || message.toolName == "compile_project")) {
            // 延迟一下让Compose有时间计算新的布局
            delay(100)
            // 滚动到底部
            // 使用 scrollTo 而不是 animateScrollTo 来避免 MonotonicFrameClock 问题
            scrollState?.scrollTo(scrollState.maxValue)
        }
    }
    
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
                    modifier = Modifier.weight(1f),
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
                    // 处理文件名和行数变化的显示
                    if (fileName != null) {
                        // 当有文件名时
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            // 对于读取文件工具，将行号信息拼接到文件名后面
                            val displayFileName = if (message.toolName == "read_file") {
                                val totalLineCount = message.parameters["lineCount"].let { value ->
                                    when (value) {
                                        is Number -> value.toInt()
                                        is String -> value.toIntOrNull() ?: 0
                                        else -> 0
                                    }
                                }
                                val actualStartLine = message.parameters["actualStartLine"].let { value ->
                                    when (value) {
                                        is Number -> value.toInt()
                                        is String -> value.toIntOrNull()
                                        else -> null
                                    }
                                }
                                val actualEndLine = message.parameters["actualEndLine"].let { value ->
                                    when (value) {
                                        is Number -> value.toInt()
                                        is String -> value.toIntOrNull()
                                        else -> null
                                    }
                                }
                                
                                val lineRangeText = if (actualStartLine != null && actualEndLine != null && actualStartLine > 0 && actualEndLine > 0) {
                                    // 总是显示行号范围，格式：L20-50
                                    "L${actualStartLine}-${actualEndLine}"
                                } else {
                                    // 旧版本或无行范围信息，显示总行数
                                    "L1-${totalLineCount}"
                                }
                                
                                // 智能路径显示：尝试显示尽可能多的路径信息
                                 fileName?.let { path ->
                                     val file = java.io.File(path)
                                     val baseName = file.name
                                     val parent = file.parent
                                     val isFullPath = path == fileName // 检查是否完整路径
                                     
                                     // 策略1：显示完整路径（如果不太长）
                                     val fullPath = "$path $lineRangeText"
                                     if (fullPath.length <= 50) {
                                         // 完整路径不太长，直接显示
                                         return@let fullPath
                                     }
                                     
                                     // 策略2：显示最后两级目录（如果有）
                                     if (parent != null) {
                                         val parentParts = parent.split(java.io.File.separatorChar)
                                         if (parentParts.size >= 2) {
                                             val lastTwoDirs = parentParts.takeLast(2).joinToString(java.io.File.separator)
                                             val pathWithTwoDirs = "...$lastTwoDirs${java.io.File.separator}$baseName $lineRangeText"
                                             if (pathWithTwoDirs.length <= 60) {
                                                 return@let pathWithTwoDirs
                                             }
                                         }
                                         
                                         // 策略3：显示最后一级目录
                                         val lastDir = parentParts.lastOrNull()
                                         if (lastDir != null) {
                                             val pathWithLastDir = "...$lastDir${java.io.File.separator}$baseName $lineRangeText"
                                             if (pathWithLastDir.length <= 60) {
                                                 return@let pathWithLastDir
                                             }
                                         }
                                     }
                                     
                                     // 策略4：只显示文件名（前面加...表示路径被省略）
                                     "...$baseName $lineRangeText"
                                 } ?: fileName
                            } else {
                                fileName
                            }
                            
                            Text(
                                text = displayFileName ?: "",
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            // 显示文件修改的行数变化
                            if ((message.toolName == "editFile" || message.toolName == "edit_file") && !message.isExecuting) {
                                val oldText = message.parameters["old_text"] as? String
                                val newText = message.parameters["new_text"] as? String
                                if (newText != null) {
                                    var addCount = 0
                                    var deleteCount = 0
                                    
                                    // 处理空字符串或null的特殊情况（新文件）
                                    if (oldText == null || oldText.isBlank()) {
                                        // 新文件，只计算新增行数
                                        addCount = newText.lines().filter { it.isNotEmpty() }.size
                                        deleteCount = 0
                                    } else {
                                        // 现有文件，计算差异
                                        val diffLines = computeDiff(oldText, newText)
                                        addCount = diffLines.count { it.type == DiffType.ADD }
                                        deleteCount = diffLines.count { it.type == DiffType.DELETE }
                                    }
                                    
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (addCount > 0) {
                                            Text(
                                                text = "+${addCount}",
                                                style = JewelTheme.defaultTextStyle.copy(
                                                    color = Color(0xFF4CAF50),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            )
                                        }
                                        if (addCount > 0 && deleteCount > 0) {
                                            Text(
                                                text = " ",
                                                style = JewelTheme.defaultTextStyle.copy(
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                        if (deleteCount > 0) {
                                            Text(
                                                text = "-${deleteCount}",
                                                style = JewelTheme.defaultTextStyle.copy(
                                                    color = Color(0xFFF44336),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            )
                                        }
                                        if (addCount == 0 && deleteCount == 0) {
                                            Text(
                                                text = "0",
                                                style = JewelTheme.defaultTextStyle.copy(
                                                    color = Color.LightGray,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else if ((message.toolName == "editFile" || message.toolName == "edit_file") && !message.isExecuting) {
                        // 当没有文件名时，行数变化占满剩余空间
                        val oldText = message.parameters["old_text"] as? String
                        val newText = message.parameters["new_text"] as? String
                        if (newText != null) {
                            var addCount = 0
                            var deleteCount = 0
                            
                            // 处理空字符串或null的特殊情况（新文件）
                            if (oldText == null || oldText.isBlank()) {
                                // 新文件，只计算新增行数
                                addCount = newText.lines().filter { it.isNotEmpty() }.size
                                deleteCount = 0
                            } else {
                                // 现有文件，计算差异
                                val diffLines = computeDiff(oldText, newText)
                                addCount = diffLines.count { it.type == DiffType.ADD }
                                deleteCount = diffLines.count { it.type == DiffType.DELETE }
                            }
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (addCount > 0) {
                                    Text(
                                        text = "+${addCount}",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = Color(0xFF4CAF50),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                                if (addCount > 0 && deleteCount > 0) {
                                    Text(
                                        text = " ",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                                if (deleteCount > 0) {
                                    Text(
                                        text = "-${deleteCount}",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = Color(0xFFF44336),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                                if (addCount == 0 && deleteCount == 0) {
                                    Text(
                                        text = "0",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    } else if (message.toolName == "compileProject" || message.toolName == "compile_project") {
                        // 显示编译参数
                        val mode = message.parameters["mode"] as? String ?: "build"
                        val buildType = message.parameters["build_type"] as? String ?: "debug"
                        val skipTests = message.parameters["skip_tests"] as? Boolean ?: false
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = mode,
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            Text(
                                text = "/",
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                            )
                            Text(
                                text = buildType,
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            if (skipTests) {
                                Text(
                                    text = "(跳过测试)",
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = Color.LightGray,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    } else if (message.toolName == "searchFiles" || message.toolName == "search_files") {
                        // 显示搜索参数
                        val pattern = message.parameters["pattern"] as? String ?: ""
                        val maxResults = message.parameters["max_results"] as? Number ?: 20
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = pattern,
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = "(max: $maxResults)",
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    } else if (message.toolName == "read_file") {
                        // 显示读取文件的行号范围（当没有文件名时）
                        val totalLineCount = message.parameters["lineCount"].let { value ->
                            when (value) {
                                is Number -> value.toInt()
                                is String -> value.toIntOrNull() ?: 0
                                else -> 0
                            }
                        }
                        val actualStartLine = message.parameters["actualStartLine"].let { value ->
                            when (value) {
                                is Number -> value.toInt()
                                is String -> value.toIntOrNull()
                                else -> null
                            }
                        }
                        val actualEndLine = message.parameters["actualEndLine"].let { value ->
                            when (value) {
                                is Number -> value.toInt()
                                is String -> value.toIntOrNull()
                                else -> null
                            }
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            val displayText = if (actualStartLine != null && actualEndLine != null && actualStartLine > 0 && actualEndLine > 0) {
                                // 显示行号范围，格式：L20-50
                                "L${actualStartLine}-${actualEndLine}"
                            } else {
                                // 旧版本或无行范围信息
                                "L1-${totalLineCount}"
                            }
                            
                            Text(
                                text = displayText,
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = " ", // 添加一个空格，与前面的内容保持间距
                        style = JewelTheme.defaultTextStyle.copy(
                            fontSize = 11.sp
                        )
                    )
                    Text(
                        text = statusText,
                        style = JewelTheme.defaultTextStyle.copy(
                            color = statusColor,
                            fontSize = 11.sp
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
                
                // 对于 editFile 工具，不显示参数详细部分，只显示差异部分
                if (message.parameters.isNotEmpty() && message.toolName != "editFile" && message.toolName != "edit_file") {
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
                
                // 对于 editFile 工具，不显示结果部分，只显示差异部分
                if (message.result != null && message.toolName != "editFile" && message.toolName != "edit_file") {
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
                
                // 对于 editFile 工具，不显示输出部分，只显示差异部分
                if (message.output.isNotEmpty() && message.toolName != "editFile" && message.toolName != "edit_file") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                    ) {
                        Text(
                            text = "输出",
                            style = JewelTheme.defaultTextStyle.copy(
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(8.dp)
                        )
                        val outputScrollState = rememberLazyListState()
                        val outputLineList = remember(message.output) {
                            message.output.split("\n").filter { it.isNotBlank() }
                        }
                        
                        LazyColumn(
                            state = outputScrollState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .padding(8.dp),
                            reverseLayout = true
                        ) {
                            items(outputLineList.size) { index ->
                                val lineIndex = outputLineList.size - 1 - index
                                val line = outputLineList[lineIndex]
                                Text(
                                    text = line,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = Color.White,
                                        fontSize = 11.sp
                                    ),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        LaunchedEffect(message.output, outputScrollState) {
                            if (outputLineList.isNotEmpty()) {
                                try {
                                    outputScrollState.scrollToItem(0)
                                } catch (e: Exception) {
                                    // 忽略滚动错误（组件可能已卸载）
                                }
                            }
                        }
                    }
                }
                
                // 对于 editFile 工具，显示文件修改的差异
                if (message.toolName == "editFile" || message.toolName == "edit_file") {
                    val oldText = message.parameters["old_text"] as? String
                    val newText = message.parameters["new_text"] as? String
                    val startLineNumber = message.parameters["start_line_number"]?.let { value ->
                        when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: 1
                            else -> 1
                        }
                    } ?: 1
                    if (oldText != null && newText != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                        ) {
                            Text(
                                text = "文件修改差异",
                                style = JewelTheme.defaultTextStyle.copy(
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(8.dp)
                            )
                            val diffScrollState = rememberLazyListState()
                            val diffLines = remember(oldText, newText, startLineNumber) {
                                computeDiff(oldText, newText)
                            }
                            
                            LazyColumn(
                                state = diffScrollState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .padding(8.dp)
                            ) {
                                items(diffLines.size) { index ->
                                    val diffLine = diffLines[index]
                                    // 计算实际文件行号
                                    val actualLineNumber = when (diffLine.type) {
                                        DiffType.ADD -> {
                                            // 新增行：显示在新文件中的行号
                                            // 简单计算：起始行号 + 新增行在新文本中的行号 - 1
                                            startLineNumber + diffLine.lineNumber - 1
                                        }
                                        DiffType.DELETE -> {
                                            // 删除行：显示在旧文件中的行号
                                            startLineNumber + diffLine.lineNumber - 1
                                        }
                                        DiffType.KEEP -> {
                                            // 未更改行：显示在文件中的行号
                                            startLineNumber + diffLine.lineNumber - 1
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 显示差异类型图标
                                        Text(
                                            text = when (diffLine.type) {
                                                DiffType.ADD -> "+"
                                                DiffType.DELETE -> "-"
                                                DiffType.KEEP -> " "
                                            },
                                            style = JewelTheme.defaultTextStyle.copy(
                                                color = when (diffLine.type) {
                                                    DiffType.ADD -> Color(0xFF4CAF50)
                                                    DiffType.DELETE -> Color(0xFFF44336)
                                                    DiffType.KEEP -> Color.LightGray
                                                },
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            ),
                                            modifier = Modifier.width(20.dp)
                                        )
                                        // 显示行号
                                        Text(
                                            text = "${actualLineNumber}.",
                                            style = JewelTheme.defaultTextStyle.copy(
                                                color = Color.LightGray,
                                                fontSize = 10.sp
                                            ),
                                            modifier = Modifier.width(30.dp)
                                        )
                                        // 显示行内容
                                        Text(
                                            text = diffLine.content,
                                            style = JewelTheme.defaultTextStyle.copy(
                                                color = when (diffLine.type) {
                                                    DiffType.ADD -> Color(0xFF4CAF50)
                                                    DiffType.DELETE -> Color(0xFFF44336)
                                                    DiffType.KEEP -> Color.White
                                                },
                                                fontSize = 11.sp
                                            ),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
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
data class AiMessage(
    override val id: String,
    override val content: String,
    override val timestamp: LocalDateTime,
    var isGenerating: Boolean = false,
    var tokenUsage: Pair<Int, Int>? = null,
    var modelName: String? = null,
    var reasoningContent: String = ""
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
        "ai" -> AiMessage(id, content, dateTime, isGenerating, if (inputTokens > 0 || outputTokens > 0) Pair(inputTokens, outputTokens) else null, modelName, reasoningContent)
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
            isGenerating = isGenerating,
            inputTokens = tokenUsage?.first ?: 0,
            outputTokens = tokenUsage?.second ?: 0,
            totalTokens = (tokenUsage?.first ?: 0) + (tokenUsage?.second ?: 0),
            modelName = modelName,
            reasoningContent = reasoningContent
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