package com.example.aiagent.service

import com.example.aiagent.settings.AiAgentSettings
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType

/**
 * Token优化工具类
 * 使用jtokkit库精确计算token，优化历史会话的token使用
 */
object TokenOptimizer {

    private val registry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()

    // 模型对应的token限制
    private val modelTokenLimits = mapOf(
        "gpt-4" to 8192,
        "gpt-4-turbo" to 128000,
        "gpt-4o" to 128000,
        "gpt-3.5-turbo" to 16385,
        "deepseek-chat" to 64000,
        "deepseek-coder" to 64000,
        "qwen" to 32000
    )

    // 为历史会话保留的token比例
    private const val HISTORY_TOKEN_RATIO = 0.6
    
    // 编程模式下为历史会话保留的token比例（更高）
    private const val PROGRAMMING_HISTORY_TOKEN_RATIO = 0.7

    // 重要的编程相关工具
    private val IMPORTANT_PROGRAMMING_TOOLS = setOf(
        "read_file",
        "edit_file",
        "compile_project",
        "analyze_project",
        "search_files",
        "list_files"
    )

    /**
     * 获取指定模型的encoding
     */
    fun getEncoding(modelName: String): Encoding {
        return try {
            when {
                modelName.contains("gpt-4o", ignoreCase = true) ->
                    registry.getEncoding(EncodingType.CL100K_BASE)
                modelName.contains("gpt-4", ignoreCase = true) ->
                    registry.getEncoding(EncodingType.CL100K_BASE)
                modelName.contains("gpt-3.5", ignoreCase = true) ->
                    registry.getEncoding(EncodingType.CL100K_BASE)
                else -> registry.getEncoding(EncodingType.CL100K_BASE)
            }
        } catch (e: Exception) {
            registry.getEncoding(EncodingType.CL100K_BASE)
        }
    }

    /**
     * 计算文本的token数量
     */
    fun countTokens(text: String, modelName: String = "default"): Int {
        if (text.isEmpty()) return 0
        val encoding = getEncoding(modelName)
        return encoding.countTokens(text)
    }

    /**
     * 计算多条消息的token数量
     */
    fun countMessageTokens(messages: List<String>, modelName: String = "default"): Int {
        return messages.sumOf { countTokens(it, modelName) }
    }

    /**
     * 获取模型的token限制
     * 优先使用模型特定的限制，否则使用设置中的上下文长度
     */
    fun getModelTokenLimit(modelName: String): Int {
        // 先尝试匹配模型特定的限制
        modelTokenLimits.entries.find { (key, _) ->
            modelName.contains(key, ignoreCase = true)
        }?.let { return it.value }
        
        // 使用设置中的上下文长度，默认为8192
        val settings = AiAgentSettings.instance.state
        val currentProvider = settings.providers.find { it.id == settings.currentProviderId } ?: settings.providers.firstOrNull()
        return currentProvider?.contextLength ?: 8192
    }

    /**
     * 计算可用于历史会话的token数量
     */
    fun getAvailableHistoryTokens(modelName: String, currentMessageTokens: Int, isProgrammingMode: Boolean = false): Int {
        val totalLimit = getModelTokenLimit(modelName)
        val systemPromptTokens = 500 // 预估系统提示的token数
        val responseReserve = 2000   // 为响应预留的token数
        val historyRatio = if (isProgrammingMode) PROGRAMMING_HISTORY_TOKEN_RATIO else HISTORY_TOKEN_RATIO
        val availableForHistory = (totalLimit * historyRatio).toInt()

        return availableForHistory - systemPromptTokens - currentMessageTokens
    }

    /**
     * 截断文本到指定token数
     */
    fun truncateToTokens(text: String, maxTokens: Int, modelName: String = "default"): String {
        if (text.isEmpty()) return text

        val encoding = getEncoding(modelName)
        val tokenCount = encoding.countTokens(text)

        return if (tokenCount <= maxTokens) {
            text
        } else {
            // 使用encodeOrdinary获取token数组，然后截断
            val tokens = encoding.encodeOrdinary(text)
            // 创建截断后的IntArrayList
            val truncatedTokens = com.knuddels.jtokkit.api.IntArrayList()
            val limit = maxTokens.coerceAtMost(tokens.size())
            for (i in 0 until limit) {
                truncatedTokens.add(tokens[i])
            }
            encoding.decode(truncatedTokens) + "\n... [已截断，原长度 $tokenCount tokens]"
        }
    }

