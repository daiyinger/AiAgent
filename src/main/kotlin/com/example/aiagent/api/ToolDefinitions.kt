package com.example.aiagent.api

import com.example.aiagent.tools.ToolManager

/**
 * 工具定义构建器
 * 将项目中的工具转换为 OpenAI Function Calling 格式
 */
object ToolDefinitions {

    /** 工具结果截断阈值：超过此长度的结果会被截断 */
    private const val MAX_TOOL_RESULT_LENGTH = 10240

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
     * Get tool definitions text description (for system prompt)
     */
    fun getToolsDescription(): String {
        return ToolManager.getAllTools().joinToString("\n") { tool ->
            buildString {
                append("${tool.name}")
                if (tool.parameters.isNotEmpty()) {
                    val params = tool.parameters.map { param ->
                        val required = if (param.required) "*" else ""
                        "${param.name}$required"
                    }.joinToString(", ")
                    append("($params)")
                }
                append(": ${tool.description}")
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
