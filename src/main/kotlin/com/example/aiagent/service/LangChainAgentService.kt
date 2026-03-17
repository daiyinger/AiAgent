package com.example.aiagent.service

import com.example.aiagent.api.*
import com.example.aiagent.settings.AiAgentSettings
import com.example.aiagent.tools.ToolManager
import com.example.aiagent.tools.ToolResult
import com.example.aiagent.ui.ToolCallMessage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI Agent 服务
 * 支持流式响应、Token 优化、智能历史管理
 */
class LangChainAgentService(private val project: Project) {

    companion object {
        /** 工具调用最大轮数 */
        private const val MAX_TOOL_ROUNDS = 60
        /** 截断续传最大次数（finish_reason=length 时自动继续） */
        private const val MAX_CONTINUATION_ROUNDS = 5
        /** 最大历史消息数（保留最近 N 条） */
        private const val MAX_HISTORY_MESSAGES = 20
        /** 单条消息最大字符数 */
        private const val MAX_MESSAGE_LENGTH = 10240
        /** 工具结果截断长度 */
        private const val MAX_TOOL_RESULT_LENGTH = 6000
    }

    private fun log(message: String) = LogService.log(message)

    private var currentJob: Job? = null
    private val isCancelled = AtomicBoolean(false)

    fun stopCurrentSession() {
        log("停止当前会话")
        isCancelled.set(true)
        currentJob?.cancel()
        currentJob = null
    }

    fun resetCancellation() { isCancelled.set(false) }
    fun isCancelled(): Boolean = isCancelled.get()

