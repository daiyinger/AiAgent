package com.example.aiagent.service

import com.example.aiagent.settings.AiAgentSettings
import com.example.aiagent.tools.ToolManager
import com.example.aiagent.tools.ToolResult
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import com.intellij.openapi.application.ApplicationManager

class AiAgentService {
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private fun log(message: String) {
        LogService.log(message)
    }
    
    private fun buildApiUrl(provider: AiAgentSettings.Provider, endpoint: String): String {
        val baseUrl = provider.apiUrl.trimEnd('/')
        return when (provider.apiType) {
            "ollama" -> {
                if (baseUrl.contains("/api/")) baseUrl else "$baseUrl$endpoint"
            }
            "openai" -> {
                if (baseUrl.contains("/v1/")) baseUrl else "$baseUrl$endpoint"
            }
            else -> "$baseUrl$endpoint"
        }
    }
    
    // 获取工具定义
    fun getToolDefinitions(): String {
        return ToolManager.getToolDefinitions()
    }
    
    // 解析工具调用
    fun parseToolCall(text: String): ToolCall? {
        try {
            // 尝试匹配标准格式: <tool_call><tool_name>name</tool_name><parameters>...</parameters></tool_call>
            // 允许标签格式有小错误，如缺少结束符号
            val standardToolCallRegex = Regex("""<tool_call>\s*<tool_name>(\w+)</tool_name>\s*(?:<parameters[^>]*>(.*?)(?:</parameters>|$)\s*)?(?:</tool_call[^>]*>|$)""", RegexOption.DOT_MATCHES_ALL)
            val standardMatch = standardToolCallRegex.find(text)
            
            if (standardMatch != null) {
                val toolName = standardMatch.groupValues[1].trim()
                val paramsText = standardMatch.groupValues[2].trim()
                
                val params = parseParameters(paramsText)
                return ToolCall(toolName, params)
            }
            
            // 尝试匹配AI生成的格式: <tool_call><tool name>name</tool name><parameters>...</parameters></tool_call>
            val aiToolCallRegex = Regex("""<tool_call>\s*<tool\s+name>(\w+)</tool\s+name>\s*(?:<parameters[^>]*>(.*?)(?:</parameters>|$)\s*)?(?:</tool_call[^>]*>|$)""", RegexOption.DOT_MATCHES_ALL)
            val aiMatch = aiToolCallRegex.find(text)
            
            if (aiMatch != null) {
                val toolName = aiMatch.groupValues[1].trim()
                val paramsText = aiMatch.groupValues[2].trim()
                
                val params = parseParameters(paramsText)
                return ToolCall(toolName, params)
            }
            
            // 尝试匹配AI生成的格式: <tool_call><read_file><parameters>...</parameters></read_file></tool_call>
            val aiToolCallRegex2 = Regex("""<tool_call>\s*<(\w+)>\s*(?:<parameters[^>]*>(.*?)(?:</parameters>|$)\s*)?</\1>\s*(?:</tool_call[^>]*>|$)""", RegexOption.DOT_MATCHES_ALL)
            val aiMatch2 = aiToolCallRegex2.find(text)
            
            if (aiMatch2 != null) {
                val toolName = aiMatch2.groupValues[1].trim()
                val paramsText = aiMatch2.groupValues[2].trim()
                
                val params = parseParameters(paramsText)
                return ToolCall(toolName, params)
            }
            
            // 尝试匹配AI生成的另一种格式: <tool_call><read_file><path>README.md</path></read_file></tool_call>
            val aiToolCallRegex3 = Regex("""<tool_call>\s*<(\w+)>(.*?)</\1>\s*(?:</tool_call[^>]*>|$)""", RegexOption.DOT_MATCHES_ALL)
            val aiMatch3 = aiToolCallRegex3.find(text)
            
            if (aiMatch3 != null) {
                val toolName = aiMatch3.groupValues[1].trim()
                val paramsText = aiMatch3.groupValues[2].trim()
                
                val params = parseParameters(paramsText)
                return ToolCall(toolName, params)
            }
            
            // 尝试匹配简化格式: <tool_call><tool_name>name</tool_name><path>path</path></tool_call>
            val simpleToolCallRegex = Regex("""<tool_call>\s*<tool_name>(\w+)</tool_name>\s*(.*?)\s*(?:</tool_call[^>]*>|$)""", RegexOption.DOT_MATCHES_ALL)
            val simpleMatch = simpleToolCallRegex.find(text)
            
            if (simpleMatch != null) {
                val toolName = simpleMatch.groupValues[1].trim()
                val paramsText = simpleMatch.groupValues[2].trim()
                
                val params = parseParameters(paramsText)
                return ToolCall(toolName, params)
            }
            
            // 尝试匹配更宽松的格式: <tool_call>list_files</toolparameters>
            val looseToolCallRegex = Regex("""<tool_call>\s*(\w+)\s*(.*?)\s*(?:</tool\w*>|$)""", RegexOption.DOT_MATCHES_ALL)
            val looseMatch = looseToolCallRegex.find(text)
            
            if (looseMatch != null) {
                val toolName = looseMatch.groupValues[1].trim()
                val paramsText = looseMatch.groupValues[2].trim()
                
                val params = parseParameters(paramsText)
                return ToolCall(toolName, params)
            }
            
            // 尝试匹配最宽松的格式: 提取tool_name和参数
            val veryLooseToolCallRegex = Regex("""<tool_call>.*?<tool[_\s]*name>?(\w+)</tool.*?>(.*?)(?:</tool_call|$)""", RegexOption.DOT_MATCHES_ALL)
            val veryLooseMatch = veryLooseToolCallRegex.find(text)
            
            if (veryLooseMatch != null) {
                val toolName = veryLooseMatch.groupValues[1].trim()
                val paramsText = veryLooseMatch.groupValues[2].trim()
                
                val params = parseParameters(paramsText)
                return ToolCall(toolName, params)
            }
        } catch (e: Exception) {
            log("解析工具调用失败: ${e.message}")
        }
        return null
    }
    