    /**
     * 智能选择历史消息
     * 优先保留最近的消息，同时考虑消息的重要性
     */
    fun selectHistoryMessages(
        history: List<ChatStateService.MessageState>,
        currentMessage: String,
        modelName: String,
        maxHistoryMessages: Int = 20,
        isProgrammingMode: Boolean = false
    ): List<ChatStateService.MessageState> {
        if (history.isEmpty()) return emptyList()

        val currentMessageTokens = countTokens(currentMessage, modelName)
        val availableTokens = getAvailableHistoryTokens(modelName, currentMessageTokens, isProgrammingMode)

        // 过滤有效消息并按时间排序
        val validHistory = if (isProgrammingMode) {
            // 编程模式：保留用户、AI消息和重要的工具结果
            history
                .filter { 
                    (it.type in listOf("user", "ai") ||
                     (it.type == "tool" && it.toolName in IMPORTANT_PROGRAMMING_TOOLS)) && 
                    it.content.isNotEmpty() 
                }
                .sortedBy { it.timestamp }
        } else {
            // 普通模式：只保留用户和AI消息
            history
                .filter { it.type in listOf("user", "ai") && it.content.isNotEmpty() }
                .sortedBy { it.timestamp }
        }

        if (validHistory.isEmpty()) return emptyList()

        // 优先选择最近的消息
        val recentHistory = validHistory.takeLast(maxHistoryMessages)

        // 计算需要的token数
        var totalTokens = 0
        val selectedMessages = mutableListOf<ChatStateService.MessageState>()

        // 从最新的消息开始选择
        for (message in recentHistory.asReversed()) {
            val messageTokens = countTokens(message.content, modelName)

            if (totalTokens + messageTokens <= availableTokens) {
                selectedMessages.add(message)
                totalTokens += messageTokens
            } else {
                // 如果token不够，尝试截断这条消息
                val remainingTokens = availableTokens - totalTokens
                if (remainingTokens > 100) { // 至少保留100个token
                    val truncatedContent = truncateToTokens(message.content, remainingTokens, modelName)
                    val truncatedMessage = ChatStateService.MessageState(
                        id = message.id,
                        type = message.type,
                        content = truncatedContent,
                        timestamp = message.timestamp,
                        toolName = message.toolName,
                        parameters = message.parameters,
                        isExecuting = message.isExecuting,
                        result = message.result,
                        output = message.output,
                        inputTokens = message.inputTokens,
                        outputTokens = message.outputTokens,
                        totalTokens = message.totalTokens,
                        modelName = message.modelName
                    )
                    selectedMessages.add(truncatedMessage)
                }
                break
            }
        }

        return selectedMessages.reversed() // 恢复时间顺序
    }

    /**
     * 生成token使用统计信息
     */
    fun generateTokenStats(
        messages: List<ChatStateService.MessageState>,
        modelName: String
    ): TokenStats {
        val totalInputTokens = messages.sumOf { it.inputTokens }
        val totalOutputTokens = messages.sumOf { it.outputTokens }
        val contentTokens = messages.sumOf { countTokens(it.content, modelName) }
        val modelLimit = getModelTokenLimit(modelName)

        return TokenStats(
            totalMessages = messages.size,
            totalInputTokens = totalInputTokens,
            totalOutputTokens = totalOutputTokens,
            contentTokens = contentTokens,
            modelLimit = modelLimit,
            usagePercentage = (contentTokens.toDouble() / modelLimit * 100).toInt()
        )
    }

    /**
     * 简单的文本摘要（基于句子重要性）
     * 当文本过长时，提取关键句子
     */
    fun summarizeText(text: String, maxSentences: Int = 3): String {
        if (text.isEmpty()) return text

        // 简单的句子分割
        val sentences = text.split(Regex("(?<=[.!?。！？])\\s+"))
            .filter { it.isNotBlank() }

        if (sentences.size <= maxSentences) return text

        // 简单的摘要策略：取前几句和最后一句
        val summary = if (sentences.size > maxSentences) {
            val firstSentences = sentences.take(maxSentences - 1)
            val lastSentence = sentences.last()
            (firstSentences + lastSentence).joinToString(" ")
        } else {
            sentences.joinToString(" ")
        }

        return summary + "\n... [已摘要，原${sentences.size}句]"
    }

    /**
     * 代码文本摘要
     * 保留函数签名、类定义等重要部分
     */
    fun summarizeCode(text: String, maxLines: Int = 20): String {
        if (text.isEmpty()) return text

        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size <= maxLines) return text

        // 优先保留函数定义、类定义、import语句等
        val importantLines = mutableListOf<String>()
        val regularLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("import ") ||
                trimmed.startsWith("package ") ||
                trimmed.startsWith("class ") ||
                trimmed.startsWith("interface ") ||
                trimmed.startsWith("fun ") ||
                trimmed.startsWith("val ") ||
                trimmed.startsWith("var ") ||
                trimmed.startsWith("if (") ||
                trimmed.startsWith("for (") ||
                trimmed.startsWith("while (") ||
                trimmed.startsWith("switch (") ||
                trimmed.startsWith("case ") ||
                trimmed.startsWith("default:") ||
                trimmed.startsWith("return ") ||
                trimmed.startsWith("throw ") ||
                trimmed.endsWith(" {") ||
                trimmed == "}") {
                importantLines.add(line)
            } else {
                regularLines.add(line)
            }
        }

        // 构建摘要
        val summaryLines = mutableListOf<String>()
        
        // 添加所有重要行
        summaryLines.addAll(importantLines)
        
        // 如果还有空间，添加一些普通行
        val remainingSpace = maxLines - importantLines.size
        if (remainingSpace > 0) {
            summaryLines.addAll(regularLines.take(remainingSpace))
        }

        return summaryLines.joinToString("\n") + "\n... [已摘要，原${lines.size}行]"
    }
}

/**
 * Token使用统计
 */
data class TokenStats(
    val totalMessages: Int,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val contentTokens: Int,
    val modelLimit: Int,
    val usagePercentage: Int
)
