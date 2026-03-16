package com.example.aiagent.api

import com.example.aiagent.tools.ToolManager

/**
 * 工具定义构建器
 * 将项目中的工具转换为 OpenAI Function Calling 格式
 */
object ToolDefinitions {

    /** 工具结果截断阈值：超过此长度的结果会被截断 */
    private const val MAX_TOOL_RESULT_LENGTH = 8000

    /**
     * 获取所有可用工具的定义（OpenAI Function Calling schema）
     */
    fun getAllTools(): List<ToolDefinition> {
        return ToolManager.getAllTools().map { tool ->
            val requiredParams = tool.parameters.filter { it.required }.map { it.name }
            val properties = tool.parameters.associate { param ->
                param.name to PropertyDefinition(
                    type = mapParameterType(param.type),
                    description = param.description
                )
            }

            ToolDefinition(
                function = FunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = ParametersDefinition(
                        properties = properties.ifEmpty { null },
                        required = requiredParams.ifEmpty { null }
                    )
                )
            )
        }
    }

    /**
     * 获取工具定义的文本描述（用于系统提示）
     */
    fun getToolsDescription(): String {
        return ToolManager.getAllTools().joinToString("\n\n") { tool ->
            buildString {
                appendLine("工具: ${tool.name}")
                appendLine("描述: ${tool.description}")
                if (tool.parameters.isNotEmpty()) {
                    appendLine("参数:")
                    tool.parameters.forEach { param ->
                        val required = if (param.required) " (必需)" else " (可选)"
                        appendLine("  - ${param.name} (${param.type}): ${param.description}$required")
                    }
                }
            }
        }
    }

    /**
     * 截断过长的工具结果，避免超出 context window
     */
    fun truncateToolResult(result: String, maxLength: Int = MAX_TOOL_RESULT_LENGTH): String {
        if (result.length <= maxLength) return result
        val truncated = result.take(maxLength)
        return "$truncated\n\n... [结果已截断，共 ${result.length} 字符，仅显示前 $maxLength 字符]"
    }

    private fun mapParameterType(type: String): String = when (type.lowercase()) {
        "string", "str" -> "string"
        "int", "integer", "long" -> "integer"
        "double", "float", "number" -> "number"
        "boolean", "bool" -> "boolean"
        "array", "list" -> "array"
        "object", "map" -> "object"
        else -> "string"
    }
}
