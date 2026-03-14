package com.example.aiagent.service

import com.example.aiagent.settings.AiAgentSettings
import com.intellij.openapi.application.ApplicationManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogService {
    private val logFile = File("C:\\AiAgent\\logs", "ai_agent.log")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    init {
        logFile.parentFile?.mkdirs()
        logFile.delete()
        logFile.createNewFile()
    }
    
    fun log(message: String) {
        if (!AiAgentSettings.instance.state.enableLogging) return
        
        val timestamp = LocalDateTime.now().format(formatter)
        val logMessage = "[$timestamp] $message"
        
        try {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                writer.write(logMessage)
                writer.newLine()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    companion object {
        val instance: LogService get() = ApplicationManager.getApplication().getService(LogService::class.java)
        
        fun log(message: String) {
            instance.log(message)
        }
    }
}
