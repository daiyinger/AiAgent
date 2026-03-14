package com.example.aiagent.tools

import com.intellij.openapi.project.Project

abstract class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
) {
    abstract suspend fun execute(project: Project, params: Map<String, Any>, onOutput: ((String) -> Unit)? = null): ToolResult
}

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

sealed class ToolResult {
    data class Success(val data: Any) : ToolResult()
    data class Error(val message: String) : ToolResult()
    data class Progress(val message: String) : ToolResult()
    data class OutputUpdate(val output: String) : ToolResult()
}