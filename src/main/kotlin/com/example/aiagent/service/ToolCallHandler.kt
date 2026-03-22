package com.example.aiagent.service

import com.example.aiagent.api.*
import com.example.aiagent.exceptions.AiAgentException
import com.example.aiagent.exceptions.ExceptionUtils
import com.example.aiagent.tools.ToolManager
import com.example.aiagent.tools.ToolResult
import com.example.aiagent.ui.ToolCallMessage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.time.LocalDateTime

/**
 * 工具调用处理器
 * 负责工具调用执行、合并、DSL 解析
 */
class ToolCallHandler(private val project: Project) {
    
    private fun log(message: String) = LogService.log("[ToolCallHandler] $message")
    
    /**
     * 执行单个工具调用
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun executeToolCall(
        toolCall: ToolCall,
        onToolCall: (ToolCallMessage) -> Unit,
        onToolOutput: ((String, String) -> Unit)?
    ): String {
        val toolName = toolCall.function.name
        val argumentsJson = toolCall.function.arguments
        val toolCallId = toolCall.id

        log("执行工具: $toolName")
        log("工具参数JSON: $argumentsJson")

        val params: Map<String, Any> = try {
            OpenAiClient.objectMapper.readValue(argumentsJson, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            log("解析工具参数失败: ${e.message}")
            emptyMap()
        }
        log("解析后的参数: $params")

        // 通知 UI 开始
        notifyToolCallUI(onToolCall, toolCallId, toolName, params, isExecuting = true)

        // 执行工具
        val toolResult = try {
            ToolManager.executeTool(project, toolName, params) { outputLine ->
                ApplicationManager.getApplication().invokeLater {
                    onToolOutput?.invoke(toolName, outputLine)
                }
            }
        } catch (e: Exception) {
            ToolResult.Error("工具执行异常: ${e.message}")
        }

        // 处理结果
        return when (toolResult) {
            is ToolResult.Success -> {
                val result = toolResult.data.toString()
                val mergedParams = mergeResultToParams(toolResult.data, params)
                notifyToolCallUI(onToolCall, toolCallId, toolName, mergedParams,
                    isExecuting = false, result = "成功", output = result)
                result
            }
            is ToolResult.Error -> {
                notifyToolCallUI(onToolCall, toolCallId, toolName, params,
                    isExecuting = false, result = "失败: ${toolResult.message}")
                "Error: ${toolResult.message}"
            }
            is ToolResult.Progress -> {
                notifyToolCallUI(onToolCall, toolCallId, toolName, params,
                    isExecuting = true, result = "进行中: ${toolResult.message}")
                "Progress: ${toolResult.message}"
            }
            is ToolResult.OutputUpdate -> {
                toolResult.output
            }
        }
    }
    
    /**
     * 将工具结果合并到参数中
     */
    private fun mergeResultToParams(data: Any, params: Map<String, Any>): Map<String, Any> {
        if (data !is Map<*, *>) return params
        
        val dataMap = data as Map<*, *>
        val mutableParams = params.toMutableMap()
        
        dataMap.forEach { (key, value) ->
            if (key is String) {
                if (value is Map<*, *>) {
                    value.forEach { (nestedKey, nestedValue) ->
                        if (nestedKey is String) {
                            mutableParams[nestedKey] = nestedValue as Any
                        }
                    }
                } else {
                    mutableParams[key] = value as Any
                }
            }
        }
        
        return mutableParams.toMap()
    }
    
    /**
     * 通知 UI 工具调用状态
     */
    private fun notifyToolCallUI(
        onToolCall: (ToolCallMessage) -> Unit,
        id: String,
        toolName: String,
        params: Map<String, Any>,
        isExecuting: Boolean,
        result: String? = null,
        output: String = ""
    ) {
        ApplicationManager.getApplication().invokeLater {
            onToolCall(ToolCallMessage(
                id = id,
                toolName = toolName,
                parameters = params,
                timestamp = LocalDateTime.now(),
                isExecuting = isExecuting,
                result = result,
                output = output
            ))
        }
    }
    
