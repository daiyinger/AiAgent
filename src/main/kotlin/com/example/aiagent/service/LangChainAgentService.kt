package com.example.aiagent.service

import com.example.aiagent.settings.AiAgentSettings
import com.example.aiagent.tools.*
import com.example.aiagent.ui.ToolCallMessage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.service.AiServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * 使用LangChain4j实现的AI Agent服务
 * 提供更稳定的工具调用和流式响应处理
 */
class LangChainAgentService(private val project: Project) {
    
    private fun log(message: String) {
        LogService.log(message)
    }
    
    /**
     * LangChain4j Agent接口
     * 定义agent可以调用的工具
     */
    interface Agent {
        fun chat(message: String): String
    }
    
    /**
     * 工具类包装器，将现有工具转换为LangChain4j工具
     */
    class ToolWrapper(
        private val project: Project, 
        private val onToolCall: (String, String, String) -> Unit,
        private val onToolOutput: ((String, String) -> Unit)? = null
    ) {
        
        @Tool("List files and directories in a given path. Supports filtering by file extension.")
        fun listFiles(
            path: String = "",
            recursive: Boolean = false,
            extension: String? = null
        ): String {
            val params = mutableMapOf<String, Any>()
            if (path.isNotEmpty()) params["path"] = path
            params["recursive"] = recursive
            if (extension != null) params["extension"] = extension
            
            onToolCall("listFiles", params.toString(), "执行中...")
            
            val result = runBlocking {
                ListFilesTool().execute(project, params)
            }
            
            val response = when (result) {
                is ToolResult.Success -> {
                    onToolCall("listFiles", params.toString(), "成功")
                    "Success: ${result.data}"
                }
                is ToolResult.Error -> {
                    onToolCall("listFiles", params.toString(), "失败: ${result.message}")
                    "Failed: ${result.message}"
                }
                is ToolResult.Progress -> {
                    onToolCall("listFiles", params.toString(), "进行中: ${result.message}")
                    "Progress: ${result.message}"
                }
                is ToolResult.OutputUpdate -> {
                    "Output: ${result.output}"
                }
            }
            return response
        }
        
        @Tool("Read the content of a file at the given path")
        fun readFile(path: String): String {
            val params = mapOf("path" to path)
            
            onToolCall("readFile", params.toString(), "执行中...")
            
            val result = runBlocking {
                ReadFileTool().execute(project, params)
            }
            
            val response = when (result) {
                is ToolResult.Success -> {
                    onToolCall("readFile", params.toString(), "成功")
                    "Success: ${result.data}"
                }
                is ToolResult.Error -> {
                    onToolCall("readFile", params.toString(), "失败: ${result.message}")
                    "Failed: ${result.message}"
                }
                is ToolResult.Progress -> {
                    onToolCall("readFile", params.toString(), "进行中: ${result.message}")
                    "Progress: ${result.message}"
                }
                is ToolResult.OutputUpdate -> {
                    "Output: ${result.output}"
                }
            }
            return response
        }
        
        @Tool("Edit a file by replacing old content with new content")
        fun editFile(path: String, old_text: String, new_text: String): String {
            val params = mapOf(
                "path" to path,
                "old_text" to old_text,
                "new_text" to new_text
            )
            
            onToolCall("editFile", params.toString(), "执行中...")
            
            val result = runBlocking {
                EditFileTool().execute(project, params)
            }
            
            val response = when (result) {
                is ToolResult.Success -> {
                    onToolCall("editFile", params.toString(), "成功")
                    "Success: ${result.data}"
                }
                is ToolResult.Error -> {
                    onToolCall("editFile", params.toString(), "失败: ${result.message}")
                    "Failed: ${result.message}"
                }
                is ToolResult.Progress -> {
                    onToolCall("editFile", params.toString(), "进行中: ${result.message}")
                    "Progress: ${result.message}"
                }
                is ToolResult.OutputUpdate -> {
                    "Output: ${result.output}"
                }
            }
            return response
        }
        
        @Tool("Search for files in the project by name or pattern")
        fun searchFiles(pattern: String, maxResults: Int = 20): String {
            val params = mutableMapOf<String, Any>("pattern" to pattern)
            if (maxResults > 0) params["max_results"] = maxResults
            
            onToolCall("searchFiles", params.toString(), "执行中...")
            
            val result = runBlocking {
                SearchFilesTool().execute(project, params)
            }
            
            val response = when (result) {
                is ToolResult.Success -> {
                    onToolCall("searchFiles", params.toString(), "成功")
                    "Success: ${result.data}"
                }
                is ToolResult.Error -> {
                    onToolCall("searchFiles", params.toString(), "失败: ${result.message}")
                    "Failed: ${result.message}"
                }
                is ToolResult.Progress -> {
                    onToolCall("searchFiles", params.toString(), "进行中: ${result.message}")
                    "Progress: ${result.message}"
                }
                is ToolResult.OutputUpdate -> {
                    "Output: ${result.output}"
                }
            }
            return response
        }
        
        @Tool("Analyze the current Android project structure and provide insights")
        fun analyzeProject(): String {
            val params = emptyMap<String, Any>()
            
            onToolCall("analyzeProject", params.toString(), "执行中...")
            
            val result = runBlocking {
                AndroidProjectAnalysisTool().execute(project, params)
            }
            
            val response = when (result) {
                is ToolResult.Success -> {
                    onToolCall("analyzeProject", params.toString(), "成功")
                    "Success: ${result.data}"
                }
                is ToolResult.Error -> {
                    onToolCall("analyzeProject", params.toString(), "失败: ${result.message}")
                    "Failed: ${result.message}"
                }
                is ToolResult.Progress -> {
                    onToolCall("analyzeProject", params.toString(), "进行中: ${result.message}")
                    "Progress: ${result.message}"
                }
                is ToolResult.OutputUpdate -> {
                    "Output: ${result.output}"
                }
            }
            return response
        }
        
        @Tool("Compile the Android project")
        fun compileProject(mode: String = "build"): String {
            val params = mapOf("mode" to mode)
            
            onToolCall("compileProject", params.toString(), "执行中...")
            
            val result = runBlocking {
                CompileProjectTool().execute(project, params) { output ->
                    onToolOutput?.invoke("compileProject", output)
                }
            }
            
            val response = when (result) {
                is ToolResult.Success -> {
                    onToolCall("compileProject", params.toString(), "成功")
                    "Success: ${result.data}"
                }
                is ToolResult.Error -> {
                    onToolCall("compileProject", params.toString(), "失败: ${result.message}")
                    "Failed: ${result.message}"
                }
                is ToolResult.Progress -> {
                    onToolCall("compileProject", params.toString(), "进行中: ${result.message}")
                    "Progress: ${result.message}"
                }
                is ToolResult.OutputUpdate -> {
                    "Output: ${result.output}"
                }
            }
            return response
        }
    }
    
