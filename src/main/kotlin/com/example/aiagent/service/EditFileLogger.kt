package com.example.aiagent.service

import com.example.aiagent.settings.AiAgentSettings
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Edit文件操作专用日志服务
 * 单独输出edit相关的日志到独立文件，方便排查edit环节问题
 */
class EditFileLogger {
    private val editLogFile = File("C:\\AiAgent\\logs", "edit_operations.log")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val separator = "=".repeat(80)
    private val subSeparator = "-".repeat(40)
    
    init {
        editLogFile.parentFile?.mkdirs()
        if (!editLogFile.exists()) {
            editLogFile.createNewFile()
        }
    }
    
    /**
     * 记录edit操作的完整信息
     */
    fun logEditOperation(
        operation: String,  // "PREVIEW" 或 "EXECUTE"
        filePath: String,
        startLine: Int,
        endLine: Int,
        newText: String,
        originalContent: String? = null,  // 原始文件内容（期望修改部分）
        actualContent: String? = null,     // 实际修改后的内容
        intent: String = "",               // 修改意图
        additionalInfo: Map<String, Any> = emptyMap()
    ) {
        if (!AiAgentSettings.instance.state.enableLogging) return
        
        val timestamp = LocalDateTime.now().format(formatter)
        val logMessage = buildString {
            appendLine(separator)
            appendLine("[$timestamp] Edit Operation: $operation")
            appendLine(subSeparator)
            appendLine("File: $filePath")
            appendLine("Line Range: $startLine - $endLine")
            appendLine("Intent: $intent")
            appendLine(subSeparator)
            
            // 新文本内容
            appendLine("NEW TEXT (User wants to insert/replace):")
            appendLine("```")
            appendLine(newText)
            appendLine("```")
            appendLine(subSeparator)
            
            // 原始文件内容（期望修改部分）
            if (originalContent != null) {
                appendLine("ORIGINAL CONTENT (Expected to modify):")
                appendLine("```")
                appendLine(originalContent)
                appendLine("```")
                appendLine(subSeparator)
            }
            
            // 实际修改后的内容
            if (actualContent != null) {
                appendLine("ACTUAL CONTENT (After modification):")
                appendLine("```")
                appendLine(actualContent)
                appendLine("```")
                appendLine(subSeparator)
            }
            
            // 额外信息
            if (additionalInfo.isNotEmpty()) {
                appendLine("ADDITIONAL INFO:")
                additionalInfo.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
                appendLine(subSeparator)
            }
            
            appendLine()
        }
        
        try {
            BufferedWriter(FileWriter(editLogFile, true)).use { writer ->
                writer.write(logMessage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 记录edit预览操作
     */
    fun logPreview(
        filePath: String,
        startLine: Int,
        endLine: Int,
        newText: String,
        originalContent: String,
        diff: String,
        lineChange: Int
    ) {
        logEditOperation(
            operation = "PREVIEW",
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            newText = newText,
            originalContent = originalContent,
            intent = "Preview edit before execution",
            additionalInfo = mapOf(
                "diff" to diff,
                "line_change" to lineChange
            )
        )
    }
    
    /**
     * 记录edit执行操作
     */
    fun logExecution(
        filePath: String,
        startLine: Int,
        endLine: Int,
        newText: String,
        originalContent: String,
        actualContent: String,
        success: Boolean,
        lineChange: Int,
        totalLinesAfter: Int
    ) {
        logEditOperation(
            operation = "EXECUTE",
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            newText = newText,
            originalContent = originalContent,
            actualContent = actualContent,
            intent = "Execute file edit",
            additionalInfo = mapOf(
                "success" to success,
                "line_change" to lineChange,
                "total_lines_after" to totalLinesAfter
            )
        )
    }
    
    /**
     * 记录edit错误
     */
    fun logError(
        filePath: String,
        startLine: Int,
        endLine: Int,
        newText: String,
        error: String,
        originalContent: String? = null
    ) {
        logEditOperation(
            operation = "ERROR",
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            newText = newText,
            originalContent = originalContent,
            intent = "Edit operation failed",
            additionalInfo = mapOf(
                "error" to error
            )
        )
    }
    
    /**
     * 清空edit日志
     */
    fun clearLogs() {
        try {
            editLogFile.delete()
            editLogFile.createNewFile()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    companion object {
        private val instance = EditFileLogger()
        
        fun logPreview(
            filePath: String,
            startLine: Int,
            endLine: Int,
            newText: String,
            originalContent: String,
            diff: String,
            lineChange: Int
        ) {
            instance.logPreview(filePath, startLine, endLine, newText, originalContent, diff, lineChange)
        }
        
        fun logExecution(
            filePath: String,
            startLine: Int,
            endLine: Int,
            newText: String,
            originalContent: String,
            actualContent: String,
            success: Boolean,
            lineChange: Int,
            totalLinesAfter: Int
        ) {
            instance.logExecution(filePath, startLine, endLine, newText, originalContent, actualContent, success, lineChange, totalLinesAfter)
        }
        
        fun logError(
            filePath: String,
            startLine: Int,
            endLine: Int,
            newText: String,
            error: String,
            originalContent: String? = null
        ) {
            instance.logError(filePath, startLine, endLine, newText, error, originalContent)
        }
        
        fun clearLogs() {
            instance.clearLogs()
        }
    }
}