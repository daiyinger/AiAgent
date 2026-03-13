package com.example.aiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.aiagent.service.AiAgentService
import com.example.aiagent.settings.AiAgentSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun SettingsPanel(
    onSettingsSaved: () -> Unit = {}
) {
    val settings = AiAgentSettings.instance.state
    var currentProviderIndex by remember { mutableStateOf(settings.providers.indexOfFirst { it.id == settings.currentProviderId }) }
    if (currentProviderIndex == -1) currentProviderIndex = 0
    
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    
    // 添加状态变量来跟踪当前Provider的属性变化
    var currentProviderName by remember { mutableStateOf(TextFieldValue("")) }
    var currentApiType by remember { mutableStateOf("") }
    var currentApiHost by remember { mutableStateOf(TextFieldValue("")) }
    var currentApiPort by remember { mutableStateOf(TextFieldValue("11434")) }
    var currentApiKey by remember { mutableStateOf(TextFieldValue("")) }
    var selectedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentTimeout by remember { mutableStateOf(TextFieldValue("60")) }
    var currentTemperature by remember { mutableStateOf(TextFieldValue("0.7")) }
    var currentTopP by remember { mutableStateOf(TextFieldValue("0.9")) }
    
    // 添加FocusRequester用于处理输入框焦点
    val providerNameFocusRequester = remember { FocusRequester() }
    val apiHostFocusRequester = remember { FocusRequester() }
    val apiPortFocusRequester = remember { FocusRequester() }
    val apiKeyFocusRequester = remember { FocusRequester() }
    val timeoutFocusRequester = remember { FocusRequester() }
    val temperatureFocusRequester = remember { FocusRequester() }
    val topPFocusRequester = remember { FocusRequester() }
    
    val scope = rememberCoroutineScope()
    val aiService = remember { AiAgentService() }
    
    // 当currentProviderIndex变化时，更新所有状态变量
    LaunchedEffect(currentProviderIndex) {
        if (settings.providers.isNotEmpty()) {
            val provider = settings.providers[currentProviderIndex]
            currentProviderName = TextFieldValue(provider.name)
            currentApiType = provider.apiType
            currentApiHost = TextFieldValue(provider.apiHost)
            currentApiPort = TextFieldValue(provider.apiPort.toString())
            currentApiKey = TextFieldValue(provider.apiKey)
            selectedModels = provider.selectedModels
            currentTimeout = TextFieldValue(provider.timeoutSeconds.toString())
            currentTemperature = TextFieldValue(provider.temperature.toString())
            currentTopP = TextFieldValue(provider.topP.toString())
        }
    }
    
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 设置标题区域，带图标
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⚙️ ",
                        style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                    Text(
                        "模型Provider配置",
                        style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                }
                
                OutlinedButton(
                    onClick = {
                        val newProvider = AiAgentSettings.Provider(
                            id = "provider_${System.currentTimeMillis()}",
                            name = "新Provider",
                            apiType = "ollama",
                            apiHost = "localhost",
                            apiPort = 11434
                        )
                        settings.providers.add(newProvider)
                        currentProviderIndex = settings.providers.size - 1
                        settings.currentProviderId = newProvider.id
                    }
                ) {
                    Text("添加Provider")
                }
            }
        }
        
        // Provider选择器
        item {
            Column {
                Text("选择Provider:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                        .background(JewelTheme.globalColors.panelBackground)
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(settings.providers) { provider ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .background(
                                        if (provider.id == settings.currentProviderId) Color(0xFF007ACC) else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (provider.id == settings.currentProviderId) Color(0xFF007ACC) else Color.Transparent,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(8.dp)
                                    .clickable {
                                        currentProviderIndex = settings.providers.indexOf(provider)
                                        settings.currentProviderId = provider.id
                                        if (provider.selectedModels.isNotEmpty()) {
                                            settings.currentModel = provider.selectedModels[0]
                                        }
                                    }
                            ) {
                                Text(
                                    provider.name,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = if (provider.id == settings.currentProviderId) Color.White else Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable {
                                            if (settings.providers.size > 1) {
                                                settings.providers.remove(provider)
                                                if (provider.id == settings.currentProviderId) {
                                                    currentProviderIndex = 0
                                                    settings.currentProviderId = settings.providers[0].id
                                                    if (settings.providers[0].selectedModels.isNotEmpty()) {
                                                        settings.currentModel = settings.providers[0].selectedModels[0]
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
            }
        }
        
        if (settings.providers.isNotEmpty()) {
            val currentProvider = settings.providers[currentProviderIndex]
            
            // Provider名称
            item {
                Column {
                    Text("Provider名称:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    androidx.compose.foundation.text.BasicTextField(
                        value = currentProviderName,
                        onValueChange = { 
                            currentProviderName = it 
                            settings.providers[currentProviderIndex].name = it.text 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(12.dp)
                            .background(JewelTheme.globalColors.panelBackground)
                            .focusRequester(providerNameFocusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    currentProviderName = TextFieldValue(
                                        text = currentProviderName.text,
                                        selection = androidx.compose.ui.text.TextRange(currentProviderName.text.length)
                                    )
                                }
                            },
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        providerNameFocusRequester.requestFocus()
                                    }
                            ) {
                                if (currentProviderName.text.isEmpty()) {
                                    Text(
                                        text = "输入Provider名称",
                                        style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
            
            // API Type
            item {
                Column {
                    Text("API 类型:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    Row {
                        listOf("ollama", "openai").forEach { type ->
                            Box(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .border(2.dp, if (currentApiType == type) Color(0xFF007ACC) else Color.Gray, RoundedCornerShape(4.dp))
                                    .padding(12.dp)
                                    .clickable {
                                        currentApiType = type
                                        settings.providers[currentProviderIndex].apiType = type
                                    }
                            ) {
                                Text(
                                    text = type,
                                    style = JewelTheme.defaultTextStyle.copy(color = Color.White)
                                )
                            }
                        }
                    }
                }
            }
            
            // API Host
            item {
                Column {
                    Text(if (currentApiType == "openai") "API Base URL:" else "API Host:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    androidx.compose.foundation.text.BasicTextField(
                        value = currentApiHost,
                        onValueChange = { 
                            currentApiHost = it 
                            settings.providers[currentProviderIndex].apiHost = it.text 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(12.dp)
                            .background(JewelTheme.globalColors.panelBackground)
                            .focusRequester(apiHostFocusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    currentApiHost = TextFieldValue(
                                        text = currentApiHost.text,
                                        selection = androidx.compose.ui.text.TextRange(currentApiHost.text.length)
                                    )
                                }
                            },
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        apiHostFocusRequester.requestFocus()
                                    }
                            ) {
                                if (currentApiHost.text.isEmpty()) {
                                    Text(
                                        text = if (currentApiType == "openai") "https://api.openai.com" else "localhost",
                                        style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
            
            // API Port (only for ollama)
            if (currentApiType == "ollama") {
                item {
                    Column {
                        Text("API Port:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = currentApiPort,
                            onValueChange = { 
                                val port = it.text.toIntOrNull() ?: 11434
                                currentApiPort = TextFieldValue(port.toString())
                                settings.providers[currentProviderIndex].apiPort = port
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(12.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(apiPortFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        currentApiPort = TextFieldValue(
                                            text = currentApiPort.text,
                                            selection = androidx.compose.ui.text.TextRange(currentApiPort.text.length)
                                        )
                                    }
                                },
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            apiPortFocusRequester.requestFocus()
                                        }
                                ) {
                                    if (currentApiPort.text.isEmpty()) {
                                        Text(
                                            text = "11434",
                                            style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            }
            
            // API Key (for openai)
            if (currentApiType == "openai") {
                item {
                    Column {
                        Text("API Key:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = currentApiKey,
                            onValueChange = { 
                                currentApiKey = it 
                                settings.providers[currentProviderIndex].apiKey = it.text 
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(12.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(apiKeyFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        currentApiKey = TextFieldValue(
                                            text = currentApiKey.text,
                                            selection = androidx.compose.ui.text.TextRange(currentApiKey.text.length)
                                        )
                                    }
                                },
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            apiKeyFocusRequester.requestFocus()
                                        }
                                ) {
                                    if (currentApiKey.text.isEmpty()) {
                                        Text(
                                            text = "sk-...",
                                            style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            }
            
            // Model Selection
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("模型选择:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isLoadingModels = true
                                    val result = aiService.getAvailableModels(currentProvider)
                                    result.onSuccess { models ->
                                        availableModels = models
                                    }
                                    isLoadingModels = false
                                }
                            },
                            enabled = !isLoadingModels
                        ) {
                            Text(if (isLoadingModels) "加载中..." else "刷新模型列表")
                        }
                    }
                    
                    if (availableModels.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .background(JewelTheme.globalColors.panelBackground)
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                items(availableModels) { model ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(4.dp)
                                            .background(
                                            if (selectedModels.contains(model)) Color(0xFF007ACC) else Color.Transparent
                                        )
                                        .border(
                                            1.dp,
                                            if (selectedModels.contains(model)) Color(0xFF007ACC) else Color.Gray,
                                            RoundedCornerShape(4.dp)
                                        )
                                            .padding(8.dp)
                                            .clickable {
                                                val updatedModels = if (currentProvider.selectedModels.contains(model)) {
                                                    currentProvider.selectedModels.filter { it != model }.toMutableList()
                                                } else {
                                                    currentProvider.selectedModels.toMutableList().apply { add(model) }
                                                }
                                                currentProvider.selectedModels = updatedModels
                                                selectedModels = updatedModels
                                            }
                                    ) {
                                        Text(
                                            text = model,
                                            style = JewelTheme.defaultTextStyle.copy(
                                                color = if (selectedModels.contains(model)) Color.White else Color.White
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .border(
                                                    2.dp,
                                                    if (selectedModels.contains(model)) Color(0xFF007ACC) else Color.Gray,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .background(
                                                    if (selectedModels.contains(model)) Color(0xFF007ACC) else Color.Transparent
                                                )
                                        ) {
                                            if (selectedModels.contains(model)) {
                                                Text(
                                                    text = "✓",
                                                    style = JewelTheme.defaultTextStyle.copy(
                                                        color = Color.White,
                                                        fontSize = 14.sp
                                                    ),
                                                    modifier = Modifier.align(Alignment.Center)
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
            
            // Timeout
            item {
                Column {
                    Text("超时时间 (秒):", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    androidx.compose.foundation.text.BasicTextField(
                        value = currentTimeout,
                        onValueChange = { 
                            val timeout = it.text.toIntOrNull() ?: 60
                            currentTimeout = TextFieldValue(timeout.toString())
                            settings.providers[currentProviderIndex].timeoutSeconds = timeout
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(12.dp)
                            .background(JewelTheme.globalColors.panelBackground)
                            .focusRequester(timeoutFocusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    currentTimeout = TextFieldValue(
                                        text = currentTimeout.text,
                                        selection = androidx.compose.ui.text.TextRange(currentTimeout.text.length)
                                    )
                                }
                            },
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        timeoutFocusRequester.requestFocus()
                                    }
                            ) {
                                if (currentTimeout.text.isEmpty()) {
                                    Text(
                                        text = "60",
                                        style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
            
            // Temperature
            item {
                Column {
                    Text("温度 (0.0-1.0):", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    androidx.compose.foundation.text.BasicTextField(
                        value = currentTemperature,
                        onValueChange = { 
                            val temperature = it.text.toDoubleOrNull() ?: 0.7
                            currentTemperature = TextFieldValue(temperature.toString())
                            settings.providers[currentProviderIndex].temperature = temperature
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(12.dp)
                            .background(JewelTheme.globalColors.panelBackground)
                            .focusRequester(temperatureFocusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    currentTemperature = TextFieldValue(
                                        text = currentTemperature.text,
                                        selection = androidx.compose.ui.text.TextRange(currentTemperature.text.length)
                                    )
                                }
                            },
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        temperatureFocusRequester.requestFocus()
                                    }
                            ) {
                                if (currentTemperature.text.isEmpty()) {
                                    Text(
                                        text = "0.7",
                                        style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
            
            // Top P
            item {
                Column {
                    Text("Top P (0.0-1.0):", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    androidx.compose.foundation.text.BasicTextField(
                        value = currentTopP,
                        onValueChange = { 
                            val topP = it.text.toDoubleOrNull() ?: 0.9
                            currentTopP = TextFieldValue(topP.toString())
                            settings.providers[currentProviderIndex].topP = topP
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(12.dp)
                            .background(JewelTheme.globalColors.panelBackground)
                            .focusRequester(topPFocusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    currentTopP = TextFieldValue(
                                        text = currentTopP.text,
                                        selection = androidx.compose.ui.text.TextRange(currentTopP.text.length)
                                    )
                                }
                            },
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        topPFocusRequester.requestFocus()
                                    }
                            ) {
                                if (currentTopP.text.isEmpty()) {
                                    Text(
                                        text = "0.9",
                                        style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Test Connection Button
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isTesting = true
                                testResult = null
                                val success = aiService.testConnection(settings.providers[currentProviderIndex])
                                testResult = success
                                isTesting = false
                                
                                val notificationType = if (success) NotificationType.INFORMATION else NotificationType.ERROR
                                val message = if (success) "连接成功！" else "连接失败，请检查配置"
                                
                                NotificationGroupManager.getInstance()
                                    .getNotificationGroup("AI Agent Notifications")
                                    .createNotification(message, notificationType)
                                    .notify(null)
                            }
                        },
                        enabled = !isTesting
                    ) {
                        Text(if (isTesting) "测试中..." else "测试连接")
                    }
                    
                    if (testResult != null) {
                        val resultColor = if (testResult == true) Color(0xFF4CAF50) else Color(0xFFF44336)
                        Text(
                            if (testResult == true) "✓ 连接成功" else "✗ 连接失败",
                            style = JewelTheme.defaultTextStyle.copy(color = resultColor)
                        )
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Save Button
        item {
            OutlinedButton(
                onClick = {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("AI Agent Notifications")
                        .createNotification("配置已保存", NotificationType.INFORMATION)
                        .notify(null)
                    
                    onSettingsSaved()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存配置")
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        // Help Text
        item {
            Text(
                "提示：本插件支持多个模型Provider，每个Provider可以配置不同的API类型和模型\n常用模型：llama2, codellama, qwen2.5-coder, deepseek-coder 等",
                style = JewelTheme.defaultTextStyle.copy(color = Color.White)
            )
        }
    }
}

@Composable
private fun ClickableTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    var isHovered by remember { mutableStateOf(false) }
    
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.HoverInteraction.Enter -> isHovered = true
                is androidx.compose.foundation.interaction.HoverInteraction.Exit -> isHovered = false
            }
        }
    }
    
    val backgroundColor = if (isHovered) {
        Color(0xFFE3F2FD)
    } else {
        Color.Transparent
    }
    
    val borderColor = if (isHovered) {
        Color(0xFF2196F3)
    } else {
        Color.Gray
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(8.dp)
            .clickable(
                enabled = true,
                onClick = {
                    isEditing = true
                },
                interactionSource = interactionSource,
                indication = null
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                style = JewelTheme.defaultTextStyle
            )
            Text(
                text = "✏️",
                style = JewelTheme.defaultTextStyle
            )
        }
    }
    
    // 显示编辑对话框
    if (isEditing) {
        EditDialog(
            currentValue = value,
            placeholder = placeholder,
            onConfirm = { newValue ->
                onValueChange(newValue)
                isEditing = false
            },
            onDismiss = {
                isEditing = false
            }
        )
    }
}

@Composable
private fun EditDialog(
    currentValue: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inputValue by remember { mutableStateOf(currentValue) }
    
    Dialog(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .background(JewelTheme.globalColors.panelBackground, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "请输入值:",
                style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, color = Color.White),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            androidx.compose.foundation.text.BasicTextField(
                value = inputValue,
                onValueChange = { inputValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                    .padding(12.dp)
                    .background(JewelTheme.globalColors.panelBackground),
                singleLine = true,
                textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                decorationBox = { innerTextField ->
                    if (inputValue.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                        )
                    }
                    innerTextField()
                }
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                
                OutlinedButton(
                    onClick = { onConfirm(inputValue) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确定")
                }
            }
        }
    }
}
