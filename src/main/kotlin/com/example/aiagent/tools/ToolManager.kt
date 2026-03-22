package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

object ToolManager {
    private val tools = mutableMapOf<String, Tool>()

    private val isToolCancelled = AtomicBoolean(false)

    fun cancelToolExecution() {
        isToolCancelled.set(true)
    }

    fun resetToolCancellation() {
        isToolCancelled.set(false)
    }

    fun isToolCancelled(): Boolean = isToolCancelled.get()

    init {
        registerTools()
    }

    private fun registerTools() {
        registerTool(ReadFileTool())
        registerTool(EditFileTool())
        registerTool(PreviewEditTool())  // 新增：编辑预览工具
        registerTool(DeleteFileTool())
        registerTool(CreateDirectoryTool())
        registerTool(BuildTool())
        registerTool(SearchFilesTool())
        registerTool(ListFilesTool())
        registerTool(AndroidProjectAnalysisTool())
        registerTool(CompileProjectTool())
        registerTool(WebSearchTool())
        registerTool(GetCurrentTimeTool())
        registerTool(ShellCommandTool())
    }

    private fun registerTool(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): Tool? = tools[name]

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getToolDefinitions(): String {
        return tools.values.joinToString("\n") { tool ->
            """
            |Tool: ${tool.name}
            |Description: ${tool.description}
            |Parameters:
            |${tool.parameters.joinToString("\n") { param ->
                "  - ${param.name} (${param.type}): ${param.description}${if (param.required) " (required)" else " (optional)"}"
            }}
            """.trimMargin()
        }
    }

    suspend fun executeTool(
        project: Project,
        toolName: String,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)? = null
    ): ToolResult {
        val tool = getTool(toolName) ?: return ToolResult.Error("Tool '$toolName' not found")

        return try {
            tool.execute(project, params, onOutput) { isToolCancelled() }
        } catch (e: Exception) {
            ToolResult.Error("Error executing tool '$toolName': ${e.message}")
        }
    }
}