    // 解析参数
    private fun parseParameters(paramsText: String): MutableMap<String, Any> {
        val params = mutableMapOf<String, Any>()
        val paramRegex = Regex("""<(\w+)>(.*?)</\w+>""")
        paramRegex.findAll(paramsText).forEach { paramMatch ->
            val key = paramMatch.groupValues[1]
            var value = paramMatch.groupValues[2].trim()
            
            // 处理路径参数，修复路径格式问题
            if (key == "path") {
                // 修复路径中的空格问题，例如 "app/src/main AndroidManifest.xml" -> "app/src/main/AndroidManifest.xml"
                value = value.replace(" ", "/")
                // 确保路径使用正斜杠
                value = value.replace("\\", "/")
            }
            
            // 处理布尔类型参数
            if (key == "recursive") {
                params[key] = value.toBoolean()
            } else {
                params[key] = value
            }
        }
        return params
    }
    
    // 执行工具调用
    suspend fun executeToolCall(toolCall: ToolCall): ToolResult {
        return try {
            val project = ProjectManager.getInstance().openProjects.firstOrNull()
                ?: return ToolResult.Error("No open project found")
            
            log("执行工具: ${toolCall.toolName}, 参数: ${toolCall.parameters}")
            val result = ToolManager.executeTool(project, toolCall.toolName, toolCall.parameters)
            log("工具执行结果: $result")
            result
        } catch (e: Exception) {
            log("工具执行失败: ${e.message}")
            ToolResult.Error("Error executing tool: ${e.message}")
        }
    }
    
