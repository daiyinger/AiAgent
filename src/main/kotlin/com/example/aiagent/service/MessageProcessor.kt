package com.example.aiagent.service

import com.example.aiagent.api.*
import com.example.aiagent.settings.AiAgentSettings

/**
 * 消息处理器
 * 负责消息列表构建、Token 优化、系统提示生成
 */
class MessageProcessor {
    
    companion object {
        /** 最大历史消息数 */
        const val MAX_HISTORY_MESSAGES = 20
    }
    
    private fun log(message: String) = LogService.log("[MessageProcessor] $message")
    
    // 上下文管理器
    private val contextManager = ContextManager()
    
    /**
     * 构建优化的消息列表（Token 优化）
     * 使用 ContextManager 进行智能上下文管理
     */
    fun buildOptimizedMessageList(
        currentMessage: String,
        embedToolsInPrompt: Boolean = false
    ): MutableList<ChatMessage> {
        val chatStateService = ChatStateService.instance
        val history = chatStateService.currentSession?.messages ?: emptyList()
        val settings = AiAgentSettings.instance.state
        val modelName = settings.currentModel

        log("构建消息列表 - 历史消息: ${history.size} 条")

        // 使用 ContextManager 构建智能上下文窗口
        val contextConfig = ContextManager.ContextWindowConfig(
            maxTokens = TokenOptimizer.getModelTokenLimit(modelName) / 2,  // 使用一半的 token 限制
            maxMessages = MAX_HISTORY_MESSAGES,
            prioritizeRecent = true,
            compressionEnabled = true
        )
        
        val contextMessages = contextManager.buildContextWindow(
            history = history,
            currentMessage = currentMessage,
            config = contextConfig
        )

        // 计算token统计
        val totalTokens = contextMessages.sumOf { 
            TokenOptimizer.countTokens(it.content, modelName) 
        }
        log("上下文窗口 - 消息数: ${contextMessages.size}, Token数: $totalTokens")

        return buildList {
            // 系统提示
            val (systemPrompt, systemPromptTokens) = buildOptimizedSystemPrompt(
                embedToolsInPrompt, modelName, currentMessage
            )
            add(ChatMessage.System(systemPrompt))
            
            log("System prompt tokens: $systemPromptTokens")

            // 添加上下文消息（已由 ContextManager 优化）
            addAll(contextMessages)

            // 当前消息（如果最后一条不是用户消息）
            val lastMessage = contextMessages.lastOrNull()
            if (lastMessage !is ChatMessage.User) {
                add(ChatMessage.User(currentMessage))
            }
        }.toMutableList()
    }
    
    /**
     * 检测是否为编程模式
     */
    private fun detectProgrammingMode(message: String): Boolean {
        val keywords = listOf(
            "code", "编程", "代码", "function", "class", 
            "文件", "编译", "优化", "完善", "bug", "error"
        )
        return keywords.any { message.lowercase().contains(it) }
    }
    
    /**
     * 记录 Token 使用情况
     */
    private fun logTokenUsage(
        currentMessage: String,
        selectedHistory: List<ChatStateService.MessageState>,
        modelName: String
    ) {
        val currentTokens = TokenOptimizer.countTokens(currentMessage, modelName)
        val historyTokens = selectedHistory.sumOf {
            TokenOptimizer.countTokens(it.content, modelName)
        }
        val modelLimit = TokenOptimizer.getModelTokenLimit(modelName)

        log("Token优化 - 模型: $modelName, 限制: $modelLimit")
        log("历史消息: ${selectedHistory.size} 条")
        log("Token使用 - 当前: $currentTokens, 历史: $historyTokens, 总计: ${currentTokens + historyTokens}")
    }
    
    /**
     * 构建优化的系统提示
     */
    fun buildOptimizedSystemPrompt(
        embedToolsInPrompt: Boolean = false,
        modelName: String = "default",
        userMessage: String? = null
    ): Pair<String, Int> {
        val settings = AiAgentSettings.instance.state
        val currentPrompt = settings.systemPrompts.find { it.id == settings.currentSystemPromptId }
        val userSystemPrompt = currentPrompt?.content ?: ""
        
        val systemPrompt = if (!embedToolsInPrompt) {
            buildFcModeSystemPrompt(userSystemPrompt, userMessage)
        } else {
            buildDslModeSystemPrompt(userSystemPrompt, userMessage)
        }
        
        val tokenCount = TokenOptimizer.countTokens(systemPrompt, modelName)
        return Pair(systemPrompt, tokenCount)
    }
    
