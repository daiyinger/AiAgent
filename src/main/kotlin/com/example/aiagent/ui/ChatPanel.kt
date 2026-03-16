package com.example.aiagent.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import com.example.aiagent.service.AiAgentService
import com.example.aiagent.service.ChatStateService
import com.example.aiagent.service.LangChainAgentService
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    val presenter = remember(currentProject) { currentProject?.let { ChatPresenter(it) } }
    DisposableEffect(presenter) {
        onDispose { presenter?.dispose() }
    }

    if (presenter == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "未检测到打开的项目",
                style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
            )
        }
        return
    }

    val uiState by presenter.uiState.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isSessionManagerOpen by remember { mutableStateOf(false) }
    var settingsVersion by remember { mutableStateOf(0) }
    
    // 文本选择颜色配置
    val textSelectionColors = TextSelectionColors(
        handleColor = Color(0xFF007ACC),
        backgroundColor = Color(0xFF007ACC).copy(alpha = 0.4f)
    )
    

    
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
                // 历史会话下拉菜单
                Box {
                    var expanded by remember { mutableStateOf(false) }
                    
                    IconButton(
                        onClick = { expanded = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text(
                            text = "💬",
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 14.sp)
                        )
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
                                // 新建会话按钮
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            presenter.createNewSession()
                                            expanded = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "+",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = Color(0xFF4CAF50),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        ),
                                        modifier = Modifier.width(20.dp)
                                    )
                                    Text(
                                        "New Chat",
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                    )
                                }
                                
                                // 历史会话列表
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(uiState.sessions.sortedByDescending { it.timestamp }) { sessionState ->
                                        val isActive = sessionState.id == uiState.currentSession?.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isActive) Color(0xFF3D5A7A) else Color.Transparent,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .clickable {
                                                    val newIndex = uiState.sessions.indexOf(sessionState)
                                                    presenter.selectSession(newIndex)
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
                                            IconButton(
                                                onClick = {
                                                    val index = uiState.sessions.indexOf(sessionState)
                                                    presenter.removeSession(index)
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
            if (uiState.messages.isEmpty()) {
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
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E1E1E))
                ) {
                    val listState = rememberLazyListState()
                    val coroutineScope = rememberCoroutineScope()
                    var autoScrollEnabled by remember { mutableStateOf(true) }
                    var userScrolled by remember { mutableStateOf(false) }

                    // 监听用户滚动行为
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex }
                            .distinctUntilChanged()
                            .collect {
                                // 如果用户滚动到了不是最底部的位置，标记为用户主动滚动
                                val total = uiState.messages.size
                                if (total > 0 && it < total - 3) {
                                    userScrolled = true
                                    autoScrollEnabled = false
                                } else if (it >= total - 2) {
                                    // 用户回到底部，重新启用自动滚动
                                    userScrolled = false
                                    autoScrollEnabled = true
                                }
                            }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        state = listState
                    ) {
                        items(uiState.messages.size) { index ->
                            when (val message = uiState.messages[index]) {
                                is UserMessage -> UserMessageItem(message)
                                is AiMessage -> {
                                    // 避免在AI尚未输出任何内容时渲染空白消息框
                                    if (message.content.isNotBlank()) {
                                        AiMessageItem(message)
                                    }
                                }
                                is ToolCallMessage -> ToolCallMessageItem(
                                    message = message,
                                    onExpand = {
                                        // 只有当用户当前在底部附近且没有主动滚动时，才滚动到底部
                                        // 这样用户可以自由查看历史消息，不会被强制滚动
                                        val total = uiState.messages.size
                                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                        if (total > 0 && lastVisible >= total - 2 && !userScrolled) {
                                            coroutineScope.launch {
                                                listState.scrollToItem(total - 1)
                                            }
                                        }
                                    }
                                )
                                is TokenUsageMessage -> TokenUsageMessageItem(message)
                            }
                            if (index < uiState.messages.size - 1) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                        // 底部空间，确保最后一条消息完全可见
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // 自动滚动到最新消息
                    LaunchedEffect(uiState.messages.size) {
                        // 只有当autoScrollEnabled为true且用户没有主动滚动时才滚动
                        if (autoScrollEnabled && !userScrolled && uiState.messages.isNotEmpty()) {
                            // 滚动到最后一条消息，并为长消息预留一点空间
                            listState.scrollToItem(
                                index = uiState.messages.size - 1,
                                scrollOffset = 200
                            )
                        }
                    }
                }
            }
        }

        // 生成状态栏
        GenerationStatusBar(uiState = uiState)

        // 输入区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
                            if (uiState.isSending) {
                                presenter.stop()
                                log("用户停止发送消息")
                            } else if (inputText.trim().isNotEmpty()) {
                                val originalInput = inputText
                                inputText = ""
                                log("开始发送消息: $originalInput")
                                presenter.sendMessage(originalInput)
                            }
                        },
                        enabled = inputText.trim().isNotEmpty() || uiState.isSending,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(if (uiState.isSending) "停止" else "→")
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
                                    presenter.createNewSession()
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
                    items(uiState.sessions.sortedByDescending { it.timestamp }) { sessionState ->
                        val isActive = sessionState.id == uiState.currentSession?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) Color(0xFF2D5A8A) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clickable {
                                    val newIndex = uiState.sessions.indexOf(sessionState)
                                    presenter.selectSession(newIndex)
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
                                            val index = uiState.sessions.indexOf(sessionState)
                                            presenter.removeSession(index)
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
private fun GenerationStatusBar(uiState: ChatUiState) {
    // 是否有正在生成的 AI 消息
    val lastAiGenerating = uiState.messages.lastOrNull { it is AiMessage }
        ?.let { (it as AiMessage).isGenerating } == true
    val visible = uiState.isSending || lastAiGenerating

    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(Color(0xFF262626), RoundedCornerShape(999.dp))
            .border(1.dp, Color(0xFF3D3D3D), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "●",
                style = JewelTheme.defaultTextStyle.copy(
                    color = Color(0xFF4CAF50),
                    fontSize = 10.sp
                )
            )
            val baseText = if (uiState.isSending) "AI 正在思考并生成回复…" else "AI 正在完成本次回复…"
            Text(
                text = baseText,
                style = JewelTheme.defaultTextStyle.copy(
                    color = Color(0xFFE0E0E0),
                    fontSize = 12.sp
                )
            )
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
                    listState.scrollToItem(currentIndex)
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
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = JewelTheme.defaultTextStyle.copy(color = Color.White)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
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
                Text(
                    text = message.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = JewelTheme.defaultTextStyle.copy(color = Color(0xFF888888), fontSize = 10.sp)
                )
            }
        }
    }
}

