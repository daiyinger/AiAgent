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
        var messages: MutableList<MessageState> = mutableListOf()
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
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val timestamp = LocalDateTime.now().format(formatter)
        val newSession = SessionState(
            id = System.currentTimeMillis().toString(),
            title = "会话 $timestamp"
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
            if (session.title.startsWith("会话 ") && message.content.isNotBlank()) {
                val title = message.content.take(30).let { 
                    if (message.content.length > 30) "$it..." else it 
                }
                session.title = title
            }
        }
    }
    
    fun updateLastAiMessageContent(content: String) {
        val lastMessage = currentSession?.messages?.lastOrNull()
        if (lastMessage != null && lastMessage.type == "ai") {
            lastMessage.content = content
        }
    }
    
    fun setLastAiMessageGenerating(isGenerating: Boolean) {
        val lastMessage = currentSession?.messages?.lastOrNull()
        if (lastMessage != null && lastMessage.type == "ai") {
            lastMessage.isGenerating = isGenerating
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
