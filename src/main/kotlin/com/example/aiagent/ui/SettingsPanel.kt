package com.example.aiagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    
    val providerListState = rememberLazyListState()
    
    val scope = rememberCoroutineScope()
    val aiService = remember { AiAgentService() }
    
    LaunchedEffect(Unit) {
        if (settings.providers.isNotEmpty() && currentProviderIndex >= 0) {
            providerListState.scrollToItem(currentProviderIndex)
        }
    }
    
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
                    Box {
                        Text(
                            text = "⚙️ ",
                            style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Bold, color = Color.White),
                            modifier = Modifier.clickable {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastSettingsClickTime < 500) {
                                    settingsClickCount++
                                    if (settingsClickCount >= 2) {
                                        settings.enableLogging = !settings.enableLogging
                                        showLogToast = if (settings.enableLogging) "日志已开启" else "日志已关闭"
                                        settingsClickCount = 0
                                    }
                                } else {
                                    settingsClickCount = 0
                                }
                                lastSettingsClickTime = currentTime
                            }
                        )
                        
                        if (showLogToast != null) {
                            LaunchedEffect(showLogToast) {
                                kotlinx.coroutines.delay(2000)
                                showLogToast = null
                            }
                            Box(
                                modifier = Modifier
                                    .absoluteOffset(y = 24.dp)
                                    .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = showLogToast!!,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        }
                    }
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
                        state = providerListState,
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(settings.providers) { provider ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(2.dp)
                                    .background(
                                        if (provider.id == selectedProviderId) Color(0xFF3A6B99) else Color.Transparent
                                    )
                                    .border(
                                        1.dp,
                                        if (provider.id == selectedProviderId) Color(0xFF3A6B99) else Color.Transparent,
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
                            .focusRequester(providerNameFocusRequester),
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                    text = if (currentApiType == "openai") "OpenAI" else currentApiType.replaceFirstChar { it.uppercase() },
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
                                                text = if (type == "openai") "OpenAI" else type.replaceFirstChar { it.uppercase() },
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
                            .focusRequester(apiUrlFocusRequester),
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                            .focusRequester(apiKeyFocusRequester),
                        singleLine = true,
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                .heightIn(max = 120.dp)
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .background(JewelTheme.globalColors.panelBackground)
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                val modelsToShow = if (availableModels.isNotEmpty()) availableModels else selectedModels
                                items(modelsToShow) { model ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
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
                                                if (selectedModels.contains(model)) Color(0xFF3A6B99).copy(alpha = 0.3f) else Color.Transparent,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .border(
                                                    1.dp,
                                                    if (selectedModels.contains(model)) Color(0xFF3A6B99) else Color.Gray,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .background(
                                                    if (selectedModels.contains(model)) Color(0xFF3A6B99) else Color.Transparent
                                                )
                                        ) {
                                            if (selectedModels.contains(model)) {
                                                Text(
                                                    text = "✓",
                                                    style = JewelTheme.defaultTextStyle.copy(
                                                        color = Color.White,
                                                        fontSize = 10.sp
                                                    ),
                                                    modifier = Modifier.align(Alignment.Center)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
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
                                if (it.text.isEmpty()) {
                                    currentTimeout = TextFieldValue("", selection = it.selection)
                                } else {
                                    val timeout = it.text.toIntOrNull() ?: 60
                                    currentTimeout = TextFieldValue(it.text, selection = it.selection)
                                    settings.providers[currentProviderIndex].timeoutSeconds = timeout
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(timeoutFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused && currentTimeout.text.isEmpty()) {
                                        currentTimeout = TextFieldValue("60")
                                        settings.providers[currentProviderIndex].timeoutSeconds = 60
                                    }
                                },
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                ) {
                                    if (currentTimeout.text.isEmpty()) {
                                        Text(
                                            text = "默认: 60",
                                            style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("温度:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = currentTemperature,
                            onValueChange = { 
                                if (it.text.isEmpty()) {
                                    currentTemperature = TextFieldValue("", selection = it.selection)
                                } else {
                                    val temperature = it.text.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.7
                                    currentTemperature = TextFieldValue(it.text, selection = it.selection)
                                    settings.providers[currentProviderIndex].temperature = temperature
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(temperatureFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused && currentTemperature.text.isEmpty()) {
                                        currentTemperature = TextFieldValue("0.7")
                                        settings.providers[currentProviderIndex].temperature = 0.7
                                    }
                                },
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                ) {
                                    if (currentTemperature.text.isEmpty()) {
                                        Text(
                                            text = "默认: 0.7",
                                            style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Top P:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = currentTopP,
                            onValueChange = { 
                                if (it.text.isEmpty()) {
                                    currentTopP = TextFieldValue("", selection = it.selection)
                                } else {
                                    val topP = it.text.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.9
                                    currentTopP = TextFieldValue(it.text, selection = it.selection)
                                    settings.providers[currentProviderIndex].topP = topP
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(topPFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused && currentTopP.text.isEmpty()) {
                                        currentTopP = TextFieldValue("0.9")
                                        settings.providers[currentProviderIndex].topP = 0.9
                                    }
                                },
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                ) {
                                    if (currentTopP.text.isEmpty()) {
                                        Text(
                                            text = "默认: 0.9",
                                            style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("上下文长度:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = currentContextLength,
                            onValueChange = { 
                                if (it.text.isEmpty()) {
                                    currentContextLength = TextFieldValue("", selection = it.selection)
                                } else {
                                    val contextLength = it.text.toIntOrNull()?.coerceAtLeast(512) ?: 32768
                                    currentContextLength = TextFieldValue(it.text, selection = it.selection)
                                    settings.providers[currentProviderIndex].contextLength = contextLength
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground)
                                .focusRequester(contextLengthFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused && currentContextLength.text.isEmpty()) {
                                        currentContextLength = TextFieldValue("32768")
                                        settings.providers[currentProviderIndex].contextLength = 32768
                                    }
                                },
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                ) {
                                    if (currentContextLength.text.isEmpty()) {
                                        Text(
                                            text = "默认: 32768",
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
        
        item {
            Text(
                "系统提示词管理",
                style = JewelTheme.defaultTextStyle.copy(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        item {
            var newPromptName by remember { mutableStateOf(TextFieldValue("")) }
            var newPromptContent by remember { mutableStateOf(TextFieldValue("")) }
            var editingPromptId by remember { mutableStateOf<String?>(null) }
            var editingPromptName by remember { mutableStateOf(TextFieldValue("")) }
            var editingPromptContent by remember { mutableStateOf(TextFieldValue("")) }
            
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (settings.systemPrompts.isNotEmpty()) {
                    settings.systemPrompts.forEach { prompt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (prompt.id == settings.currentSystemPromptId) Color(0xFF3A6B99) else Color.Transparent
                                )
                                .border(
                                    1.dp,
                                    if (prompt.id == settings.currentSystemPromptId) Color(0xFF3A6B99) else Color.Gray,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    prompt.name,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = if (prompt.id == settings.currentSystemPromptId) Color.White else Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    prompt.content,
                                    style = JewelTheme.defaultTextStyle.copy(
                                        color = if (prompt.id == settings.currentSystemPromptId) Color.White.copy(alpha = 0.7f) else Color.LightGray,
                                        fontSize = 11.sp
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (editingPromptId == prompt.id) {
                                    OutlinedButton(
                                        onClick = {
                                            val promptIndex = settings.systemPrompts.indexOfFirst { it.id == prompt.id }
                                            if (promptIndex >= 0) {
                                                settings.systemPrompts[promptIndex].name = editingPromptName.text
                                                settings.systemPrompts[promptIndex].content = editingPromptContent.text
                                            }
                                            editingPromptId = null
                                        }
                                    ) {
                                        Text("保存", fontSize = 10.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            editingPromptId = null
                                        }
                                    ) {
                                        Text("取消", fontSize = 10.sp)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            settings.currentSystemPromptId = prompt.id
                                        }
                                    ) {
                                        Text("选择", fontSize = 10.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            editingPromptId = prompt.id
                                            editingPromptName = TextFieldValue(prompt.name)
                                            editingPromptContent = TextFieldValue(prompt.content)
                                        }
                                    ) {
                                        Text("编辑", fontSize = 10.sp)
                                    }
                                    if (!prompt.isDefault) {
                                        OutlinedButton(
                                            onClick = {
                                                settings.systemPrompts.removeIf { it.id == prompt.id }
                                                if (settings.currentSystemPromptId == prompt.id) {
                                                    settings.currentSystemPromptId = settings.systemPrompts.firstOrNull()?.id ?: ""
                                                }
                                            }
                                        ) {
                                            Text("删除", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                        
                        if (editingPromptId == prompt.id) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("名称:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                                androidx.compose.foundation.text.BasicTextField(
                                    value = editingPromptName,
                                    onValueChange = { editingPromptName = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .background(JewelTheme.globalColors.panelBackground),
                                    singleLine = true,
                                    textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                                    cursorBrush = SolidColor(Color.White)
                                )
                                
                                Text("内容:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                                androidx.compose.foundation.text.BasicTextField(
                                    value = editingPromptContent,
                                    onValueChange = { editingPromptContent = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .background(JewelTheme.globalColors.panelBackground),
                                    textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                                    cursorBrush = SolidColor(Color.White)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("添加新系统提示词:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("名称:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                        androidx.compose.foundation.text.BasicTextField(
                            value = newPromptName,
                            onValueChange = { newPromptName = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .background(JewelTheme.globalColors.panelBackground),
                            singleLine = true,
                            textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (newPromptName.text.isEmpty()) {
                                        Text(
                                            text = "输入提示词名称",
                                            style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
                
                Column {
                    Text("内容:", style = JewelTheme.defaultTextStyle.copy(color = Color.White))
                    androidx.compose.foundation.text.BasicTextField(
                        value = newPromptContent,
                        onValueChange = { newPromptContent = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .background(JewelTheme.globalColors.panelBackground),
                        textStyle = JewelTheme.defaultTextStyle.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (newPromptContent.text.isEmpty()) {
                                    Text(
                                        text = "输入系统提示词内容",
                                        style = JewelTheme.defaultTextStyle.copy(color = Color.LightGray)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
                
                OutlinedButton(
                    onClick = {
                        if (newPromptName.text.isNotEmpty() && newPromptContent.text.isNotEmpty()) {
                            val newPrompt = AiAgentSettings.SystemPrompt(
                                id = java.util.UUID.randomUUID().toString(),
                                name = newPromptName.text,
                                content = newPromptContent.text,
                                isDefault = false
                            )
                            settings.systemPrompts.add(newPrompt)
                            newPromptName = TextFieldValue("")
                            newPromptContent = TextFieldValue("")
                        }
                    },
                    enabled = newPromptName.text.isNotEmpty() && newPromptContent.text.isNotEmpty()
                ) {
                    Text("添加系统提示词")
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        item {
            Text(
                "提示：设置会在关闭对话框时自动保存。支持多个Provider配置和系统提示词管理。",
                style = JewelTheme.defaultTextStyle.copy(color = Color.Gray, fontSize = 11.sp)
            )
        }
    }
}
