package com.example.aiagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@State(
    name = "ChatStateService",
    storages = [Storage("chat-sessions.xml")]
)
class ChatStateService : PersistentStateComponent<ChatStateService.State> {
    
    data class SessionState(
        var id: String = "",
        var title: String = "新会话",
        var messages: MutableList<MessageState> = mutableListOf(),
        var timestamp: Long = System.currentTimeMillis()
    )
    
    data class MessageState(
        var id: String = "",
        var type: String = "user",
        var content: String = "",
        var timestamp: String = "",
        var toolName: String = "",
        var parameters: Map<String, String> = emptyMap(),
        var isExecuting: Boolean = false,
        var result: String? = null,
        var output: String = "",
        var isGenerating: Boolean = false,
        var inputTokens: Int = 0,
        var outputTokens: Int = 0,
        var totalTokens: Int = 0
    )
    
    data class State(
        var sessions: MutableList<SessionState> = mutableListOf(),
        var currentSessionIndex: Int = 0
    )
    
    private var state = State()
    
    init {
        if (state.sessions.isEmpty()) {
            state.sessions.add(SessionState(
                id = System.currentTimeMillis().toString(),
                title = "新会话"
            ))
        }
    }
    
    override fun getState(): State = state
    
    override fun loadState(loadedState: State) {
        this.state = loadedState
        if (this.state.sessions.isEmpty()) {
            this.state.sessions.add(SessionState(
                id = System.currentTimeMillis().toString(),
                title = "新会话"
            ))
        }
        if (this.state.currentSessionIndex >= this.state.sessions.size) {
            this.state.currentSessionIndex = 0
        }
    }
    
    val sessions: List<SessionState> get() = state.sessions
    
    var currentSessionIndex: Int
        get() = state.currentSessionIndex
        set(value) {
            state.currentSessionIndex = value.coerceIn(0, state.sessions.size - 1)
        }
    
    val currentSession: SessionState?
        get() = sessions.getOrNull(currentSessionIndex)
    
    fun addSession(session: SessionState) {
        state.sessions.add(session)
    }
    
    fun createNewSession(): SessionState {
        val newSession = SessionState(
            id = System.currentTimeMillis().toString(),
            title = "New Chat",
            timestamp = System.currentTimeMillis()
        )
        state.sessions.add(newSession)
        return newSession
    }
    
    fun removeSession(index: Int) {
        if (index in 0 until state.sessions.size) {
            state.sessions.removeAt(index)
            if (state.currentSessionIndex >= state.sessions.size) {
                state.currentSessionIndex = state.sessions.size - 1
            }
            if (state.sessions.isEmpty()) {
                state.sessions.add(SessionState(
                    id = System.currentTimeMillis().toString(),
                    title = "新会话"
                ))
                state.currentSessionIndex = 0
            }
        }
    }
    
    fun updateSessionTitle(index: Int, title: String) {
        if (index in 0 until state.sessions.size) {
            state.sessions[index].title = title
        }
    }
    
    fun addMessageToCurrentSession(message: MessageState) {
        currentSession?.messages?.add(message)

        if (message.type == "user" && currentSession != null) {
            val session = currentSession!!
            // 如果标题是默认标题，则使用第一条用户消息作为标题
            if ((session.title == "New Chat" || session.title == "新会话") && message.content.isNotBlank()) {
                val title = message.content.take(30).let {
                    if (message.content.length > 30) "$it..." else it
                }
                session.title = title
            }
        }
    }
    
    fun addMessageToCurrentSessionAtPosition(message: MessageState, position: Int) {
        currentSession?.let {
            if (position >= 0 && position <= it.messages.size) {
                it.messages.add(position, message)
            } else {
                it.messages.add(message)
            }
        }
    }
    
    fun updateLastAiMessageContent(content: String) {
        // 查找最后一条 AI 消息，而不是最后一条消息
        val lastAiMessage = currentSession?.messages?.lastOrNull { it.type == "ai" }
        if (lastAiMessage != null) {
            lastAiMessage.content = content
        }
    }
    
    fun setLastAiMessageGenerating(isGenerating: Boolean) {
        // 查找最后一条 AI 消息，而不是最后一条消息
        val lastAiMessage = currentSession?.messages?.lastOrNull { it.type == "ai" }
        if (lastAiMessage != null) {
            lastAiMessage.isGenerating = isGenerating
        }
    }
    
    fun updateToolCallMessage(messageId: String, isExecuting: Boolean, result: String?, output: String = "") {
        val currentSessionState = currentSession
        if (currentSessionState != null) {
            val msgIndex = currentSessionState.messages.indexOfFirst { it.id == messageId && it.type == "tool" }
            if (msgIndex >= 0) {
                currentSessionState.messages[msgIndex].isExecuting = isExecuting
                currentSessionState.messages[msgIndex].result = result
                if (output.isNotEmpty()) {
                    currentSessionState.messages[msgIndex].output = output
                }
            }
        }
    }
    
    fun clearCurrentSessionMessages() {
        currentSession?.messages?.clear()
    }
    
    companion object {
        val instance: ChatStateService
            get() = ApplicationManager.getApplication().getService(ChatStateService::class.java)
    }
}

fun LocalDateTime.toStateString(): String {
    return this.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

fun String.toLocalDateTime(): LocalDateTime {
    return try {
        LocalDateTime.parse(this, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    } catch (e: Exception) {
        LocalDateTime.now()
    }
}
