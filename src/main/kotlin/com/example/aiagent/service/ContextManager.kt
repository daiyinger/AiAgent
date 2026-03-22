package com.example.aiagent.service

import com.example.aiagent.api.*
import com.example.aiagent.settings.AiAgentSettings

/**
 * 上下文管理器
 * 负责智能上下文压缩、窗口管理和相关性评分
 */
class ContextManager {
    
    companion object {
        /** 默认上下文窗口大小（token 数） */
        const val DEFAULT_CONTEXT_WINDOW = 8000
        /** 最大历史消息数 */
        const val MAX_HISTORY_MESSAGES = 20
        /** 摘要触发阈值（消息数） */
        const val SUMMARY_THRESHOLD = 30
        /** 单条消息最大 token 数 */
        const val MAX_MESSAGE_TOKENS = 2000
    }
    
    private fun log(message: String) = LogService.log("[ContextManager] $message")
    
    /**
     * 上下文窗口配置
     */
    data class ContextWindowConfig(
        val maxTokens: Int = DEFAULT_CONTEXT_WINDOW,
        val maxMessages: Int = MAX_HISTORY_MESSAGES,
        val prioritizeRecent: Boolean = true,
        val includeSystemPrompt: Boolean = true,
        val compressionEnabled: Boolean = true
    )
    
    /**
     * 消息相关性评分
     */
    data class MessageScore(
        val message: ChatStateService.MessageState,
        val relevanceScore: Double,
        val recencyScore: Double,
        val importanceScore: Double,
        val totalScore: Double
    )
    
    /**
     * 构建智能上下文窗口
     */
    fun buildContextWindow(
        history: List<ChatStateService.MessageState>,
        currentMessage: String,
        config: ContextWindowConfig = ContextWindowConfig()
    ): List<ChatMessage> {
        val settings = AiAgentSettings.instance.state
        val modelName = settings.currentModel
        
        log("构建上下文窗口 - 历史消息: ${history.size} 条")
        
        // 如果历史消息较少，直接返回
        if (history.size <= config.maxMessages) {
            log("历史消息较少，直接使用")
            return history.map { it.toChatMessage() }
        }
        
        // 计算每条消息的得分
        val scoredMessages = scoreMessages(history, currentMessage, modelName)
        
        // 选择最优的消息子集
        val selectedMessages = selectMessages(scoredMessages, config, modelName)
        
        log("智能选择后消息: ${selectedMessages.size} 条")
        
        // 如果启用压缩且消息仍然较多，进行压缩
        return if (config.compressionEnabled && selectedMessages.size > config.maxMessages / 2) {
            compressMessages(selectedMessages, config.maxMessages)
        } else {
            selectedMessages.map { it.toChatMessage() }
        }
    }
    
    /**
     * 为消息评分
     */
    private fun scoreMessages(
        history: List<ChatStateService.MessageState>,
        currentMessage: String,
        modelName: String
    ): List<MessageScore> {
        val currentTime = System.currentTimeMillis()
        val totalMessages = history.size
        
        return history.mapIndexed { index, message ->
            // 相关性评分（基于关键词匹配）
            val relevanceScore = calculateRelevanceScore(message, currentMessage)
            
            // 时效性评分（越新越高）
            val recencyScore = calculateRecencyScore(index, totalMessages)
            
            // 重要性评分（基于消息类型和内容）
            val importanceScore = calculateImportanceScore(message)
            
            // 总分（加权平均）
            val totalScore = relevanceScore * 0.4 + recencyScore * 0.4 + importanceScore * 0.2
            
            MessageScore(
                message = message,
                relevanceScore = relevanceScore,
                recencyScore = recencyScore,
                importanceScore = importanceScore,
                totalScore = totalScore
            )
        }
    }
    