    /**
     * 构建 Function Calling 模式的系统提示
     */
    private fun buildFcModeSystemPrompt(
        userSystemPrompt: String,
        userMessage: String?
    ): String {
        val relevantTools = getRelevantTools(userMessage)
        val toolNames = relevantTools.joinToString(", ") { it.function.name }
        
        val toolsInfo = "\n\nAvailable tools: $toolNames\nCompilation advice: assemble(fast) > build(complete) > clean(rebuild)"
        
        return if (userSystemPrompt.isNotEmpty()) {
            "$userSystemPrompt$toolsInfo"
        } else {
            toolsInfo
        }
    }
    
    /**
     * 构建 DSL 模式的系统提示
     */
    private fun buildDslModeSystemPrompt(
        userSystemPrompt: String,
        userMessage: String?
    ): String {
        val relevantTools = getRelevantTools(userMessage)
        val toolsDetailDesc = getMinimalToolsDescription(relevantTools)
        
        val toolsInfo = """
            You have the following tools:
            $toolsDetailDesc

            When you need to use a tool, please output in the following DSL format strictly:
            <｜DSML｜function_calls>
            <｜DSML｜invoke name="tool name">
            <｜DSML｜function_call>
            <arguments>
            <argument name="parameter name">parameter value</argument>
            </arguments>
            </｜DSML｜function_call>
            </｜DSML｜invoke>
            </｜DSML｜function_calls>

            Important notes:
            - Output only one tool call at a time
            - The tool name must be one of the names listed above
            - Parameter values do not need quotes
        """.trimIndent()
        
        return if (userSystemPrompt.isNotEmpty()) {
            "$userSystemPrompt$toolsInfo"
        } else {
            toolsInfo
        }
    }
    
    /**
     * 根据用户消息获取相关的工具
     */
    fun getRelevantTools(userMessage: String?): List<ToolDefinition> {
        if (userMessage.isNullOrEmpty()) {
            return ToolDefinitions.getAllTools()
        }

        val allTools = ToolDefinitions.getAllTools()
        val relevantTools = mutableListOf<ToolDefinition>()

        val toolKeywords = mapOf(
            "read_file" to listOf("read", "file", "view", "see", "content"),
            "edit_file" to listOf("edit", "modify", "change", "update", "file"),
            "delete_file" to listOf("delete", "remove", "file"),
            "search_files" to listOf("search", "find", "locate", "file"),
            "list_files" to listOf("list", "files", "directory", "folder"),
            "create_directory" to listOf("create", "directory", "folder", "mkdir"),
            "compile_project" to listOf("compile", "build", "assemble", "gradle"),
            "build" to listOf("build", "compile", "assemble", "gradle"),
            "analyze_android_project" to listOf("analyze", "android", "project", "structure"),
            "web_search" to listOf("search", "web", "internet", "information"),
            "get_current_time" to listOf("time", "date", "current", "now")
        )

        for (tool in allTools) {
            val keywords = toolKeywords[tool.function.name] ?: emptyList()
            if (keywords.any { keyword -> userMessage.lowercase().contains(keyword) }) {
                relevantTools.add(tool)
            }
        }

        return if (relevantTools.isNotEmpty()) relevantTools else allTools
    }
    
    /**
     * 获取最小化的工具描述
     */
    private fun getMinimalToolsDescription(tools: List<ToolDefinition>): String {
        return tools.joinToString("\n") { tool ->
            buildString {
                append("${tool.function.name}")
                val required = tool.function.parameters.required
                if (required != null && required.isNotEmpty()) {
                    val requiredParams = required.joinToString(", ")
                    append("($requiredParams)")
                }
                append(": ${tool.function.description}")
            }
        }
    }
    
    /**
     * 估算 Token 数
     */
    fun estimateTokenCount(text: String): Int {
        val settings = AiAgentSettings.instance.state
        return TokenOptimizer.countTokens(text, settings.currentModel)
    }
}