@Composable
private fun ToolCallMessageItem(message: ToolCallMessage, onExpand: () -> Unit = {}) {
    var isExpanded by remember { mutableStateOf(message.output.isNotEmpty() && message.toolName == "compileProject") }
    
    // 当编译工具产生新输出且仍在执行中时自动展开；执行结束后自动收起，避免占满视口
    LaunchedEffect(message.output, message.isExecuting) {
        if (message.toolName == "compileProject") {
            if (message.isExecuting && message.output.isNotEmpty()) {
                isExpanded = true
                // 只有在执行中且有新输出时才滚动到底部
                onExpand()
            } else if (!message.isExecuting) {
                isExpanded = false
                // 执行结束时不滚动，避免干扰用户查看历史消息
            }
        }
    }

    // 当非编译类工具的消息框展开时触发滚动
    LaunchedEffect(isExpanded) {
        if (isExpanded && message.toolName != "compileProject") {
            onExpand()
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

    // 针对常用工具拼出简短的参数描述，放在简略条里
    val paramSummary: String? = when (message.toolName) {
        "compileProject" -> {
            val mode = message.parameters["mode"]?.toString()?.ifBlank { null }
            val target = message.parameters["target"]?.toString()?.ifBlank { null }
            val options = message.parameters["options"]?.toString()?.ifBlank { null }
            buildString {
                if (mode != null) append("mode=$mode")
                if (target != null) {
                    if (isNotEmpty()) append(", ")
                    append("target=$target")
                }
                if (options != null) {
                    if (isNotEmpty()) append(", ")
                    append("options=$options")
                }
            }.ifBlank { null }
        }
        "listFiles" -> {
            val path = message.parameters["path"]?.toString()?.ifBlank { null }
            val recursive = message.parameters["recursive"]?.toString()?.takeIf { it == "true" }
            val ext = message.parameters["extension"]?.toString()?.ifBlank { null }
            buildString {
                if (path != null) append(path)
                if (recursive != null) {
                    if (isNotEmpty()) append(", ")
                    append("recursive")
                }
                if (ext != null) {
                    if (isNotEmpty()) append(", ")
                    append("ext=$ext")
                }
            }.ifBlank { null }
        }
        "readFile" -> {
            message.parameters["path"]?.toString()?.ifBlank { null }
        }
        "searchFiles" -> {
            val pattern = message.parameters["pattern"]?.toString()?.ifBlank { null }
            val maxResults = message.parameters["max_results"]?.toString()?.ifBlank { null }
            buildString {
                if (pattern != null) append(pattern)
                if (maxResults != null) {
                    if (isNotEmpty()) append(", ")
                    append("max=$maxResults")
                }
            }.ifBlank { null }
        }
        "editFile" -> {
            message.parameters["path"]?.toString()?.ifBlank { null }
        }
        else -> null
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
                            Text(
                                text = fileName,
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
                                if (oldText != null && newText != null) {
                                    val diffLines = computeDiff(oldText, newText)
                                    val addCount = diffLines.count { it.type == DiffType.ADD }
                                    val deleteCount = diffLines.count { it.type == DiffType.DELETE }
                                    
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
                        if (oldText != null && newText != null) {
                            val diffLines = computeDiff(oldText, newText)
                            val addCount = diffLines.count { it.type == DiffType.ADD }
                            val deleteCount = diffLines.count { it.type == DiffType.DELETE }
                            
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
                    } else if (message.toolName == "compileProject") {
                        // 编译工具的参数显示，模拟editFiles的路径显示
                        val mode = message.parameters["mode"]?.toString()?.ifBlank { null }
                        val target = message.parameters["target"]?.toString()?.ifBlank { null }
                        val options = message.parameters["options"]?.toString()?.ifBlank { null }
                        val paramsText = buildString {
                            if (mode != null) append("mode=$mode")
                            if (target != null) {
                                if (isNotEmpty()) append(", ")
                                append("target=$target")
                            }
                            if (options != null) {
                                if (isNotEmpty()) append(", ")
                                append("options=$options")
                            }
                        }
                        if (paramsText.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = paramsText,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    ),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else if (message.toolName == "analyzeProject") {
                        // 分析项目工具的参数显示，模拟editFiles的路径显示
                        val projectPath = message.parameters["projectPath"]?.toString()?.ifBlank { null }
                        val depth = message.parameters["depth"]?.toString()?.ifBlank { null }
                        val paramsText = buildString {
                            if (projectPath != null) append(projectPath)
                            if (depth != null) {
                                if (isNotEmpty()) append(", ")
                                append("depth=$depth")
                            }
                        }
                        if (paramsText.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = paramsText,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    ),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else if (message.toolName == "searchFiles") {
                        // 搜索文件工具的参数显示，模拟editFiles的路径显示
                        val pattern = message.parameters["pattern"]?.toString()?.ifBlank { null }
                        val maxResults = message.parameters["max_results"]?.toString()?.ifBlank { null }
                        val paramsText = buildString {
                            if (pattern != null) append(pattern)
                            if (maxResults != null) {
                                if (isNotEmpty()) append(", ")
                                append("max=$maxResults")
                            }
                        }
                        if (paramsText.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = paramsText,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    ),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
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
                        
                        LaunchedEffect(message.output) {
                            if (outputLineList.isNotEmpty()) {
                                outputScrollState.scrollToItem(0)
                            }
                        }
                    }
                }
                
                // 对于 editFile 工具，显示文件修改的差异
                if (message.toolName == "editFile" || message.toolName == "edit_file") {
                    val oldText = message.parameters["old_text"] as? String
                    val newText = message.parameters["new_text"] as? String
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
                            val diffLines = remember(oldText, newText) {
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
                                            text = "${diffLine.lineNumber}.",
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

// Chat 消息模型与映射已迁移到 `ChatModels.kt`