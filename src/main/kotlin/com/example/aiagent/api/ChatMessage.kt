package com.example.aiagent.api

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 聊天消息类型
 */
sealed class ChatMessage {
    abstract val content: String
    abstract val role: String

    data class System(override val content: String) : ChatMessage() {
        override val role: String = "system"
    }

    data class User(override val content: String) : ChatMessage() {
        override val role: String = "user"
    }

    data class Assistant(
        override val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoningContent: String? = null  // DeepSeek 思维模式：必须回传给 API
    ) : ChatMessage() {
        override val role: String = "assistant"
    }

    data class Tool(
        val toolCallId: String,
        val name: String,
        override val content: String
    ) : ChatMessage() {
        override val role: String = "tool"
    }
}

/**
 * 工具调用
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
    val index: Int? = null  // 用于流式 delta 合并，按 index 分组
)

data class FunctionCall(
    val name: String,
    val arguments: String
)

/**
 * 工具定义（用于 Function Calling schema）
 */
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: ParametersDefinition
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParametersDefinition(
    val type: String = "object",
    val properties: Map<String, PropertyDefinition>? = null,
    val required: List<String>? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PropertyDefinition(
    val type: String,
    val description: String,
    @get:com.fasterxml.jackson.annotation.JsonProperty("enum")
    val enumValues: List<String>? = null
)

/**
 * Token 使用情况
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * API 响应
 */
data class ChatResponse(
    val content: String,
    val toolCalls: List<ToolCall>,
    val tokenUsage: TokenUsage?,
    val finishReason: String? = null,
    val reasoningContent: String? = null
)
