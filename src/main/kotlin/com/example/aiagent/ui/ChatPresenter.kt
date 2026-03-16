package com.example.aiagent.ui

import com.example.aiagent.service.ChatStateService
import com.example.aiagent.service.LangChainAgentService
import com.example.aiagent.settings.AiAgentSettings
import com.example.aiagent.service.LogService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

data class ChatUiState(
    val sessions: List<ChatStateService.SessionState>,
    val currentSessionIndex: Int,
    val messages: List<ChatMessage>,
    val isSending: Boolean
) {
    val currentSession: ChatStateService.SessionState? get() = sessions.getOrNull(currentSessionIndex)
}

class ChatPresenter(private val project: Project) {
    private val chatStateService = ChatStateService.instance
    private val langChainService = LangChainAgentService(project)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sending = AtomicBoolean(false)

    private val _uiState = MutableStateFlow(
        ChatUiState(
            sessions = chatStateService.sessions.toList(),
            currentSessionIndex = chatStateService.currentSessionIndex,
            messages = chatStateService.currentSession?.messages?.map { it.toChatMessage() } ?: emptyList(),
            isSending = false
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun dispose() {
        scope.cancel()
    }

    fun selectSession(index: Int) {
        chatStateService.currentSessionIndex = index
        _uiState.update {
            it.copy(
                sessions = chatStateService.sessions.toList(),
                currentSessionIndex = chatStateService.currentSessionIndex,
                messages = chatStateService.currentSession?.messages?.map { m -> m.toChatMessage() } ?: emptyList()
            )
        }
    }

    fun createNewSession() {
        chatStateService.createNewSession()
        chatStateService.currentSessionIndex = chatStateService.sessions.size - 1
        _uiState.update {
            it.copy(
                sessions = chatStateService.sessions.toList(),
                currentSessionIndex = chatStateService.currentSessionIndex,
                messages = emptyList(),
                isSending = false
            )
        }
    }

    fun removeSession(index: Int) {
        chatStateService.removeSession(index)
        _uiState.update {
            it.copy(
                sessions = chatStateService.sessions.toList(),
                currentSessionIndex = chatStateService.currentSessionIndex,
                messages = chatStateService.currentSession?.messages?.map { m -> m.toChatMessage() } ?: emptyList(),
                isSending = false
            )
        }
    }

    fun stop() {
        sending.set(false)
        _uiState.update { it.copy(isSending = false) }
    }

    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return

        val settings = AiAgentSettings.instance.state

        val userMessage = UserMessage(
            id = System.currentTimeMillis().toString(),
            content = content,
            timestamp = LocalDateTime.now()
        )

        val aiMessageId = (System.currentTimeMillis() + 1).toString()
        val aiMessageTimestamp = LocalDateTime.now()
        val initialAiMessage = AiMessage(
            id = aiMessageId,
            content = "",
            timestamp = aiMessageTimestamp,
            isGenerating = true,
            modelName = settings.currentModel
        )

        // 先落盘到 ChatStateService，保证历史一致
        chatStateService.addMessageToCurrentSession(userMessage.toMessageState())
        chatStateService.addMessageToCurrentSession(initialAiMessage.toMessageState())

        // 再更新 UI 状态
        _uiState.update { state ->
            state.copy(
                sessions = chatStateService.sessions.toList(),
                currentSessionIndex = chatStateService.currentSessionIndex,
                messages = state.messages + userMessage + initialAiMessage,
                isSending = true
            )
        }

        sending.set(true)

        scope.launch(Dispatchers.IO) {
            var currentContent = ""
            var tokenUsage: Pair<Int, Int>? = null

            val result = langChainService.sendMessage(
                message = content,
                onChunk = { chunk ->
                    currentContent += chunk
                    if (!sending.get()) return@sendMessage

                    _uiState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg is AiMessage && msg.id == aiMessageId) {
                                AiMessage(
                                    id = msg.id,
                                    content = currentContent,
                                    timestamp = aiMessageTimestamp,
                                    isGenerating = true,
                                    tokenUsage = tokenUsage,
                                    modelName = settings.currentModel
                                )
                            } else msg
                        }
                        state.copy(messages = updated)
                    }
                },
                onToolCall = { toolCall ->
                    if (!sending.get()) return@sendMessage

                    _uiState.update { state ->
                        val list = state.messages.toMutableList()
                        val existingIndex = list.indexOfFirst { it is ToolCallMessage && it.id == toolCall.id }
                        if (existingIndex >= 0) {
                            val existing = list[existingIndex] as ToolCallMessage
                            val updated = toolCall.copy(output = existing.output)
                            list[existingIndex] = updated
                            chatStateService.updateToolCallMessage(updated.id, updated.isExecuting, updated.result, updated.output)
                        } else {
                            val aiIndex = list.indexOfLast { it is AiMessage && it.id == aiMessageId }
                            val insertAt = if (aiIndex >= 0) aiIndex else list.size
                            list.add(insertAt, toolCall)
                            chatStateService.addMessageToCurrentSessionAtPosition(toolCall.toMessageState(), insertAt)
                        }
                        state.copy(messages = list)
                    }
                },
                onTokenUsage = { inputTokens, outputTokens ->
                    tokenUsage = Pair(inputTokens, outputTokens)
                    if (!sending.get()) return@sendMessage

                    _uiState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg is AiMessage && msg.id == aiMessageId) {
                                AiMessage(
                                    id = msg.id,
                                    content = msg.content,
                                    timestamp = msg.timestamp,
                                    isGenerating = true,
                                    tokenUsage = tokenUsage,
                                    modelName = msg.modelName
                                )
                            } else msg
                        }
                        state.copy(messages = updated)
                    }
                },
                onToolOutput = { toolName, output ->
                    if (!sending.get()) return@sendMessage

                    _uiState.update { state ->
                        val list = state.messages.toMutableList()
                        val toolCallIndex = list.indexOfLast { it is ToolCallMessage && it.toolName == toolName }
                        if (toolCallIndex >= 0) {
                            val existing = list[toolCallIndex] as ToolCallMessage
                            val updated = existing.copy(output = existing.output + output + "\n")
                            list[toolCallIndex] = updated
                            chatStateService.updateToolCallMessage(updated.id, updated.isExecuting, updated.result, updated.output)
                        }
                        state.copy(messages = list)
                    }
                }
            )

            sending.set(false)

            _uiState.update { state ->
                val updated = state.messages.map { msg ->
                    if (msg is AiMessage && msg.id == aiMessageId) {
                        AiMessage(
                            id = msg.id,
                            content = currentContent,
                            timestamp = aiMessageTimestamp,
                            isGenerating = false,
                            tokenUsage = tokenUsage,
                            modelName = settings.currentModel
                        )
                    } else msg
                }
                state.copy(
                    sessions = chatStateService.sessions.toList(),
                    currentSessionIndex = chatStateService.currentSessionIndex,
                    messages = updated,
                    isSending = false
                )
            }

            // 同步落盘最后 AI 内容/状态
            chatStateService.updateLastAiMessageContent(currentContent)
            chatStateService.setLastAiMessageGenerating(false)

            result.onFailure { e ->
                LogService.log("LangChain4j发送失败: ${e.message}")
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("AI Agent Notifications")
                    .createNotification("发送失败: ${e.message}", NotificationType.ERROR)
                    .notify(null)
            }
        }
    }
}

