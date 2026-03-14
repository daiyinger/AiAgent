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
    
    data class State(
        var providers: MutableList<Provider> = mutableListOf(
            Provider(
                id = "default",
                name = "默认本地Ollama",
                apiType = "ollama",
                apiUrl = "http://localhost:11434",
                selectedModels = mutableListOf("llama2")
            )
        ),
        var currentProviderId: String = "default",
        var currentModel: String = "llama2",
        var enableLogging: Boolean = false
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