    /**
     * 创建ChatLanguageModel
     */
    private fun createChatModel(): ChatLanguageModel {
        val settings = AiAgentSettings.instance.state
        val currentProvider = settings.providers.find { it.id == settings.currentProviderId } 
            ?: settings.providers.firstOrNull() 
            ?: throw IllegalStateException("No provider configured")
        
        log("创建ChatModel: provider=${currentProvider.name}, model=${settings.currentModel}")
        
        return when (currentProvider.apiType) {
            "openai" -> {
                val baseUrl = currentProvider.apiUrl.trimEnd('/')
                val finalUrl = if (baseUrl.endsWith("/v1")) {
                    baseUrl
                } else if (baseUrl.endsWith("/v1/")) {
                    baseUrl.trimEnd('/')
                } else {
                    "$baseUrl/v1"
                }
                log("OpenAI baseUrl: $finalUrl")
                
                val builder = OpenAiChatModel.builder()
                    .baseUrl(finalUrl)
                    .apiKey(currentProvider.apiKey)
                    .modelName(settings.currentModel)
                    .timeout(java.time.Duration.ofSeconds(currentProvider.timeoutSeconds.toLong()))
                
                if (!settings.currentModel.contains("deepseek")) {
                    builder.temperature(currentProvider.temperature)
                    builder.topP(currentProvider.topP)
                }
                
                builder.build()
            }
            "ollama" -> {
                val baseUrl = currentProvider.apiUrl.trimEnd('/')
                log("Ollama baseUrl: $baseUrl")
                
                OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(settings.currentModel)
                    .temperature(currentProvider.temperature)
                    .topP(currentProvider.topP)
                    .timeout(java.time.Duration.ofSeconds(currentProvider.timeoutSeconds.toLong()))
                    .build()
            }
            else -> {
                throw IllegalArgumentException("Unsupported API type: ${currentProvider.apiType}")
            }
        }
    }
    