    /**
     * 合并流式 tool call delta
     */
    fun mergeToolCalls(buffer: List<ToolCall>): List<ToolCall> {
        if (buffer.isEmpty()) return emptyList()

        return buffer.groupBy { it.index ?: 0 }.map { (_, calls) ->
            val firstWithId = calls.firstOrNull { it.id.isNotEmpty() }
            val firstWithName = calls.firstOrNull { it.function.name.isNotEmpty() }

            ToolCall(
                id = firstWithId?.id ?: "merged_${System.currentTimeMillis()}",
                type = calls.first().type,
                function = FunctionCall(
                    name = firstWithName?.function?.name ?: "",
                    arguments = calls.joinToString("") { it.function.arguments }
                ),
                index = calls.first().index
            )
        }.filter { it.function.name.isNotEmpty() }
    }
    
    /**
     * 从文本解析 DSL 格式工具调用
     */
    fun parseDslToolCalls(text: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()

        log("解析 DSL，输入文本长度: ${text.length}")

        // 尝试匹配 DSML 格式
        val functionCallsRegex = """<[｜|]\s*DSML\s*[｜|]\s*function_calls\s*>([\s\S]*?)</[｜|]\s*DSML\s*[｜|]\s*function_calls\s*>""".toRegex()
        val callsBlockMatch = functionCallsRegex.find(text)

        if (callsBlockMatch != null) {
            val callsBlock = callsBlockMatch.groupValues[1]
            log("找到 DSML function_calls 块，内容长度: ${callsBlock.length}")

            // 尝试 invoke 标签格式
            val invokeRegex = """<[｜|]\s*DSML\s*[｜|]\s*invoke\s+name="([^"]+)"[^>]*>([\s\S]*?)</[｜|]\s*DSML\s*[｜|]\s*invoke\s*>""".toRegex()
            val functionCallRegex = """<[｜|]\s*DSML\s*[｜|]\s*function_call\s*>([\s\S]*?)</[｜|]\s*DSML\s*[｜|]\s*function_call\s*>""".toRegex()

            val invokeMatches = invokeRegex.findAll(callsBlock).toList()
            log("找到 ${invokeMatches.size} 个 invoke 标签")

            if (invokeMatches.isNotEmpty()) {
                invokeMatches.forEachIndexed { index, invokeMatch ->
                    val invokeName = invokeMatch.groupValues[1]
                    val invokeContent = invokeMatch.groupValues[2]

                    val funcMatch = functionCallRegex.find(invokeContent)
                    val callContent = funcMatch?.groupValues?.get(1)?.trim() ?: invokeContent.trim()

                    val argsMap = extractDslArguments(callContent)
                    log("解析 DSL (invoke): name=$invokeName, args=$argsMap")

                    toolCalls.add(ToolCall(
                        id = "dsl_${System.currentTimeMillis()}_$index",
                        type = "function",
                        function = FunctionCall(
                            name = invokeName,
                            arguments = OpenAiClient.objectMapper.writeValueAsString(argsMap)
                        )
                    ))
                }
            } else {
                // 回退到直接解析 function_call
                functionCallRegex.findAll(callsBlock).forEachIndexed { index, match ->
                    val callContent = match.groupValues[1].trim()

                    val nameMatch = """<name>([^<]+)</name>""".toRegex().find(callContent)
                    val name = nameMatch?.groupValues?.get(1)?.trim() ?: return@forEachIndexed

                    val argsMap = extractDslArguments(callContent)
                    log("解析 DSL (direct): name=$name, args=$argsMap")

                    toolCalls.add(ToolCall(
                        id = "dsl_${System.currentTimeMillis()}_$index",
                        type = "function",
                        function = FunctionCall(
                            name = name,
                            arguments = OpenAiClient.objectMapper.writeValueAsString(argsMap)
                        )
                    ))
                }
            }
        }

        // 尝试 <tool_call> 格式
        if (toolCalls.isEmpty()) {
            val toolCallRegex = """<tool_call>\s*(?:(?:<tool_name>(\w+)</tool_name>)|(?:<function=(\w+)>)|(?:<(\w+)>))\s*([\s\S]*?)(?:</tool_call>|$)""".toRegex()
            toolCallRegex.findAll(text).forEachIndexed { index, match ->
                val toolName = match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }
                if (toolName.isNotEmpty()) {
                    val paramsText = match.groupValues[4]
                    val argsMap = extractXmlParams(paramsText)
                    log("解析 tool_call 格式: name=$toolName, args=$argsMap")

                    toolCalls.add(ToolCall(
                        id = "xml_${System.currentTimeMillis()}_$index",
                        type = "function",
                        function = FunctionCall(
                            name = toolName,
                            arguments = OpenAiClient.objectMapper.writeValueAsString(argsMap)
                        )
                    ))
                }
            }
        }

