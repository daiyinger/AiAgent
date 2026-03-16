package com.example.aiagent.ui

import com.example.aiagent.service.ChatStateService
import com.example.aiagent.service.toLocalDateTime
import com.example.aiagent.service.toStateString
import java.time.LocalDateTime

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
class AiMessage(
    override val id: String,
    override val content: String,
    override val timestamp: LocalDateTime,
    var isGenerating: Boolean = false,
    var tokenUsage: Pair<Int, Int>? = null,
    var modelName: String? = null
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
        "ai" -> AiMessage(
            id,
            content,
            dateTime,
            isGenerating,
            if (inputTokens > 0 || outputTokens > 0) Pair(inputTokens, outputTokens) else null,
            modelName
        )
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
            modelName = modelName
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