    /**
     * 构建Agent
     */
    private fun buildAgent(
        onToolCall: (String, String, String) -> Unit,
        onToolOutput: ((String, String) -> Unit)? = null
    ): Agent {
        log("构建LangChain4j Agent")
        
        val chatModel = createChatModel()
        val toolWrapper = ToolWrapper(project, onToolCall, onToolOutput)
        
        return AiServices.builder(Agent::class.java)
            .chatLanguageModel(chatModel)
            .tools(toolWrapper)
            .build()
    }
    
    /**
     * 发送消息并获取响应
     */
    suspend fun sendMessage(
        message: String,
        onChunk: (String) -> Unit,
        onToolCall: (ToolCallMessage) -> Unit,
        onTokenUsage: (Int, Int) -> Unit = { _, _ -> },
        onToolOutput: ((String, String) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            log("发送消息: ${message.take(100)}...")
            
            // 检查缓存
            val cachedResponse = checkCache(message)
            if (cachedResponse != null) {
                log("使用缓存的响应")
                // 估算token数
                val estimatedInputTokens = estimateTokenCount(message)
                val estimatedOutputTokens = estimateTokenCount(cachedResponse)
                
                // 通知token使用情况
                ApplicationManager.getApplication().invokeLater {
                    onTokenUsage(estimatedInputTokens, estimatedOutputTokens)
                }
                
                // 流式发送缓存的响应
                ApplicationManager.getApplication().invokeLater {
                    cachedResponse.forEach { char ->
                        Thread.sleep(5)
                        onChunk(char.toString())
                    }
                }
                return@withContext Result.success(Unit)
            }
            
            // 构建系统提示
            val systemPrompt = buildSystemPrompt()
            
            // 工具调用ID映射，用于跟踪正在执行的工具调用
            val toolCallIds = mutableMapOf<String, String>()
            
            // 创建工具调用回调
            val toolCallCallback: (String, String, String) -> Unit = { toolName, paramsStr, status ->
                log("工具调用: $toolName, 参数: $paramsStr, 状态: $status")
                ApplicationManager.getApplication().invokeLater {
                    // 解析参数字符串，构建参数映射
                    val parameters = mutableMapOf<String, Any>()
                    try {
                        // 移除首尾的大括号
                        val cleanedParamsStr = paramsStr.trim().removePrefix("{")
                            .removeSuffix("}")
                        if (cleanedParamsStr.isNotEmpty()) {
                            // 分割参数对
                            val paramPairs = cleanedParamsStr.split(",")
                            for (pair in paramPairs) {
                                val parts = pair.split("=", limit = 2)
                                if (parts.size == 2) {
                                    val key = parts[0].trim()
                                    val value = parts[1].trim()
                                    parameters[key] = value
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 如果解析失败，使用原始参数字符串
                        parameters["params"] = paramsStr
                    }
                    
                    // 生成或获取工具调用ID
                    val toolCallKey = "$toolName-${paramsStr.take(50)}" // 使用工具名和参数的前50个字符作为键
                    val toolCallId = toolCallIds.getOrPut(toolCallKey) { System.currentTimeMillis().toString() }
                    
                    // 查找是否已经存在该工具调用消息，以保留之前的输出
                    var existingOutput = ""
                    // 这里暂时无法直接访问 UI 中的消息列表，所以我们依赖 chatStateService 来保存输出
                    
                    val toolCallMessage = ToolCallMessage(
                        id = toolCallId,
                        toolName = toolName,
                        parameters = parameters,
                        timestamp = LocalDateTime.now(),
                        isExecuting = status == "执行中..." || status.startsWith("进行中:"),
                        result = if (status == "成功") "成功" else if (status.startsWith("失败:")) status.substring(3) else null,
                        output = existingOutput
                    )
                    onToolCall(toolCallMessage)
                    
                    // 如果工具调用完成或失败，从映射中移除，这样下次相同工具调用会创建新的框
                    if (status == "成功" || status.startsWith("失败:")) {
                        toolCallIds.remove(toolCallKey)
                    }
                }
            }
            
            val agent = buildAgent(toolCallCallback, onToolOutput)
            
            // 获取对话历史
            val chatStateService = ChatStateService.instance
            val history = chatStateService.currentSession?.messages ?: emptyList()
            
            log("获取到 ${history.size} 条历史消息")
            history.forEachIndexed { index, msg ->
                log("历史消息 $index: type=${msg.type}, content=${msg.content.take(50)}...")
            }
            
            // 构建对话历史文本
            val historyText = buildString {
                history.forEach { msg ->
                    when (msg.type) {
                        "user" -> appendLine("用户: ${msg.content}")
                        "ai" -> appendLine("AI: ${msg.content}")
                        "tool" -> {
                            val resultSummary = when {
                                msg.result == "成功" || msg.result?.startsWith("Success") == true -> "成功"
                                msg.result == "失败" || msg.result?.startsWith("Error") == true -> "失败"
                                else -> "执行完成"
                            }
                            appendLine("工具: ${msg.toolName} ($resultSummary)")
                        }
                    }
                }
            }
            
            log("构建的对话历史: ${historyText.take(200)}...")
            
            // 构建完整消息
            val fullMessage = if (historyText.isNotEmpty()) {
                "$systemPrompt\n\n对话历史:\n$historyText\n\n用户: $message"
            } else {
                "$systemPrompt\n\n用户: $message"
            }
            
            // 估算输入token数
            val estimatedInputTokens = estimateTokenCount(fullMessage)
            log("估算输入token数: $estimatedInputTokens")
            
            // 发送消息并获取响应
            val response = agent.chat(fullMessage)
            
            // 添加到缓存
            addToCache(message, response)
            
            // 估算输出token数
            val estimatedOutputTokens = estimateTokenCount(response)
            log("估算输出token数: $estimatedOutputTokens")
            
            // 通知token使用情况
            ApplicationManager.getApplication().invokeLater {
                onTokenUsage(estimatedInputTokens, estimatedOutputTokens)
            }
            
            log("收到响应: ${response.take(100)}...")
            
            // 发送响应（模拟流式）
            ApplicationManager.getApplication().invokeLater {
                // 模拟流式发送，每次发送一个字符
                response.forEach { char ->
                    Thread.sleep(5) // 模拟网络延迟，使用更短的延迟
                    onChunk(char.toString())
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            log("发送消息错误: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }
    
    /**
     * 估算token数（基于简单的字符计数方法）
     */
    private fun estimateTokenCount(text: String): Int {
        // 简单估算：1个token约等于4个字符
        // 实际情况会更复杂，但这是一个合理的近似
        return (text.length / 4) + 1
    }
    
    /**
     * 构建系统提示
     */
    private fun buildSystemPrompt(): String {
        return """
            你是Android Studio工程分析专家，擅长：
            1. 分析工程：用list_files查看结构，read_file读取关键文件，提供分析报告
            2. 添加功能：分析需求，search_files查找相关代码，edit_file修改文件
            3. 修改功能：查找相关代码，分析实现，进行修改
            
            处理流程：
            - 先思考，明确需求和步骤
            - 使用工具时确保路径正确（相对路径）
            - 修改文件前先读取内容
            - 提供清晰的操作说明
            
            工具：
            - listFiles(path, recursive, extension): 列出文件
            - readFile(path): 读取文件内容
            - editFile(path, oldContent, newContent): 编辑文件
            - searchFiles(pattern, maxResults): 搜索文件
            - analyzeProject(): 分析项目结构
            - compileProject(mode): 编译项目，mode可选值：build（完整构建）、assemble（仅组装）、clean（清理构建）
            
            重要提示：
            - 请记住对话历史，保持上下文的连贯性
            - 当用户询问之前的问题时，请参考对话历史回答
            
            请简洁高效地完成任务，减少不必要的描述。
        """.trimIndent()
    }
    
    /**
     * 消息缓存，避免重复处理相同的请求
     */
    private val messageCache = mutableMapOf<String, String>()
    
    /**
     * 检查消息是否在缓存中
     */
    private fun checkCache(message: String): String? {
        return messageCache[message]
    }
    
    /**
     * 将消息和响应添加到缓存
     */
    private fun addToCache(message: String, response: String) {
        // 只缓存短消息，避免内存占用过大
        if (message.length < 200) {
            messageCache[message] = response
        }
    }
    
    /**
     * 测试连接
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            log("测试连接...")
            
            val chatModel = createChatModel()
            val response = chatModel.generate("Hello")
            
            log("测试连接成功: ${response.take(50)}...")
            true
        } catch (e: Exception) {
            log("测试连接失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}