    /**
     * 发送消息并获取流式响应
     * 支持多轮工具调用：模型可连续调用多个工具，直到给出最终回答
     */
    suspend fun sendMessage(
        message: String,
        onChunk: (String) -> Unit,
        onToolCall: (ToolCallMessage) -> Unit,
        onComplete: () -> Unit = {},
        onTokenUsage: (Int, Int) -> Unit = { _, _ -> },
        onToolOutput: ((String, String) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isCancelled.get()) {
                return@withContext Result.failure(Exception("Request cancelled"))
            }

            log("发送消息: ${message.take(100)}...")

            // 检查缓存
            checkCache(message)?.let { cached ->
                deliverCachedResponse(cached, onChunk, onComplete, onTokenUsage, message)
                return@withContext Result.success(Unit)
            }

            val settings = AiAgentSettings.instance.state
            val client = OpenAiClient.fromSettings(settings)
            val supportsFc = client.supportsFunctionCalling()

            // 构建优化后的消息列表
            val currentMessages = buildOptimizedMessageList(message, embedToolsInPrompt = !supportsFc)
            val tools = if (supportsFc) ToolDefinitions.getAllTools() else emptyList()

            log("模型=${settings.currentModel}, FC=$supportsFc, 消息数=${currentMessages.size}")

            val isDslMode = !supportsFc
            var finalResponse = StringBuilder()
            var totalInputTokens = 0
            var totalOutputTokens = 0

            // 多轮工具调用循环
            var round = 0
            while (round < MAX_TOOL_ROUNDS && !isCancelled.get()) {
                round++
                log("=== 工具调用第 $round 轮 ===")

                val roundResponse = StringBuilder()
                val toolCallBuffer = mutableListOf<ToolCall>()
                val reasoningContentBuffer = StringBuilder()  // DeepSeek 思维内容
                var lastFinishReason: String? = null
                var hasSentContentInStream = false  // 标记是否在流中发送过内容

                // 流式请求
                client.chatStream(currentMessages, tools).collect { chunk ->
                    if (isCancelled.get()) return@collect

                    when (chunk) {
                        is StreamChunk.Content -> {
                            if (chunk.toolCalls.isNotEmpty()) {
                                toolCallBuffer.addAll(chunk.toolCalls)
                            }
                            // 累积 reasoning_content（DeepSeek 思维模式）
                            if (!chunk.reasoningContent.isNullOrEmpty()) {
                                reasoningContentBuffer.append(chunk.reasoningContent)
                            }
                            // 记录 finish_reason（流式中通常在最后一个 chunk 出现）
                            if (chunk.finishReason != null) {
                                lastFinishReason = chunk.finishReason
                            }
                            if (chunk.content.isNotEmpty()) {
                                roundResponse.append(chunk.content)
                                // 实时发送文本内容，包括DSL模式
                                ApplicationManager.getApplication().invokeLater {
                                    onChunk(chunk.content)
                                }
                                hasSentContentInStream = true
                            }
                        }
                        is StreamChunk.Done -> { /* 流结束 */ }
                    }
                }

                log("第 $round 轮 finish_reason=$lastFinishReason")

                // 合并流式 tool call delta
                var resolvedToolCalls = mergeToolCalls(toolCallBuffer)
                log("FC 合并后 tool calls: ${resolvedToolCalls.size}")

                // 如果 FC 没有返回 tool calls，尝试从文本解析 DSL 格式
                if (resolvedToolCalls.isEmpty() && !isCancelled.get()) {
                    val responseText = roundResponse.toString()
                    val dslToolCalls = parseDslToolCalls(responseText)
                    if (dslToolCalls.isNotEmpty()) {
                        log("从文本解析到 ${dslToolCalls.size} 个 DSL 工具调用")
                        resolvedToolCalls = dslToolCalls
                        val cleanText = removeDslFromText(responseText)
                        roundResponse.clear()
                        roundResponse.append(cleanText)
                    }
                }

                // 只有在没有在流中实时发送过内容时，才一次性发送
                // 但是在发送工具调用之前，确保先发送已经累积的文本
                val cleanContent = roundResponse.toString()
                if (cleanContent.isNotEmpty() && !hasSentContentInStream && !isCancelled.get()) {
                    ApplicationManager.getApplication().invokeLater {
                        onChunk(cleanContent)
                    }
                    // 标记为已发送，避免重复发送
                    hasSentContentInStream = true
                }

                // ====== 处理 finish_reason = "length"（输出被截断） ======
                // 模型因 max_tokens 限制被截断，回复不完整
                // 将已输出的内容作为 assistant 消息加入历史，追加一轮让模型继续
                if (lastFinishReason == "length" && resolvedToolCalls.isEmpty()) {
                    val partialText = roundResponse.toString()
                    log("输出被截断 (finish_reason=length)，已输出 ${partialText.length} 字符，尝试续传")

                    // 将截断的内容作为 assistant 消息
                    val reasoningContent = reasoningContentBuffer.toString().ifEmpty { null }
                    currentMessages.add(ChatMessage.Assistant(
                        content = partialText,
                        reasoningContent = reasoningContent
                    ))
                    // 追加一条 user 消息请求继续
                    currentMessages.add(ChatMessage.User("请继续，从你上次停下的地方接着说。"))

                    // 续传计数器：最多续传 MAX_CONTINUATION_ROUNDS 次
                    var continuations = 0
                    while (continuations < MAX_CONTINUATION_ROUNDS && !isCancelled.get()) {
                        continuations++
                        log("续传第 $continuations 次")

                        val contResponse = StringBuilder()
                        var contFinishReason: String? = null

                        client.chatStream(currentMessages, tools).collect { chunk ->
                            if (isCancelled.get()) return@collect
                            when (chunk) {
                                is StreamChunk.Content -> {
                                    if (chunk.finishReason != null) contFinishReason = chunk.finishReason
                                    if (chunk.content.isNotEmpty()) {
                                        contResponse.append(chunk.content)
                                        ApplicationManager.getApplication().invokeLater {
                                            onChunk(chunk.content)
                                        }
                                    }
                                }
                                is StreamChunk.Done -> {}
                            }
                        }

                        roundResponse.append(contResponse)

                        // 如果不再是 length 截断，续传完成
                        if (contFinishReason != "length") {
                            log("续传完成，finish_reason=$contFinishReason")
                            break
                        }

                        // 继续被截断，追加到历史再请求
                        currentMessages.add(ChatMessage.Assistant(content = contResponse.toString()))
                        currentMessages.add(ChatMessage.User("请继续。"))
                    }

                    // 续传完毕，视为最终回答
                    finalResponse = roundResponse
                    break
                }

                // 没有工具调用 → 模型给出了最终回答，退出循环
                if (resolvedToolCalls.isEmpty()) {
                    finalResponse = roundResponse
                    log("第 $round 轮无工具调用，结束循环")
                    break
                }

                log("第 $round 轮收到 ${resolvedToolCalls.size} 个工具调用")

                // 添加 AI 响应（含 tool calls 和 reasoning_content）到消息历史
                val reasoningContent = reasoningContentBuffer.toString().ifEmpty { null }
                currentMessages.add(ChatMessage.Assistant(
                    content = roundResponse.toString(),
                    toolCalls = resolvedToolCalls,
                    reasoningContent = reasoningContent
                ))

                // 执行所有工具调用
                for (toolCall in resolvedToolCalls) {
                    if (isCancelled.get()) break

                    val resultContent = executeToolCall(toolCall, onToolCall, onToolOutput)

                    val truncatedResult = if (resultContent.length > MAX_TOOL_RESULT_LENGTH) {
                        resultContent.take(MAX_TOOL_RESULT_LENGTH) + "\n... [已截断，原长度 ${resultContent.length}]"
                    } else resultContent

                    currentMessages.add(ChatMessage.Tool(
                        toolCallId = toolCall.id,
                        name = toolCall.function.name,
                        content = truncatedResult
                    ))
                }

                // 继续下一轮：模型根据工具结果决定是否继续调用工具或给出最终回答
                finalResponse = StringBuilder()
            }

            if (round >= MAX_TOOL_ROUNDS) {
                log("已达到最大工具调用轮数: $MAX_TOOL_ROUNDS")
            }

            if (isCancelled.get()) {
                return@withContext Result.failure(Exception("Request cancelled"))
            }

            val responseText = finalResponse.toString()
            addToCache(message, responseText)

            // 估算 token
            val inputTokens = estimateTokenCount(buildString {
                currentMessages.forEach { append(it.content) }
            })
            val outputTokens = estimateTokenCount(responseText)

            ApplicationManager.getApplication().invokeLater {
                onTokenUsage(inputTokens, outputTokens)
                onComplete()
            }

            Result.success(Unit)
        } catch (e: Throwable) {
            log("发送消息错误: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * 从文本解析 DSL 格式工具调用
     * 支持多种格式:
     * 1. <｜DSML｜function_calls> 标准 DeepSeek 格式
     * 2. 容错：全角/半角竖线混用
     * 3. <tool_call> 格式（部分模型可能生成）
     */
    private fun parseDslToolCalls(text: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()

        log("解析 DSL，输入文本长度: ${text.length}")
        log("输入文本前300字符: ${text.take(300)}")

        // 尝试匹配 DSML 格式（兼容全角/半角竖线和可能的空格）
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

        // 如果 DSML 格式没解析到，尝试 <tool_call> 格式（部分模型可能生成）
        if (toolCalls.isEmpty()) {
            val toolCallRegex = """<tool_call>\s*(?:<tool_name>(\w+)</tool_name>|<(\w+)>)\s*([\s\S]*?)(?:</tool_call>|$)""".toRegex()
            toolCallRegex.findAll(text).forEachIndexed { index, match ->
                val toolName = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (toolName.isNotEmpty()) {
                    val paramsText = match.groupValues[3]
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

        // 尝试 JSON 格式 tool call（某些模型直接输出 JSON）
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
     * 从 XML 格式文本中提取参数 (<key>value</key> 格式)
     */
    private fun extractXmlParams(text: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val paramRegex = """<(\w+)>(.*?)</\w+>""".toRegex()
        paramRegex.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            if (key != "tool_name" && key != "parameters" && key != "tool_call") {
                params[key] = value
            }
        }
        return params
    }

    /**
     * 从 DSL 内容中提取参数
     */
    private fun extractDslArguments(callContent: String): Map<String, Any> {
        val argsMap = mutableMapOf<String, Any>()

        // 匹配 <arguments>...</arguments> 块
        val argsBlockMatch = """<arguments>([\s\S]*?)</arguments>""".toRegex().find(callContent)
        val argsContent = argsBlockMatch?.groupValues?.get(1) ?: ""

        // 匹配每个 <argument name="xxx" type="yyy">value</argument>
        // 或者 <argument name="xxx">value</argument>
        val argRegex = """<argument\s+name="([^"]+)"(?:\s+type="[^"]+")?>([^<]*)</argument>""".toRegex()
        argRegex.findAll(argsContent).forEach { argMatch ->
            val argName = argMatch.groupValues[1]
            val argValue = argMatch.groupValues[2].trim()
            argsMap[argName] = argValue
        }

        return argsMap
    }

    /**
     * 从文本中移除 DSL 标记，保留纯文本
     */
    private fun removeDslFromText(text: String): String {
        // 移除 DSML 格式（兼容全角/半角竖线）
        var result = text.replace("""<[｜|]\s*DSML\s*[｜|]\s*function_calls\s*>[\s\S]*?</[｜|]\s*DSML\s*[｜|]\s*function_calls\s*>""".toRegex(), "")
        // 移除 tool_call 格式
        result = result.replace("""<tool_call>[\s\S]*?</tool_call>""".toRegex(), "")
        return result.trim()
    }

    /**
     * 合并流式 tool call delta
     * 流式模式下，一个 tool call 会分多个 chunk 到达：
     * - 第一个 chunk: 包含 id、type、name，arguments 可能为空
     * - 后续 chunk: 只有 index 和部分 arguments，id/name 为空字符串
     * 必须按 index 分组合并，而非按 id（后续 chunk 的 id 为空）
     */
    private fun mergeToolCalls(buffer: List<ToolCall>): List<ToolCall> {
        if (buffer.isEmpty()) return emptyList()

        return buffer.groupBy { it.index ?: 0 }.map { (_, calls) ->
            // 从所有 delta 中找到第一个有效的 id 和 name
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
        }.filter { it.function.name.isNotEmpty() }  // 过滤掉没有名称的无效调用
    }

    /**
     * 执行单个工具调用
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun executeToolCall(
        toolCall: ToolCall,
        onToolCall: (ToolCallMessage) -> Unit,
        onToolOutput: ((String, String) -> Unit)?
    ): String {
        val toolName = toolCall.function.name
        val argumentsJson = toolCall.function.arguments
        val toolCallId = toolCall.id

        log("执行工具: $toolName")

        val params: Map<String, Any> = try {
            OpenAiClient.objectMapper.readValue(argumentsJson, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            emptyMap()
        }

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
                // 如果data是Map类型，将其合并到params中传递给UI，以便显示额外信息（如lineCount）
                val mergedParams = if (toolResult.data is Map<*, *>) {
                    val dataMap = toolResult.data as Map<*, *>
                    // 将原始params转换为可变的，并添加data中的键值对
                    val mutableParams = params.toMutableMap()
                    dataMap.forEach { (key, value) ->
                        if (key is String) {
                            // 如果值是Map，将其条目展开到mutableParams中
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
                    mutableParams.toMap()
                } else {
                    params
                }
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
     * 构建优化的消息列表（Token 优化）
     */
    private fun buildOptimizedMessageList(
        currentMessage: String,
        embedToolsInPrompt: Boolean = false
    ): MutableList<ChatMessage> {
        val chatStateService = ChatStateService.instance
        val history = chatStateService.currentSession?.messages ?: emptyList()

        // 1. 截断历史：只保留最近 N 条有效消息
        val recentHistory = history
            .filter { it.type in listOf("user", "ai") && it.content.isNotEmpty() }
            .takeLast(MAX_HISTORY_MESSAGES)

        log("历史消息: ${history.size} 条 -> 截断后 ${recentHistory.size} 条")

        return buildList {
            // 系统提示（精简版）
            add(ChatMessage.System(buildOptimizedSystemPrompt(embedToolsInPrompt)))

            // 历史消息（截断内容）
            recentHistory.forEach { msg ->
                val truncatedContent = if (msg.content.length > MAX_MESSAGE_LENGTH) {
                    msg.content.take(MAX_MESSAGE_LENGTH) + "\n... [已截断]"
                } else msg.content

                when (msg.type) {
                    "user" -> add(ChatMessage.User(truncatedContent))
                    "ai" -> add(ChatMessage.Assistant(truncatedContent))
                }
            }

            // 当前消息
            add(ChatMessage.User(currentMessage))
        }.toMutableList()
    }

    /**
     * 精简的系统提示，减少 Token 消耗
     * FC 模式：工具定义通过 API tools 参数传递，提示只需概要
     * 非 FC 模式：需要在提示中嵌入详细的工具说明和调用格式
     */
    private fun buildOptimizedSystemPrompt(embedToolsInPrompt: Boolean = false): String {
        if (!embedToolsInPrompt) {
            // FC 模式：工具定义已通过 API 传递
            return """
                你是 Android Studio 专家，擅长分析和修改项目。

                行为规则：
                1. 需要信息时直接调用工具，不要先描述你打算做什么
                2. 拿到工具结果后，如果还需要更多信息，继续调用工具
                3. 有足够信息后，直接给出完整的最终回答
                4. 路径用相对路径，修改文件前先读取内容
                5. 不要说"我来帮你..."或"让我先..."这样的过渡语句，直接行动

                可用工具: list_files, read_file, edit_file, search_files, analyze_project, compile_project
                编译建议: assemble(快速) > build(完整) > clean(清理后重新)
            """.trimIndent()
        }

        // 非 FC 模式：嵌入完整工具定义和调用格式说明
        val toolsDetailDesc = ToolDefinitions.getToolsDescription()
        return """
            你是 Android Studio 专家，擅长分析和修改项目。

            行为规则：
            1. 需要信息时直接输出工具调用DSL，不要先描述你打算做什么
            2. 拿到工具结果后，如果还需要更多信息，继续输出工具调用
            3. 有足够信息后，直接给出完整的最终回答
            4. 路径用相对路径，修改文件前先读取内容
            5. 不要说"我来帮你..."或"让我先..."这样的过渡语句，直接行动

            编译建议: assemble(快速) > build(完整) > clean(清理后重新)

            你拥有以下工具：
            $toolsDetailDesc

            当你需要使用工具时，请严格按照以下 DSL 格式输出（不要输出其他格式）：
            <｜DSML｜function_calls>
            <｜DSML｜invoke name="工具名称">
            <｜DSML｜function_call>
            <arguments>
            <argument name="参数名">参数值</argument>
            </arguments>
            </｜DSML｜function_call>
            </｜DSML｜invoke>
            </｜DSML｜function_calls>

            重要提示：
            - 每次只输出一个工具调用
            - 工具名称必须是上面列出的名称之一
            - 参数值不需要引号
            - 你可以连续调用多个工具来完成复杂任务
        """.trimIndent()
    }

    /**
     * 交付缓存的响应
     */
    private fun deliverCachedResponse(
        response: String,
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onTokenUsage: (Int, Int) -> Unit,
        originalMessage: String
    ) {
        ApplicationManager.getApplication().invokeLater {
            onTokenUsage(estimateTokenCount(originalMessage), estimateTokenCount(response))

            // 模拟流式发送
            response.forEach { char ->
                onChunk(char.toString())
                Thread.sleep(1)
            }
            onComplete()
        }
    }

    /**
     * 估算 Token 数（优化算法）
     */
    private fun estimateTokenCount(text: String): Int {
        // 中文约 2 token/字，英文约 0.25 token/字符，代码约 1 token/字符
        var count = 0
        for (char in text) {
            count += when {
                char.code > 127 -> 2  // 中文/Unicode
                char.isLetterOrDigit() -> 1
                char.isWhitespace() -> 0
                else -> 1
            }
        }
        return (count / 2).coerceAtLeast(1)
    }

    // ========== 缓存 ==========

    private val messageCache = linkedMapOf<String, String>()
    private val maxCacheSize = 20  // 减少缓存大小

    private fun checkCache(message: String): String? = messageCache[message]

    private fun addToCache(message: String, response: String) {
        if (message.length > 100 || response.length > 500) return  // 更严格的缓存条件
        if (messageCache.size >= maxCacheSize) {
            messageCache.remove(messageCache.keys.first())
        }
        messageCache[message] = response
    }

    fun clearCache() {
        log("清除消息缓存")
        messageCache.clear()
    }

    /**
     * 测试连接
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            log("测试连接...")
            val settings = AiAgentSettings.instance.state
            val client = OpenAiClient.fromSettings(settings)
            val response = client.chat(listOf(ChatMessage.User("Hi")))
            log("测试连接成功: ${response.content.take(50)}...")
            true
        } catch (e: Exception) {
            log("测试连接失败: ${e.message}")
            false
        }
    }
}