    // 构建包含工具定义的提示
    fun buildPromptWithTools(userMessage: String, history: List<ChatStateService.MessageState> = emptyList()): String {
        val toolDefinitions = getToolDefinitions()
        
        // 构建对话历史
        val historyText = buildString {
            history.forEach { message ->
                when (message.type) {
                    "user" -> appendLine("用户：${message.content}")
                    "ai" -> appendLine("AI：${message.content}")
                    "tool" -> {
                        // 工具调用历史只显示工具名称和结果状态，不显示详细内容
                        val resultSummary = when {
                            message.result == "成功" || message.result?.startsWith("Success") == true -> "成功"
                            message.result == "失败" || message.result?.startsWith("Error") == true -> "失败"
                            else -> "执行完成"
                        }
                        appendLine("工具：${message.toolName} ($resultSummary)")
                    }
                }
            }
        }
        
        return """
        你是一个Android Studio工程分析专家，专门帮助开发者分析、理解和优化Android项目。你可以使用以下工具来操作和分析Android工程：
        
        $toolDefinitions
        
        当你需要使用工具时，请按照以下格式调用：
        <tool_call>
        <tool_name>工具名称</tool_name>
        <parameters>
        <参数名>参数值</参数名>
        </parameters>
        </tool_call>
        
        对于Android工程分析，你应该：
        1. 首先使用list_files工具了解项目结构
        2. 查看build.gradle文件了解项目依赖和配置
        3. 分析关键源代码文件
        4. 提供有针对性的分析和建议
        
        重要提示：
        - 你可以调用多个工具来完成分析任务
        - 根据工具执行结果，继续调用其他工具或提供分析结果
        - 例如：先调用list_files查看项目结构，然后根据结果调用read_file查看关键文件
        - 不要只调用一个工具就停止，要持续分析直到完成用户的需求
        - 请记住之前的对话历史，保持上下文的连贯性
        
        具体任务处理流程：
        1. 当用户要求"分析工程"时：
           - 首先使用list_files工具查看项目的整体结构
           - 根据需要使用list_files工具的extension参数过滤特定类型的文件（如extension=kt查看Kotlin文件，extension=java查看Java文件，extension=gradle查看构建文件）
           - 使用read_file工具查看关键的源代码文件
           - 使用read_file工具查看build.gradle文件了解项目依赖
           - 使用read_file工具查看AndroidManifest.xml文件
           - 基于以上分析，提供详细的项目分析报告，包括项目结构、主要功能模块、依赖关系等
        
        2. 当用户要求"新加功能"时：
           - 首先分析用户的需求，理解需要添加的功能
           - 使用list_files工具查看项目结构，了解代码组织
           - 使用read_file工具查看相关的源代码文件，理解现有实现
           - 分析应该修改哪个文件来实现新功能
           - 使用edit_file工具修改相应的文件，添加新功能
           - 提供修改的详细说明，包括修改了哪些文件、添加了什么功能等
        
        3. 当用户要求修改或优化现有功能时：
           - 首先使用read_file工具查看相关的源代码文件，理解现有实现
           - 分析需要修改的部分
           - 使用edit_file工具进行修改
           - 提供修改的详细说明
        
        对话历史：
        $historyText
        
        用户消息：
        $userMessage
        """.trimIndent()
    }
    
    data class ToolCall(
        val toolName: String,
        val parameters: Map<String, Any>
    )
    
    data class TokenUsage(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    )
    