    /**
     * 计算相关性评分
     */
    private fun calculateRelevanceScore(
        message: ChatStateService.MessageState,
        currentMessage: String
    ): Double {
        if (currentMessage.isBlank()) return 0.5
        
        val messageWords = message.content.lowercase().split("\\s+".toRegex())
        val currentWords = currentMessage.lowercase().split("\\s+".toRegex())
        
        // 计算词汇重叠度
        val commonWords = messageWords.intersect(currentWords.toSet())
        val overlapRatio = if (currentWords.isNotEmpty()) {
            commonWords.size.toDouble() / currentWords.size
        } else {
            0.0
        }
        
        // 检查是否有编程相关关键词
        val programmingKeywords = listOf(
            "code", "function", "class", "file", "error", "bug",
            "compile", "build", "test", "run", "debug",
            "代码", "函数", "类", "文件", "错误", "编译", "构建"
        )
        
        val hasProgrammingContext = programmingKeywords.any { keyword ->
            message.content.lowercase().contains(keyword) && 
            currentMessage.lowercase().contains(keyword)
        }
        
        val contextBonus = if (hasProgrammingContext) 0.3 else 0.0
        
        return (overlapRatio + contextBonus).coerceIn(0.0, 1.0)
    }
    
    /**
     * 计算时效性评分
     */
    private fun calculateRecencyScore(index: Int, totalMessages: Int): Double {
        // 越新的消息得分越高
        return (index.toDouble() / totalMessages).coerceIn(0.0, 1.0)
    }
    
    /**
     * 计算重要性评分
     */
    private fun calculateImportanceScore(message: ChatStateService.MessageState): Double {
        var score = 0.5
        
        // 用户消息和 AI 回复通常更重要
        when (message.type) {
            "user" -> score += 0.2
            "ai" -> score += 0.3
            "tool" -> score += 0.1
        }
        
        // 包含错误信息的消息更重要
        if (message.content.contains("error", ignoreCase = true) ||
            message.content.contains("错误", ignoreCase = true) ||
            message.content.contains("失败", ignoreCase = true)) {
            score += 0.2
        }
        
        // 较长的消息可能包含更多信息
        if (message.content.length > 200) {
            score += 0.1
        }
        
        return score.coerceIn(0.0, 1.0)
    }
    
    /**
     * 选择最优消息子集
     */
    private fun selectMessages(
        scoredMessages: List<MessageScore>,
        config: ContextWindowConfig,
        modelName: String
    ): List<ChatStateService.MessageState> {
        // 按总分排序
        val sortedMessages = if (config.prioritizeRecent) {
            scoredMessages.sortedByDescending { it.totalScore }
        } else {
            scoredMessages.sortedByDescending { it.relevanceScore }
        }
        
        // 选择消息，直到达到限制
        val selectedMessages = mutableListOf<ChatStateService.MessageState>()
        var currentTokens = 0
        
        for (scoredMessage in sortedMessages) {
            val messageTokens = TokenOptimizer.countTokens(
                scoredMessage.message.content, 
                modelName
            )
            
            // 检查是否超过限制
            if (selectedMessages.size >= config.maxMessages ||
                currentTokens + messageTokens > config.maxTokens) {
                break
            }
            
            selectedMessages.add(scoredMessage.message)
            currentTokens += messageTokens
        }
        
        // 按原始顺序重新排序
        return selectedMessages.sortedBy { 
            scoredMessages.indexOfFirst { sm -> sm.message.id == it.id }
        }
    }
    
    /**
     * 压缩消息
     */
    private fun compressMessages(
        messages: List<ChatStateService.MessageState>,
        maxMessages: Int
    ): List<ChatMessage> {
        if (messages.size <= maxMessages) {
            return messages.map { it.toChatMessage() }
        }
        
        log("压缩消息: ${messages.size} -> $maxMessages")
        
        val result = mutableListOf<ChatMessage>()
        
        // 保留第一条用户消息（通常包含问题）
        val firstUserMessage = messages.firstOrNull { it.type == "user" }
        if (firstUserMessage != null) {
            result.add(firstUserMessage.toChatMessage())
        }
        
        // 保留最近的消息
        val recentMessages = messages.takeLast(maxMessages - 2)
        
        // 如果中间有消息被跳过，添加摘要
        val skippedCount = messages.size - recentMessages.size - 1
        if (skippedCount > 0) {
            val summary = generateConversationSummary(messages.drop(1).dropLast(recentMessages.size))
            result.add(ChatMessage.System("[已省略 $skippedCount 条历史消息。摘要: $summary]"))
        }
        
        result.addAll(recentMessages.map { it.toChatMessage() })
        
        return result
    }
    
