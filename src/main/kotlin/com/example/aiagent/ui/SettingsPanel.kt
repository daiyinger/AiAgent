package com.example.aiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var selectedProviderId by remember { mutableStateOf(settings.currentProviderId) }
    
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    
    var settingsClickCount by remember { mutableStateOf(0) }
    var lastSettingsClickTime by remember { mutableStateOf(0L) }
    var showLogToast by remember { mutableStateOf<String?>(null) }
    
    var currentProviderName by remember { mutableStateOf(TextFieldValue("")) }
    var currentApiType by remember { mutableStateOf("") }
    var currentApiUrl by remember { mutableStateOf(TextFieldValue("")) }
    var currentApiKey by remember { mutableStateOf(TextFieldValue("")) }
    var selectedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentTimeout by remember { mutableStateOf(TextFieldValue("60")) }
    var currentTemperature by remember { mutableStateOf(TextFieldValue("0.7")) }
    var currentTopP by remember { mutableStateOf(TextFieldValue("0.9")) }
    var currentContextLength by remember { mutableStateOf(TextFieldValue("32768")) }
    
    val providerNameFocusRequester = remember { FocusRequester() }
    val apiUrlFocusRequester = remember { FocusRequester() }
    val apiKeyFocusRequester = remember { FocusRequester() }
    val timeoutFocusRequester = remember { FocusRequester() }
    val temperatureFocusRequester = remember { FocusRequester() }
    val topPFocusRequester = remember { FocusRequester() }
    val contextLengthFocusRequester = remember { FocusRequester() }
    
    val scope = rememberCoroutineScope()
    val aiService = remember { AiAgentService() }
    
    LaunchedEffect(currentProviderIndex) {
        if (settings.providers.isNotEmpty()) {
            val provider = settings.providers[currentProviderIndex]
            currentProviderName = TextFieldValue(provider.name)
            currentApiType = provider.apiType
            currentApiUrl = TextFieldValue(provider.apiUrl)
            currentApiKey = TextFieldValue(provider.apiKey)
            selectedModels = provider.selectedModels
            currentTimeout = TextFieldValue(provider.timeoutSeconds.toString())
            currentTemperature = TextFieldValue(provider.temperature.toString())
            currentTopP = TextFieldValue(provider.topP.toString())
            currentContextLength = TextFieldValue(provider.contextLength.toString())
        }
    }
    
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                        style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, color = Color.White),
                        modifier = Modifier.clickable {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastSettingsClickTime < 500) {
                                settingsClickCount++
                                if (settingsClickCount >= 2) {
                                    settings.enableLogging = !settings.enableLogging
                                    settingsClickCount = 0
                                }
                            } else {
                                settingsClickCount = 0
                            }
                            lastSettingsClickTime = currentTime
                        }
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
                            apiUrl = "http://localhost:11434"
                        )
                        settings.providers.add(newProvider)
                        currentProviderIndex = settings.providers.size - 1
                        selectedProviderId = newProvider.id
                        settings.currentProviderId = newProvider.id
                    }
                ) {
                    Text("添加Provider")
                }
            }
        }
        
        item {
            Column {
                Text("选择Provider:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                        .background(JewelTheme.globalColors.panelBackground)
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(settings.providers) { provider ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(2.dp)
                                    .background(
                                        if (provider.id == selectedProviderId) Color(0xFF007ACC) else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (provider.id == selectedProviderId) Color(0xFF007ACC) else Color.Transparent,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable {
                                        val index = settings.providers.indexOf(provider)
                                        if (index >= 0) {
                                            currentProviderIndex = index
                                            selectedProviderId = provider.id
                                            settings.currentProviderId = provider.id
                                            if (provider.selectedModels.isNotEmpty()) {
                                                settings.currentModel = provider.selectedModels[0]
                                            }
                                        }
                                    }
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        provider.name,
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = if (provider.id == selectedProviderId) Color.White else Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        provider.apiUrl,
                                        style = JewelTheme.defaultTextStyle.copy(
                                            color = if (provider.id == selectedProviderId) Color.White.copy(alpha = 0.7f) else Color.LightGray,
                                            fontSize = 11.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable {
                                            if (settings.providers.size > 1) {
                                                settings.providers.remove(provider)
                                                currentProviderIndex = 0
                                                selectedProviderId = settings.providers[0].id
                                                settings.currentProviderId = settings.providers[0].id
                                                if (settings.providers[0].selectedModels.isNotEmpty()) {
                                                    settings.currentModel = settings.providers[0].selectedModels[0]
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
                            .padding(horizontal = 8.dp, vertical = 6.dp)
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
                        cursorBrush = SolidColor(Color.White),
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
            
            item {
                Column {
                    Text("API 类型:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    var apiTypeExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { apiTypeExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentApiType.uppercase(),
                                    style = JewelTheme.defaultTextStyle.copy(color = Color.White)
                                )
                                Text(
                                    text = "▼",
                                    style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                )
                            }
                        }
                        
                        if (apiTypeExpanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                    .background(JewelTheme.globalColors.panelBackground)
                                    .padding(4.dp)
                            ) {
                                Column {
                                    listOf("ollama", "openai").forEach { type ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (currentApiType == type) Color(0xFF007ACC).copy(alpha = 0.3f) else Color.Transparent,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .clickable {
                                                    currentApiType = type
                                                    settings.providers[currentProviderIndex].apiType = type
                                                    val currentUrl = currentApiUrl.text.trimEnd('/')
                                                    val ollamaDefault = "http://localhost:11434"
                                                    val openaiDefault = "https://api.openai.com"
                                                    if (type == "ollama" && currentUrl == openaiDefault) {
                                                        currentApiUrl = TextFieldValue(ollamaDefault)
                                                        settings.providers[currentProviderIndex].apiUrl = ollamaDefault
                                                    } else if (type == "openai" && currentUrl == ollamaDefault) {
                                                        currentApiUrl = TextFieldValue(openaiDefault)
                                                        settings.providers[currentProviderIndex].apiUrl = openaiDefault
                                                    }
                                                    apiTypeExpanded = false
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = type.uppercase(),
                                                style = JewelTheme.defaultTextStyle.copy(
                                                    color = if (currentApiType == type) Color(0xFF007ACC) else Color.White
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
            
            item {
                Column {
                    Text("API URL:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    androidx.compose.foundation.text.BasicTextField(
                        value = currentApiUrl,
                        onValueChange = { 
                            currentApiUrl = it 
                            settings.providers[currentProviderIndex].apiUrl = it.text 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .background(JewelTheme.globalColors.panelBackground)
                            .focusRequester(apiUrlFocusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    currentApiUrl = TextFieldValue(
                                        text = currentApiUrl.text,
                                        selection = androidx.compose.ui.text.TextRange(currentApiUrl.text.length)
                                    )
                                }
                            },
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        apiUrlFocusRequester.requestFocus()
                                    }
                            ) {
                                if (currentApiUrl.text.isEmpty()) {
                                    Text(
                                        text = if (currentApiType == "openai") "https://api.openai.com" else "http://localhost:11434",
                                        style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Text(
                        text = if (currentApiType == "ollama") "示例: http://localhost:11434" else "示例: https://api.openai.com (或自定义兼容端点)",
                        style = JewelTheme.defaultTextStyle.copy(color = Color.Gray, fontSize = 11.sp)
                    )
                }
            }
            
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
                            .padding(horizontal = 8.dp, vertical = 6.dp)
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
                        cursorBrush = SolidColor(Color.White),
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
                                        text = if (currentApiType == "openai") "sk-..." else "Ollama 通常不需要 API Key",
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
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("选择模型:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isLoadingModels = true
                                    availableModels = aiService.getAvailableModels(settings.providers[currentProviderIndex]).getOrDefault(emptyList())
                                    isLoadingModels = false
                                    
                                    if (availableModels.isEmpty()) {
                                        NotificationGroupManager.getInstance()
                                            .getNotificationGroup("AI Agent Notifications")
                                            .createNotification("无法获取模型列表，请检查API配置", NotificationType.WARNING)
                                            .notify(null)
                                    }
                                }
                            },
                            enabled = !isLoadingModels
                        ) {
                            Text(if (isLoadingModels) "加载中..." else "刷新模型列表")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (selectedModels.isNotEmpty() || availableModels.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .background(JewelTheme.globalColors.panelBackground)
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                val modelsToShow = if (availableModels.isNotEmpty()) availableModels else selectedModels
                                items(modelsToShow) { model ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(4.dp)
                                            .clickable {
                                                val newSelectedModels = selectedModels.toMutableList()
                                                if (newSelectedModels.contains(model)) {
                                                    newSelectedModels.remove(model)
                                                } else {
                                                    newSelectedModels.add(model)
                                                }
                                                selectedModels = newSelectedModels
                                                settings.providers[currentProviderIndex].selectedModels = newSelectedModels.toMutableList()
                                                if (newSelectedModels.isNotEmpty() && !newSelectedModels.contains(settings.currentModel)) {
                                                    settings.currentModel = newSelectedModels[0]
                                                }
                                            }
                                            .background(
                                                if (selectedModels.contains(model)) Color(0xFF007ACC).copy(alpha = 0.3f) else Color.Transparent,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .border(
                                                    1.dp,
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
                                                        fontSize = 12.sp
                                                    ),
                                                    modifier = Modifier.align(Alignment.Center)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            model,
                                            style = JewelTheme.defaultTextStyle.copy(color = Color.White)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .background(JewelTheme.globalColors.panelBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "点击\"刷新模型列表\"获取可用模型",
                                style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                            )
                        }
                    }
                }
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("超时(秒):", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
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
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(timeoutFocusRequester),
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White)
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("温度:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = currentTemperature,
                            onValueChange = { 
                                val temperature = it.text.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.7
                                currentTemperature = TextFieldValue(temperature.toString())
                                settings.providers[currentProviderIndex].temperature = temperature
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(temperatureFocusRequester),
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White)
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Top P:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = currentTopP,
                            onValueChange = { 
                                val topP = it.text.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.9
                                currentTopP = TextFieldValue(topP.toString())
                                settings.providers[currentProviderIndex].topP = topP
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(topPFocusRequester),
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White)
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("上下文长度:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = currentContextLength,
                            onValueChange = { 
                                val contextLength = it.text.toIntOrNull()?.coerceAtLeast(512) ?: 32768
                                currentContextLength = TextFieldValue(contextLength.toString())
                                settings.providers[currentProviderIndex].contextLength = contextLength
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(contextLengthFocusRequester),
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White)
                        )
                    }
                }
            }
            
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
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        item {
            Text(
                "提示：设置会在关闭对话框时自动保存。支持多个Provider配置。",
                style = JewelTheme.defaultTextStyle.copy(color = Color.Gray, fontSize = 11.sp)
            )
        }
    }
}
