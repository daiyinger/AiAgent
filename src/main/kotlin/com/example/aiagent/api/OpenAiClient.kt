package com.example.aiagent.api

import com.example.aiagent.settings.AiAgentSettings
import com.example.aiagent.service.LogService
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容 API 客户端
 * 支持流式响应 (SSE) 和非流式响应
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutSeconds: Int = 60,
    private val temperature: Double = 0.7,
    private val topP: Double = 0.9,
    private val maxRetries: Int = 2
) {
    companion object {
        val objectMapper: ObjectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private fun log(message: String) = LogService.log("[OpenAiClient] $message")
        private fun logNetworkSend(message: String) = LogService.logNetworkSend(message)
        private fun logNetworkReceive(message: String) = LogService.logNetworkReceive(message)

        fun fromSettings(settings: AiAgentSettings.State): OpenAiClient {
            val provider = settings.providers.find { it.id == settings.currentProviderId }
                ?: settings.providers.firstOrNull()
                ?: throw IllegalStateException("No provider configured")

            return OpenAiClient(
                baseUrl = provider.apiUrl,
                apiKey = provider.apiKey,
                model = settings.currentModel,
                timeoutSeconds = provider.timeoutSeconds,
                temperature = provider.temperature,
                topP = provider.topP
            )
        }
    }

    private val apiUrl: String = run {
        val base = baseUrl.trimEnd('/')
        when {
            base.endsWith("/v1") -> "$base/chat/completions"
            base.endsWith("/v1/chat/completions") -> base
            base.contains("/v1/") -> "$base".let { if (it.endsWith("/")) "${it}chat/completions" else it }
            else -> "$base/v1/chat/completions"
        }
    }

    fun supportsFunctionCalling(): Boolean {
        val lowerModel = model.lowercase()
        val noFcModels = listOf("deepseek-reasoner", "deepseek-r1", "o1-mini", "o1-preview")
        val noFcPrefixes = listOf("o1-", "deepseek-r")
        return noFcModels.none { lowerModel == it } && noFcPrefixes.none { lowerModel.startsWith(it) }
    }

    /**
     * 流式聊天请求，返回 Flow<StreamChunk>
     * 使用 callbackFlow 处理异步回调
     */
    fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList()
    ): Flow<StreamChunk> = callbackFlow {
        val effectiveTools = if (supportsFunctionCalling()) tools.ifEmpty { null } else null

        val request = ChatCompletionRequest(
            model = model,
            messages = messages.map { it.toApiMessage() },
            tools = effectiveTools,
            toolChoice = if (effectiveTools != null) "auto" else null,
            temperature = temperature,
            topP = topP,
            stream = true
        )

        val requestBody = objectMapper.writeValueAsString(request)
        log("Stream request model=$model, messages=${messages.size}")

        // 在 IO 线程执行 HTTP 请求
        launch(Dispatchers.IO) {
            try {
                doStreamRequest(requestBody) { line ->
                    when (line) {
                        is StreamLine.Data -> {
                            val chunk = parseStreamChunk(line.value)
                            if (chunk != null) {
                                trySend(chunk)
                            }
                        }
                        is StreamLine.Done -> {
                            trySend(StreamChunk.Done)
                        }
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }

        awaitClose()
    }.flowOn(Dispatchers.IO)

    /**
     * 非流式聊天请求
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList()
    ): ChatResponse = withContext(Dispatchers.IO) {
        val effectiveTools = if (supportsFunctionCalling()) tools.ifEmpty { null } else null

        val request = ChatCompletionRequest(
            model = model,
            messages = messages.map { it.toApiMessage() },
            tools = effectiveTools,
            toolChoice = if (effectiveTools != null) "auto" else null,
            temperature = temperature,
            topP = topP,
            stream = false
        )

        val requestBody = objectMapper.writeValueAsString(request)
        log("Request model=$model, messages=${messages.size}")

        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                log("重试第 $attempt 次...")
                Thread.sleep((attempt * 1000).toLong())
            }
            try {
                val result = doHttpPost(requestBody)
                val response: ChatCompletionResponse = objectMapper.readValue(result)
                return@withContext response.toChatResponse()
            } catch (e: RetryableException) {
                log("可重试错误 (attempt $attempt): ${e.message}")
                lastException = e
            } catch (e: Exception) {
                throw e
            }
        }
        throw lastException ?: RuntimeException("Unknown error after retries")
    }

    private fun doStreamRequest(requestBody: String, onLine: (StreamLine) -> Unit) {
        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = 0 // 流式无超时
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            logNetworkSend(requestBody)

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw RuntimeException("Stream request failed: HTTP ${connection.responseCode} - $error")
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line!!
                    when {
                        currentLine.startsWith("data: ") -> {
                            val data = currentLine.substring(6)
                            logNetworkReceive(currentLine)
                            if (data == "[DONE]") {
                                onLine(StreamLine.Done)
                                break
                            } else {
                                onLine(StreamLine.Data(data))
                            }
                        }
                        currentLine.isEmpty() -> continue
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseStreamChunk(data: String): StreamChunk? {
        return try {
            val chunk: StreamChunkResponse = objectMapper.readValue(data)
            val choice = chunk.choices.firstOrNull() ?: return null
            val delta = choice.delta ?: return null

            StreamChunk.Content(
                content = delta.content ?: "",
                reasoningContent = delta.reasoning ?: delta.reasoningContent,
                finishReason = choice.finishReason,
                toolCalls = delta.toolCalls?.map { tc ->
                    // 流式模式下 tool call 分多个 delta 到达：
                    // 第一个 delta: 有 id、type、name，arguments 可能为空字符串
                    // 后续 delta: 只有 index 和部分 arguments，id/name 为 null
                    // 不能过滤掉后续 delta，否则参数会丢失
                    ToolCall(
                        id = tc.id ?: "",
                        type = tc.type ?: "function",
                        function = FunctionCall(
                            name = tc.function?.name ?: "",
                            arguments = tc.function?.arguments ?: ""
                        ),
                        index = tc.index
                    )
                } ?: emptyList()
            )
        } catch (e: Exception) {
            log("解析流式 chunk 失败: ${e.message}")
            null
        }
    }

    private fun doHttpPost(requestBody: String): String {
        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            logNetworkSend(requestBody)

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                logNetworkReceive(responseText)
                return responseText
            }

            val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: "No error body"

            if (responseCode == 429 || responseCode >= 500) {
                throw RetryableException("HTTP $responseCode: $errorBody")
            }
            throw RuntimeException("API request failed with code $responseCode: $errorBody")
        } finally {
            connection.disconnect()
        }
    }

    private class RetryableException(message: String) : Exception(message)

    private fun ChatMessage.toApiMessage(): ApiMessage = when (this) {
        is ChatMessage.System -> ApiMessage(role = "system", content = content)
        is ChatMessage.User -> ApiMessage(role = "user", content = content)
        is ChatMessage.Assistant -> {
            val apiToolCalls = toolCalls.ifEmpty { null }?.map {
                ApiToolCall(
                    id = it.id,
                    type = it.type,
                    function = ApiFunctionCall(name = it.function.name, arguments = it.function.arguments)
                )
            }
            ApiMessage(
                role = "assistant",
                content = if (apiToolCalls != null && content.isEmpty()) null else content,
                reasoningContent = reasoningContent,
                toolCalls = apiToolCalls
            )
        }
        is ChatMessage.Tool -> ApiMessage(
            role = "tool",
            content = content,
            toolCallId = toolCallId,
            name = name
        )
    }

    private fun ChatCompletionResponse.toChatResponse(): ChatResponse {
        val choice = choices.firstOrNull() ?: throw RuntimeException("No choices in response")
        val message = choice.message

        val toolCalls = message.toolCalls?.mapIndexedNotNull { idx, tc ->
            try {
                ToolCall(
                    id = tc.id ?: return@mapIndexedNotNull null,
                    type = tc.type ?: "function",
                    function = FunctionCall(
                        name = tc.function?.name ?: return@mapIndexedNotNull null,
                        arguments = tc.function.arguments ?: "{}"
                    ),
                    index = tc.index ?: idx
                )
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()

        val tokenUsage = usage?.let {
            TokenUsage(
                promptTokens = it.promptTokens ?: 0,
                completionTokens = it.completionTokens ?: 0,
                totalTokens = it.totalTokens ?: ((it.promptTokens ?: 0) + (it.completionTokens ?: 0))
            )
        }

        return ChatResponse(
            content = message.content ?: "",
            toolCalls = toolCalls,
            tokenUsage = tokenUsage,
            finishReason = choice.finishReason,
            reasoningContent = message.reasoningContent
        )
    }

    // ========== 数据类 ==========

    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<ApiMessage>,
        val tools: List<ToolDefinition>?,
        @JsonProperty("tool_choice") val toolChoice: String?,
        val temperature: Double,
        @JsonProperty("top_p") val topP: Double,
        val stream: Boolean
    )

    private data class ApiMessage(
        val role: String,
        val content: String? = null,
        @JsonProperty("reasoning_content") val reasoningContent: String? = null,
        @JsonProperty("tool_calls") val toolCalls: List<ApiToolCall>? = null,
        @JsonProperty("tool_call_id") val toolCallId: String? = null,
        val name: String? = null
    )

    private data class ApiToolCall(
        val id: String? = null,
        val type: String? = null,
        val index: Int? = null,
        val function: ApiFunctionCall? = null
    )

    private data class ApiFunctionCall(
        val name: String? = null,
        val arguments: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ChatCompletionResponse(
        val id: String? = null,
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Choice(
        val index: Int? = null,
        val message: ResponseMessage = ResponseMessage(),
        @JsonProperty("finish_reason") val finishReason: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ResponseMessage(
        val role: String? = null,
        val content: String? = null,
        @JsonProperty("reasoning_content") val reasoningContent: String? = null,
        @JsonProperty("tool_calls") val toolCalls: List<ApiToolCall>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Usage(
        @JsonProperty("prompt_tokens") val promptTokens: Int? = null,
        @JsonProperty("completion_tokens") val completionTokens: Int? = null,
        @JsonProperty("total_tokens") val totalTokens: Int? = null
    )

    // 流式响应数据类
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StreamChunkResponse(
        val id: String? = null,
        val choices: List<StreamChoice> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StreamChoice(
        val index: Int? = null,
        val delta: StreamDelta? = null,
        @JsonProperty("finish_reason") val finishReason: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StreamDelta(
        val role: String? = null,
        val content: String? = null,
        @JsonProperty("reasoning") val reasoning: String? = null,
        @JsonProperty("reasoning_content") val reasoningContent: String? = null,
        @JsonProperty("tool_calls") val toolCalls: List<ApiToolCall>? = null
    )

    private sealed class StreamLine {
        data class Data(val value: String) : StreamLine()
        object Done : StreamLine()
    }
}

// 公开的流式 chunk 类型
sealed class StreamChunk {
    data class Content(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoningContent: String? = null,
        val finishReason: String? = null  // "stop", "tool_calls", "length" 等
    ) : StreamChunk()
    object Done : StreamChunk()
}