    /**
     * 生成会话摘要
     */
    private fun generateConversationSummary(messages: List<ChatStateService.MessageState>): String {
        if (messages.isEmpty()) return ""
        
        val topics = mutableSetOf<String>()
        var hasToolCalls = false
        var hasErrors = false
        
        for (message in messages) {
            // 提取主题关键词
            val words = message.content.split("\\s+".toRegex())
            val significantWords = words.filter { word ->
                word.length > 4 && !listOf("the", "and", "for", "that", "this", "with").contains(word.lowercase())
            }
            topics.addAll(significantWords.take(3))
            
            // 检查是否有工具调用
            if (message.type == "tool") {
                hasToolCalls = true
            }
            
            // 检查是否有错误
            if (message.content.contains("error", ignoreCase = true) ||
                message.content.contains("错误", ignoreCase = true)) {
                hasErrors = true
            }
        }
        
        return buildString {
            append("讨论了: ${topics.take(5).joinToString(", ")}")
            if (hasToolCalls) append("；执行了工具操作")
            if (hasErrors) append("；遇到错误")
        }
    }
    
    /**
     * 生成会话摘要（用于长会话）
     */
    fun generateSessionSummary(
        messages: List<ChatStateService.MessageState>
    ): String {
        if (messages.size < SUMMARY_THRESHOLD) {
            return ""
        }
        
        log("生成会话摘要 - 消息数: ${messages.size}")
        
        val userMessages = messages.filter { it.type == "user" }
        val aiMessages = messages.filter { it.type == "ai" }
        val toolCalls = messages.filter { it.type == "tool" }
        
        return buildString {
            appendLine("=== 会话摘要 ===")
            appendLine("消息总数: ${messages.size}")
            appendLine("用户消息: ${userMessages.size}")
            appendLine("AI 回复: ${aiMessages.size}")
            appendLine("工具调用: ${toolCalls.size}")
            appendLine()
            
            // 主要话题
            val topics = extractTopics(messages)
            if (topics.isNotEmpty()) {
                appendLine("主要话题: ${topics.joinToString(", ")}")
            }
            
            // 工具使用统计
            if (toolCalls.isNotEmpty()) {
                val toolStats = toolCalls.groupBy { it.toolName }
                    .mapValues { it.value.size }
                    .toList()
                    .sortedByDescending { it.second }
                    .take(5)
                
                appendLine("常用工具: ${toolStats.joinToString(", ") { "${it.first}(${it.second}次)" }}")
            }
            
            appendLine("================")
        }
    }
    
    /**
     * 提取主要话题
     */
    private fun extractTopics(messages: List<ChatStateService.MessageState>): List<String> {
        val wordFrequency = mutableMapOf<String, Int>()
        
        val stopWords = setOf(
            "the", "and", "for", "that", "this", "with", "from", "have", "has",
            "was", "were", "been", "being", "are", "is", "am", "do", "does",
            "did", "will", "would", "could", "should", "may", "might",
            "的", "了", "在", "是", "我", "你", "他", "她", "它", "们", "这", "那"
        )
        
        for (message in messages) {
            val words = message.content.lowercase()
                .replace("[^a-z\\u4e00-\\u9fa5\\s]".toRegex(), " ")
                .split("\\s+".toRegex())
            
            for (word in words) {
                if (word.length > 2 && word !in stopWords) {
                    wordFrequency[word] = (wordFrequency[word] ?: 0) + 1
                }
            }
        }
        
        return wordFrequency.toList()
            .sortedByDescending { it.second }
            .take(10)
            .map { it.first }
    }
    
    /**
     * 清理过期上下文
     */
    fun cleanupExpiredContext(
        messages: List<ChatStateService.MessageState>,
        maxAgeHours: Long = 24
    ): List<ChatStateService.MessageState> {
        val cutoffTime = System.currentTimeMillis() - (maxAgeHours * 60 * 60 * 1000)
        
        return messages.filter { message ->
            try {
                val messageTime = message.timestamp.toLocalDateTime()
                messageTime.isAfter(java.time.LocalDateTime.now().minusHours(maxAgeHours))
            } catch (e: Exception) {
                true // 保留无法解析时间的消息
            }
        }
    }
    
    /**
     * MessageState 转换为 ChatMessage
     */
    private fun ChatStateService.MessageState.toChatMessage(): ChatMessage {
        return when (type) {
            "user" -> ChatMessage.User(content)
            "ai" -> ChatMessage.Assistant(content, reasoningContent = reasoningContent)
            "tool" -> ChatMessage.Tool(toolCallId = id, name = toolName, content = output)
            else -> ChatMessage.User(content)
        }
    }
}