    suspend fun sendMessage(message: String): Result<Pair<String, TokenUsage?>> = withContext(Dispatchers.IO) {
        try {
            val settings = AiAgentSettings.instance.state
            val currentProvider = settings.providers.find { it.id == settings.currentProviderId } ?: settings.providers[0]
            
            val apiUrl = buildApiUrl(currentProvider, when (currentProvider.apiType) {
                "ollama" -> "/api/generate"
                "openai" -> "/v1/chat/completions"
                else -> "/api/generate"
            })
            
            // 获取对话历史
            val chatStateService = ChatStateService.instance
            val history = chatStateService.currentSession?.messages ?: emptyList()
            
            // 构建包含工具定义的提示
            val promptWithTools = buildPromptWithTools(message, history)
            
            val requestBody = when (currentProvider.apiType) {
                "ollama" -> JSONObject().apply {
                    put("model", settings.currentModel)
                    put("prompt", promptWithTools)
                    put("stream", false)
                    put("options", JSONObject().apply {
                        put("temperature", currentProvider.temperature)
                        put("top_p", currentProvider.topP)
                    })
                }
                "openai" -> JSONObject().apply {
                    put("model", settings.currentModel)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", promptWithTools)
                        })
                    })
                    put("temperature", currentProvider.temperature)
                    put("top_p", currentProvider.topP)
                }
                else -> JSONObject().apply {
                    put("model", settings.currentModel)
                    put("prompt", promptWithTools)
                    put("stream", false)
                }
            }
            
            val requestBuilder = HttpRequest.newBuilder()
                .uri(java.net.URI(apiUrl))
                .timeout(Duration.ofSeconds(currentProvider.timeoutSeconds.toLong()))
                .header("Content-Type", "application/json")
            
            // Add API key if provided
            if (currentProvider.apiKey.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer ${currentProvider.apiKey}")
            }
            
            val request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                val jsonResponse = JSONObject(response.body())
                val generatedText = when (currentProvider.apiType) {
                    "ollama" -> jsonResponse.optString("response", "")
                    "openai" -> jsonResponse.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                    else -> jsonResponse.optString("response", "")
                }
                
                val tokenUsage = when (currentProvider.apiType) {
                    "openai" -> {
                        val usage = jsonResponse.optJSONObject("usage")
                        if (usage != null) {
                            TokenUsage(
                                promptTokens = usage.optInt("prompt_tokens", 0),
                                completionTokens = usage.optInt("completion_tokens", 0),
                                totalTokens = usage.optInt("total_tokens", 0)
                            )
                        } else {
                            null
                        }
                    }
                    "ollama" -> {
                        val evalCount = jsonResponse.optInt("eval_count", 0)
                        val promptEvalCount = jsonResponse.optInt("prompt_eval_count", 0)
                        if (evalCount > 0 || promptEvalCount > 0) {
                            TokenUsage(
                                promptTokens = promptEvalCount,
                                completionTokens = evalCount - promptEvalCount,
                                totalTokens = evalCount
                            )
                        } else {
                            null
                        }
                    }
                    else -> null
                }
                
                Result.success(Pair(generatedText, tokenUsage))
            } else {
                Result.failure(Exception("API 返回错误：${response.statusCode()}\n${response.body()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun sendMessageStream(
        message: String, 
        onChunk: (String) -> Unit, 
        onToolCall: (ToolCall) -> Unit,
        onTokenUsage: (TokenUsage?) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            log("开始发送流式消息: $message")
            val settings = AiAgentSettings.instance.state
            val currentProvider = settings.providers.find { it.id == settings.currentProviderId } ?: settings.providers[0]
            
            log("当前Provider: ${currentProvider.name}, 类型: ${currentProvider.apiType}")
            log("当前模型: ${settings.currentModel}")
            
            val apiUrl = buildApiUrl(currentProvider, when (currentProvider.apiType) {
                "ollama" -> "/api/generate"
                "openai" -> "/v1/chat/completions"
                else -> "/api/generate"
            })
            
            log("API URL: $apiUrl")
            
            // 获取对话历史
            val chatStateService = ChatStateService.instance
            val history = chatStateService.currentSession?.messages ?: emptyList()
            
            // 构建包含工具定义的提示
            val promptWithTools = buildPromptWithTools(message, history)
            log("构建的提示: ${promptWithTools.take(500)}...")
            
            val requestBody = when (currentProvider.apiType) {
                "ollama" -> JSONObject().apply {
                    put("model", settings.currentModel)
                    put("prompt", promptWithTools)
                    put("stream", true)
                    put("options", JSONObject().apply {
                        put("temperature", currentProvider.temperature)
                        put("top_p", currentProvider.topP)
                    })
                }
                "openai" -> JSONObject().apply {
                    put("model", settings.currentModel)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", promptWithTools)
                        })
                    })
                    put("temperature", currentProvider.temperature)
                    put("top_p", currentProvider.topP)
                    put("stream", true)
                }
                else -> JSONObject().apply {
                    put("model", settings.currentModel)
                    put("prompt", promptWithTools)
                    put("stream", true)
                }
            }
            
            log("请求体: ${requestBody.toString().take(500)}...")
            
            val requestBuilder = HttpRequest.newBuilder()
                .uri(java.net.URI(apiUrl))
                .timeout(Duration.ofSeconds(currentProvider.timeoutSeconds.toLong()))
                .header("Content-Type", "application/json")
            
            // Add API key if provided
            if (currentProvider.apiKey.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer ${currentProvider.apiKey}")
                log("已添加API Key")
            }
            
            val request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build()
            
            log("发送请求...")
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
            log("响应状态码: ${response.statusCode()}")
            
            val lines = response.body()
            log("开始处理响应流...")
            
            var accumulatedText = ""
            var processingToolCall = false
            var toolCallDetected = false
            var totalPromptTokens = 0
            var totalCompletionTokens = 0
            
            lines.forEach { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) {
                    return@forEach
                }
                
                try {
                    when (currentProvider.apiType) {
                        "ollama" -> {
                            log("Ollama响应行: $trimmedLine")
                            val json = JSONObject(trimmedLine)
                            val chunk = json.optString("response", "")
                            
                            // 收集 token 使用情况
                            val promptEvalCount = json.optInt("prompt_eval_count", 0)
                            val evalCount = json.optInt("eval_count", 0)
                            if (promptEvalCount > 0) {
                                totalPromptTokens = promptEvalCount
                            }
                            if (evalCount > 0) {
                                totalCompletionTokens = evalCount - totalPromptTokens
                            }
                            
                            if (chunk.isNotEmpty()) {
                                log("收到Ollama chunk: ${chunk.take(100)}...")
                                accumulatedText += chunk
                                
                                // 检查是否包含工具调用
                                if (!processingToolCall && !toolCallDetected) {
                                    val toolCall = parseToolCall(accumulatedText)
                                    if (toolCall != null) {
                                        log("检测到工具调用: ${toolCall.toolName}")
                                        toolCallDetected = true
                                        processingToolCall = true
                                        ApplicationManager.getApplication().invokeLater {
                                            onToolCall(toolCall)
                                            processingToolCall = false
                                            toolCallDetected = false
                                            // 清空累积文本，准备处理下一个工具调用
                                            accumulatedText = ""
                                        }
                                    } else {
                                        // 发送chunk
                                        ApplicationManager.getApplication().invokeLater {
                                            onChunk(chunk)
                                        }
                                    }
                                } else if (toolCallDetected) {
                                    // 工具调用已检测到，等待处理完成
                                    log("工具调用已检测到，等待处理完成")
                                } else {
                                    // 发送chunk
                                    ApplicationManager.getApplication().invokeLater {
                                        onChunk(chunk)
                                    }
                                }
                            }
                        }
                        "openai" -> {
                            log("OpenAI响应行: $trimmedLine")
                            if (trimmedLine.startsWith("data: ")) {
                                val data = trimmedLine.substring(6)
                                if (data == "[DONE]") {
                                    log("OpenAI响应结束")
                                    return@forEach
                                }
                                val json = JSONObject(data)
                                
                                // 收集 token 使用情况
                                val usage = json.optJSONObject("usage")
                                if (usage != null) {
                                    totalPromptTokens = usage.optInt("prompt_tokens", 0)
                                    totalCompletionTokens = usage.optInt("completion_tokens", 0)
                                }
                                
                                val chunk = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", "") ?: ""
                                if (chunk.isNotEmpty()) {
                                    log("收到OpenAI chunk: ${chunk.take(100)}...")
                                    accumulatedText += chunk
                                    
                                    // 检查是否包含工具调用
                                    if (!processingToolCall && !toolCallDetected) {
                                        val toolCall = parseToolCall(accumulatedText)
                                        if (toolCall != null) {
                                            log("检测到工具调用: ${toolCall.toolName}")
                                            toolCallDetected = true
                                            processingToolCall = true
                                            ApplicationManager.getApplication().invokeLater {
                                                onToolCall(toolCall)
                                                processingToolCall = false
                                                toolCallDetected = false
                                                // 清空累积文本，准备处理下一个工具调用
                                                accumulatedText = ""
                                            }
                                        } else {
                                            // 发送chunk
                                            ApplicationManager.getApplication().invokeLater {
                                                onChunk(chunk)
                                            }
                                        }
                                    } else if (toolCallDetected) {
                                        // 工具调用已检测到，等待处理完成
                                        log("工具调用已检测到，等待处理完成")
                                    } else {
                                        // 发送chunk
                                        ApplicationManager.getApplication().invokeLater {
                                            onChunk(chunk)
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            log("其他响应行: $trimmedLine")
                            if (trimmedLine.startsWith("data: ")) {
                                val data = trimmedLine.substring(6)
                                if (data == "[DONE]") {
                                    log("响应结束")
                                    return@forEach
                                }
                                val json = JSONObject(data)
                                val chunk = json.optString("response", "")
                                if (chunk.isNotEmpty()) {
                                    log("收到chunk: ${chunk.take(100)}...")
                                    accumulatedText += chunk
                                    
                                    // 检查是否包含工具调用
                                    if (!processingToolCall && !toolCallDetected) {
                                        val toolCall = parseToolCall(accumulatedText)
                                        if (toolCall != null) {
                                            log("检测到工具调用: ${toolCall.toolName}")
                                            toolCallDetected = true
                                            processingToolCall = true
                                            ApplicationManager.getApplication().invokeLater {
                                                onToolCall(toolCall)
                                                processingToolCall = false
                                                toolCallDetected = false
                                                // 清空累积文本，准备处理下一个工具调用
                                                accumulatedText = ""
                                            }
                                        } else {
                                            // 发送chunk
                                            ApplicationManager.getApplication().invokeLater {
                                                onChunk(chunk)
                                            }
                                        }
                                    } else if (toolCallDetected) {
                                        // 工具调用已检测到，等待处理完成
                                        log("工具调用已检测到，等待处理完成")
                                    } else {
                                        // 发送chunk
                                        ApplicationManager.getApplication().invokeLater {
                                            onChunk(chunk)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log("处理响应行错误: ${e.message}")
                    e.printStackTrace()
                }
            }
            
            // 通知 token 使用情况
            val tokenUsage = if (totalPromptTokens > 0 || totalCompletionTokens > 0) {
                TokenUsage(
                    promptTokens = totalPromptTokens,
                    completionTokens = totalCompletionTokens,
                    totalTokens = totalPromptTokens + totalCompletionTokens
                )
            } else {
                null
            }
            onTokenUsage(tokenUsage)
            
            log("流式消息处理完成")
            Result.success(Unit)
        } catch (e: Exception) {
            log("发送流式消息错误: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }
    
    suspend fun testConnection(provider: AiAgentSettings.Provider? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val settings = AiAgentSettings.instance.state
            val testProvider = provider ?: settings.providers.find { it.id == settings.currentProviderId } ?: settings.providers[0]
            
            val apiUrl = buildApiUrl(testProvider, when (testProvider.apiType) {
                "ollama" -> "/api/tags"
                "openai" -> "/v1/models"
                else -> "/api/tags"
            })
            
            val requestBuilder = HttpRequest.newBuilder()
                .uri(java.net.URI(apiUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
            
            if (testProvider.apiKey.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer ${testProvider.apiKey}")
            }
            
            val request = requestBuilder.build()
            
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun getAvailableModels(provider: AiAgentSettings.Provider? = null): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val settings = AiAgentSettings.instance.state
            val targetProvider = provider ?: settings.providers.find { it.id == settings.currentProviderId } ?: settings.providers[0]
            
            val apiUrl = buildApiUrl(targetProvider, when (targetProvider.apiType) {
                "ollama" -> "/api/tags"
                "openai" -> "/v1/models"
                else -> return@withContext Result.failure(Exception("Unsupported API type"))
            })
            
            val requestBuilder = HttpRequest.newBuilder()
                .uri(java.net.URI(apiUrl))
                .timeout(Duration.ofSeconds(10))
                .GET()
            
            if (targetProvider.apiKey.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer ${targetProvider.apiKey}")
            }
            
            val request = requestBuilder.build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                val jsonResponse = JSONObject(response.body())
                val models = mutableListOf<String>()
                
                when (targetProvider.apiType) {
                    "ollama" -> {
                        val modelsArray = jsonResponse.optJSONArray("models")
                        if (modelsArray != null) {
                            for (i in 0 until modelsArray.length()) {
                                val modelObj = modelsArray.optJSONObject(i)
                                val modelName = modelObj?.optString("name")
                                if (modelName != null) {
                                    models.add(modelName)
                                }
                            }
                        }
                    }
                    "openai" -> {
                        val dataArray = jsonResponse.optJSONArray("data")
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val modelObj = dataArray.optJSONObject(i)
                                val modelId = modelObj?.optString("id")
                                if (modelId != null) {
                                    models.add(modelId)
                                }
                            }
                        }
                    }
                }
                
                Result.success(models)
            } else {
                Result.failure(Exception("API 返回错误：${response.statusCode()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}