        // 尝试 JSON 格式
        if (toolCalls.isEmpty()) {
            val jsonToolCallRegex = """\{\s*"name"\s*:\s*"(\w+)"\s*,\s*"(?:arguments|parameters)"\s*:\s*(\{[^}]*\})\s*\}""".toRegex()
            jsonToolCallRegex.findAll(text).forEachIndexed { index, match ->
                val name = match.groupValues[1]
                val args = match.groupValues[2]
                if (name.isNotEmpty()) {
                    log("解析 JSON 格式 tool call: name=$name, args=$args")
                    toolCalls.add(ToolCall(
                        id = "json_${System.currentTimeMillis()}_$index",
                        type = "function",
                        function = FunctionCall(name = name, arguments = args)
                    ))
                }
            }
        }

        log("DSL 解析结果: ${toolCalls.size} 个工具调用")
        return toolCalls
    }
    
    /**
     * 从 XML 格式文本中提取参数
     */
    private fun extractXmlParams(text: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        val paramRegex1 = """<(\w+)>(.*?)</\w+>""".toRegex()
        paramRegex1.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            if (key !in listOf("tool_name", "parameters", "tool_call", "parameter", "function")) {
                params[key] = value
            }
        }
        
        val paramRegex2 = """<parameter=(\w+)>\s*([\s\S]*?)\s*</parameter>""".toRegex()
        paramRegex2.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            params[key] = value
            log("提取参数: $key = $value")
        }
        
        return params
    }
    
    /**
     * 从 DSL 内容中提取参数
     */
    private fun extractDslArguments(callContent: String): Map<String, Any> {
        val argsMap = mutableMapOf<String, Any>()

        val argsBlockMatch = """<arguments>([\s\S]*?)</arguments>""".toRegex().find(callContent)
        val argsContent = argsBlockMatch?.groupValues?.get(1) ?: ""

        val argRegex = """<argument\s+name="([^"]+)"(?:\s+type="[^"]+")?>([^<]*)</argument>""".toRegex()
        argRegex.findAll(argsContent).forEach { argMatch ->
            val argName = argMatch.groupValues[1]
            val argValue = argMatch.groupValues[2].trim()
            argsMap[argName] = argValue
        }

        return argsMap
    }
    
    /**
     * 从文本中移除 DSL 标记
     */
    fun removeDslFromText(text: String): String {
        var result = text.replace("""<[｜|]\s*DSML\s*[｜|]\s*function_calls\s*>[\s\S]*?</[｜|]\s*DSML\s*[｜|]\s*function_calls\s*>""".toRegex(), "")
        result = result.replace("""<tool_call>[\s\S]*?</tool_call>""".toRegex(), "")
        result = result.replace("""\n{3,}""".toRegex(), "\n\n")
        result = result.replace("\r\n", "\n")
        return result
    }
}