package com.example.aiagent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "AiAgentSettings",
    storages = [Storage("ai-agent-settings.xml")]
)
class AiAgentSettings : PersistentStateComponent<AiAgentSettings.State> {
    
    data class Provider(
        var id: String = "",
        var name: String = "",
        var apiType: String = "ollama",
        var apiUrl: String = "http://localhost:11434",
        var apiKey: String = "",
        var selectedModels: MutableList<String> = mutableListOf(),
        var timeoutSeconds: Int = 60,
        var temperature: Double = 0.7,
        var topP: Double = 0.9,
        var contextLength: Int = 32768
    )
    
    data class SystemPrompt(
        var id: String = "",
        var name: String = "",
        var content: String = "",
        var isDefault: Boolean = false
    )
    
    data class State(
        var providers: MutableList<Provider> = mutableListOf(
            Provider(
                id = "default",
                name = "默认本地Ollama",
                apiType = "ollama",
                apiUrl = "http://localhost:11434",
                selectedModels = mutableListOf()
            )
        ),
        var currentProviderId: String = "default",
        var currentModel: String = "",
        var enableLogging: Boolean = false,
        var systemPrompts: MutableList<SystemPrompt> = mutableListOf(
            SystemPrompt(
                id = "default",
                name = "默认助手",
                content = "你是一个有帮助的AI助手，请用中文回答问题。",
                isDefault = true
            ),
            SystemPrompt(
                id = "android-dev",
                name = "Android 开发者",
                content = "你是 Android Studio 专家，擅长分析和修改项目。\n\n行为规则：\n1. 需要信息时直接调用工具，不要先描述你打算做什么\n2. 拿到工具结果后，如果还需要更多信息，继续调用工具\n3. 有足够信息后，直接给出完整的最终回答\n4. 路径用相对路径，修改文件前先读取内容\n5. 不要说\"我来帮你...\"或\"让我先...\"这样的过渡语句，直接行动",
                isDefault = false
            )
        ),
        var currentSystemPromptId: String = "android-dev"
    )
    
    private var state = State()
    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        this.state = state
        migrateOldSettings()
    }
    
    private fun migrateOldSettings() {
        this.state.providers.forEach { provider ->
            if (!provider.apiUrl.startsWith("http")) {
                provider.apiUrl = "http://localhost:11434"
            }
        }
    }
    
    companion object {
        val instance: AiAgentSettings get() = ApplicationManager.getApplication().getService(AiAgentSettings::class.java)
    }
}
