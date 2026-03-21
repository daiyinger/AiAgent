# IntelliJ IDEA AI Agent 插件详细设计文档

## 1. 概述

### 1.1 项目背景

AI Agent 是一个为 IntelliJ IDEA / Android Studio 开发的 AI 辅助编程插件。通过集成多种大语言模型（LLM），为开发者提供智能对话、代码分析、文件操作、项目构建等功能，提升开发效率。

### 1.2 设计目标

- **多模型支持**：支持 OpenAI、Ollama、DeepSeek 等多种 AI 模型
- **工具调用**：AI 可自动调用工具执行文件操作、构建项目等任务
- **流式响应**：实时显示 AI 回复，提供流畅的交互体验
- **会话管理**：支持多会话，持久化保存对话历史
- **可扩展性**：易于添加新工具和功能

### 1.3 技术选型

| 技术 | 版本 | 用途 |
|------|------|------|
| Kotlin | 2.1.20 | 主要开发语言 |
| IntelliJ Platform Plugin SDK | 2.10.2 | 插件开发框架 |
| JetBrains Compose | - | UI 框架 |
| Jackson | 2.19.2 | JSON 序列化 |
| Jsoup | 1.17.2 | HTML 解析 |
| JTokkit | 1.0.0 | Token 计算 |
| JDK | 21 | 运行环境 |

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI Layer                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │  ChatPanel   │  │SettingsPanel │  │ SessionManagerPanel  │  │
│  └──────┬───────┘  └──────────────┘  └──────────────────────┘  │
└─────────┼───────────────────────────────────────────────────────┘
          │
┌─────────▼───────────────────────────────────────────────────────┐
│                      Service Layer                               │
│  ┌────────────────────┐  ┌─────────────────────────────────┐   │
│  │ LangChainAgent     │  │ ChatStateService                │   │
│  │ Service            │  │ (会话状态持久化)                  │   │
│  └─────────┬──────────┘  └─────────────────────────────────┘   │
│            │              ┌─────────────────────────────────┐   │
│            │              │ AiAgentSettings                 │   │
│            │              │ (配置持久化)                      │   │
│            │              └─────────────────────────────────┘   │
└────────────┼─────────────────────────────────────────────────────┘
             │
┌────────────▼─────────────────────────────────────────────────────┐
│                       API Layer                                   │
│  ┌────────────────────┐  ┌─────────────────────────────────┐    │
│  │   OpenAiClient     │  │      ToolDefinitions            │    │
│  │ (HTTP/SSE通信)      │  │ (工具定义转换)                    │    │
│  └─────────┬──────────┘  └─────────────────────────────────┘    │
│            │             ┌─────────────────────────────────┐    │
│            │             │      ChatMessage                │    │
│            │             │ (消息类型定义)                    │    │
│            │             └─────────────────────────────────┘    │
└────────────┼─────────────────────────────────────────────────────┘
             │
┌────────────▼─────────────────────────────────────────────────────┐
│                      Tool Layer                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                     ToolManager                           │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────────────┐ │   │
│  │  │ReadFile │ │EditFile │ │WebSearch│ │ ShellCommand    │ │   │
│  │  │  Tool   │ │  Tool   │ │  Tool   │ │     Tool        │ │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────────────┘ │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────────────┐ │   │
│  │  │ListFiles│ │BuildTool│ │DeleteFil│ │ CompileProject  │ │   │
│  │  │  Tool   │ │         │ │  eTool  │ │     Tool        │ │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────────────┘ │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 模块划分

| 模块 | 包路径 | 职责 |
|------|--------|------|
| UI 层 | `com.example.aiagent.ui` | 用户界面展示与交互 |
| 服务层 | `com.example.aiagent.service` | 业务逻辑处理 |
| API 层 | `com.example.aiagent.api` | 外部 API 通信 |
| 工具层 | `com.example.aiagent.tools` | 工具定义与执行 |
| 配置层 | `com.example.aiagent.settings` | 配置管理 |

---

## 3. 核心模块详细设计

### 3.1 API 层

#### 3.1.1 OpenAiClient

**职责**：与 OpenAI 兼容 API 进行通信，支持流式和非流式响应。

**类图**：
```
┌─────────────────────────────────────────────────────────────┐
│                      OpenAiClient                           │
├─────────────────────────────────────────────────────────────┤
│ - baseUrl: String                                           │
│ - apiKey: String                                            │
│ - model: String                                             │
│ - timeoutSeconds: Int                                       │
│ - temperature: Double                                       │
│ - topP: Double                                              │
├─────────────────────────────────────────────────────────────┤
│ + chatStream(messages, tools): Flow<StreamChunk>           │
│ + chat(messages, tools): ChatResponse                       │
│ + supportsFunctionCalling(): Boolean                        │
│ - doStreamRequest(requestBody, onLine)                      │
│ - doHttpPost(requestBody): String                           │
│ - parseStreamChunk(data): StreamChunk?                      │
│ + fromSettings(settings): OpenAiClient                      │
└─────────────────────────────────────────────────────────────┘
```

**关键设计**：

1. **流式响应处理**：使用 Kotlin Flow 和 `callbackFlow` 处理 SSE（Server-Sent Events）流
   ```kotlin
   fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>): Flow<StreamChunk>
   ```

2. **Function Calling 支持**：自动检测模型是否支持工具调用
   ```kotlin
   fun supportsFunctionCalling(): Boolean {
       // 某些模型（如 deepseek-reasoner, o1-mini）不支持 Function Calling
   }
   ```

3. **重试机制**：对 429（限流）和 5xx 错误自动重试
   ```kotlin
   private val maxRetries: Int = 2
   ```

#### 3.1.2 ChatMessage（消息类型）

**职责**：定义聊天消息的数据结构。

**类继承关系**：
```
                    ChatMessage (sealed class)
                           │
       ┌───────────┬───────┴───────┬────────────┐
       │           │               │            │
   System       User          Assistant       Tool
   (系统提示)   (用户消息)    (AI回复)      (工具结果)
```

**数据结构**：
```kotlin
sealed class ChatMessage {
    abstract val content: String
    abstract val role: String

    data class System(override val content: String) : ChatMessage()
    data class User(override val content: String) : ChatMessage()
    data class Assistant(
        override val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoningContent: String? = null  // DeepSeek 思维链
    ) : ChatMessage()
    data class Tool(
        val toolCallId: String,
        val name: String,
        override val content: String
    ) : ChatMessage()
}
```

#### 3.1.3 ToolDefinitions

**职责**：将项目工具转换为 OpenAI Function Calling Schema。

**转换流程**：
```
Tool (项目定义)
    │
    ▼ ToolDefinitions.getAllTools()
ToolDefinition (OpenAI Schema)
    │
    ▼ API 请求
大模型
```

---

### 3.2 服务层

#### 3.2.1 LangChainAgentService

**职责**：核心 AI 服务，处理消息发送、工具调用、多轮对话。

**状态图**：
```
┌─────────┐    sendMessage()    ┌──────────────┐
│  Idle   │ ─────────────────► │   Sending    │
└─────────┘                     └──────┬───────┘
     ▲                                 │
     │                                 ▼
     │                          ┌──────────────┐
     │                          │  Receiving   │
     │                          │   Stream     │
     │                          └──────┬───────┘
     │                                 │
     │                    ┌────────────┴────────────┐
     │                    ▼                         ▼
     │             ┌──────────────┐          ┌──────────────┐
     │             │  Tool Call   │          │   Complete   │
     │             │  Detected    │          │              │
     │             └──────┬───────┘          └──────┬───────┘
     │                    │                         │
     │                    ▼                         │
     │             ┌──────────────┐                 │
     │             │   Execute    │                 │
     │             │    Tools     │                 │
     │             └──────┬───────┘                 │
     │                    │                         │
     └────────────────────┴─────────────────────────┘
```

**核心流程**：

1. **消息发送流程**：
   ```kotlin
   suspend fun sendMessage(
       message: String,
       onChunk: (String) -> Unit,           // 文本块回调
       onReasoningChunk: (String) -> Unit,  // 思维链回调
       onToolCall: (ToolCallMessage) -> Unit, // 工具调用回调
       onComplete: () -> Unit,              // 完成回调
       onTokenUsage: (Int, Int) -> Unit,    // Token 使用回调
       onToolOutput: ((String, String) -> Unit)? // 工具输出回调
   ): Result<Unit>
   ```

2. **多轮工具调用循环**：
   ```kotlin
   var round = 0
   while (round < MAX_TOOL_ROUNDS && !isCancelled.get()) {
       // 1. 发送请求，接收流式响应
       // 2. 解析工具调用
       // 3. 执行工具
       // 4. 将工具结果加入消息历史
       // 5. 如果没有工具调用，退出循环
   }
   ```

3. **工具调用合并**：流式响应中工具调用分多个 delta 到达，需要合并
   ```kotlin
   private fun mergeToolCalls(toolCallBuffer: List<ToolCall>): List<ToolCall>
   ```

4. **DSL 工具调用解析**：支持不支持 Function Calling 的模型使用 DSL 格式
   ```kotlin
   private fun parseDslToolCalls(text: String): List<ToolCall>
   ```

#### 3.2.2 ChatStateService

**职责**：会话状态管理，持久化保存对话历史。

**数据模型**：
```
ChatStateService
    │
    └── State
            ├── sessions: MutableList<SessionState>
            └── currentSessionIndex: Int

SessionState
    ├── id: String
    ├── title: String
    ├── messages: MutableList<MessageState>
    └── timestamp: Long

MessageState
    ├── id: String
    ├── type: String (user/ai/tool)
    ├── content: String
    ├── timestamp: String
    ├── toolName: String (工具消息)
    ├── parameters: Map<String, String>
    ├── isExecuting: Boolean
    ├── result: String?
    └── ...
```

**持久化**：使用 IntelliJ Platform 的 `PersistentStateComponent`，保存到 `chat-sessions.xml`。

#### 3.2.3 TokenOptimizer

**职责**：优化 Token 使用，压缩历史消息。

**优化策略**：
1. **消息截断**：限制单条消息最大长度
2. **历史限制**：保留最近 N 条消息
3. **智能压缩**：对长消息进行摘要

---

### 3.3 工具层

#### 3.3.1 Tool 基类

**类设计**：
```kotlin
abstract class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
) {
    abstract suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): ToolResult

    protected fun normalizePath(path: String): String
    protected fun resolveFilePath(project: Project, rawPath: String): Path?
    protected fun findVirtualFile(absolutePath: Path): VirtualFile?
}
```

**ToolResult 类型**：
```kotlin
sealed class ToolResult {
    data class Success(val data: Any) : ToolResult()
    data class Error(val message: String) : ToolResult()
    data class Progress(val message: String) : ToolResult()
    data class OutputUpdate(val output: String) : ToolResult()
}
```

#### 3.3.2 ToolManager

**职责**：工具注册、查找、执行。

**设计模式**：注册表模式（Registry Pattern）

```kotlin
object ToolManager {
    private val tools = mutableMapOf<String, Tool>()

    fun registerTool(tool: Tool)
    fun getTool(name: String): Tool?
    fun getAllTools(): List<Tool>
    suspend fun executeTool(project: Project, toolName: String, params: Map<String, Any>): ToolResult
}
```

#### 3.3.3 工具列表

| 工具名 | 功能 | 关键参数 |
|--------|------|----------|
| `read_file` | 读取文件 | path, start_line, limit |
| `edit_file` | 编辑文件 | path, new_text, start_line, end_line |
| `delete_file` | 删除文件 | path |
| `create_directory` | 创建目录 | path |
| `list_files` | 列出目录 | path, max_depth |
| `search_files` | 搜索文件 | pattern, max_results |
| `build` | Gradle 构建 | task |
| `compile_project` | 编译项目 | - |
| `web_search` | 网络搜索 | query, max_results |
| `shell_command` | Shell 命令 | command, cwd |
| `get_current_time` | 获取时间 | - |
| `android_project_analysis` | 项目分析 | - |

#### 3.3.4 EditFileTool 详细设计

**设计要点**：

1. **行号精确替换**：使用 `start_line` 和 `end_line` 指定替换范围
2. **插入模式**：当 `end_line = start_line - 1` 时，在指定行后插入
3. **返回信息**：返回实际被替换的文本和文件状态

**执行流程**：
```
┌─────────────┐
│ 接收参数     │
│ path        │
│ new_text    │
│ start_line  │
│ end_line    │
└──────┬──────┘
       │
       ▼
┌─────────────┐     否    ┌─────────────┐
│ 文件存在？   │ ────────► │ 创建新文件   │
└──────┬──────┘           └─────────────┘
       │ 是
       ▼
┌─────────────┐     是    ┌─────────────┐
│ 插入模式？   │ ────────► │ 插入内容     │
│ (end < start)│           └─────────────┘
└──────┬──────┘
       │ 否
       ▼
┌─────────────┐
│ 替换指定行   │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 返回结果     │
│ - old_text  │
│ - total_lines│
│ - new_end_line│
└─────────────┘
```

---

### 3.4 UI 层

#### 3.4.1 ChatPanel

**职责**：聊天界面，消息展示与输入。

**组件结构**：
```
ChatPanel
├── TopBar
│   ├── ModelSelector (模型选择)
│   ├── SessionSelector (会话选择)
│   └── SettingsButton (设置按钮)
├── MessageList
│   ├── UserMessageItem
│   ├── AiMessageItem
│   │   ├── Content (Markdown 渲染)
│   │   └── TokenUsage
│   └── ToolCallMessageItem
│       ├── ToolHeader (工具名、状态)
│       ├── Parameters (参数展示)
│       └── DiffView (文件修改差异)
└── InputArea
    ├── TextInput (多行输入)
    └── SendButton
```

#### 3.4.2 Diff 显示设计

**算法**：LCS（最长公共子序列）算法

**数据结构**：
```kotlin
data class DiffLine(
    val oldLineNumber: Int?,    // 旧文件行号
    val newLineNumber: Int?,    // 新文件行号
    val content: String,        // 行内容
    val type: DiffType          // 差异类型
)

enum class DiffType {
    ADD,      // 新增
    DELETE,   // 删除
    KEEP,     // 保持
    CONTEXT   // 省略上下文
}
```

**显示逻辑**：
1. 计算新旧文本的 LCS
2. 生成差异行列表
3. 只显示变化行及其上下文（默认 3 行）
4. 省略部分用 `...` 表示

---

### 3.5 配置层

#### 3.5.1 AiAgentSettings

**职责**：管理插件配置，支持多 Provider。

**配置结构**：
```kotlin
data class State(
    var providers: MutableList<Provider>,      // API 服务商列表
    var currentProviderId: String,             // 当前服务商
    var currentModel: String,                  // 当前模型
    var enableLogging: Boolean,                // 启用日志
    var systemPrompts: MutableList<SystemPrompt>, // 系统提示词列表
    var currentSystemPromptId: String          // 当前系统提示词
)

data class Provider(
    var id: String,
    var name: String,
    var apiType: String,        // "openai" 或 "ollama"
    var apiUrl: String,
    var apiKey: String,
    var selectedModels: MutableList<String>,
    var timeoutSeconds: Int,
    var temperature: Double,
    var topP: Double,
    var contextLength: Int
)
```

**持久化**：保存到 `ai-agent-settings.xml`。

---

## 4. 数据流设计

### 4.1 消息发送流程

```
用户输入
    │
    ▼
┌─────────────┐
│ ChatPanel   │
│ onSend()    │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ LangChain   │
│ AgentService│
│ sendMessage │
└──────┬──────┘
       │
       ▼
┌─────────────┐     ┌─────────────┐
│ 构建消息列表 │────►│ Token优化   │
└──────┬──────┘     └─────────────┘
       │
       ▼
┌─────────────┐
│ OpenAiClient│
│ chatStream  │
└──────┬──────┘
       │
       ▼
┌─────────────┐     ┌─────────────┐
│ 流式接收响应 │────►│ 解析工具调用 │
└──────┬──────┘     └──────┬──────┘
       │                   │
       │                   ▼
       │            ┌─────────────┐
       │            │ ToolManager │
       │            │ executeTool │
       │            └──────┬──────┘
       │                   │
       ▼                   ▼
┌─────────────┐     ┌─────────────┐
│ onChunk     │     │ onToolCall  │
│ 回调UI更新   │     │ 回调UI更新   │
└─────────────┘     └─────────────┘
```

### 4.2 工具调用流程

```
大模型返回工具调用
    │
    ▼
┌─────────────────┐
│ mergeToolCalls  │ 合并流式 delta
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ notifyToolCallUI│ 通知 UI 开始执行
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ ToolManager     │
│ executeTool     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Tool.execute    │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌───────┐ ┌───────┐
│Success│ │ Error │
└───┬───┘ └───┬───┘
    │         │
    ▼         ▼
┌─────────────────┐
│ 构建Tool消息     │
│ 加入对话历史     │
└─────────────────┘
```

---

## 5. 关键技术实现

### 5.1 流式响应处理

使用 Kotlin Flow 和 callbackFlow：

```kotlin
fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>): Flow<StreamChunk> = callbackFlow {
    launch(Dispatchers.IO) {
        try {
            doStreamRequest(requestBody) { line ->
                when (line) {
                    is StreamLine.Data -> {
                        val chunk = parseStreamChunk(line.value)
                        if (chunk != null) trySend(chunk)
                    }
                    is StreamLine.Done -> trySend(StreamChunk.Done)
                }
            }
            close()
        } catch (e: Exception) {
            close(e)
        }
    }
    awaitClose()
}.flowOn(Dispatchers.IO)
```

### 5.2 工具调用合并

流式响应中，工具调用分多个 delta 到达：

```
Delta 1: { id: "call_123", function: { name: "read_file" } }
Delta 2: { function: { arguments: "{\"path\": \"" } }
Delta 3: { function: { arguments: "test.kt\"}" } }
```

合并逻辑：
```kotlin
private fun mergeToolCalls(toolCallBuffer: List<ToolCall>): List<ToolCall> {
    return toolCallBuffer
        .groupBy { it.id.ifEmpty { "index_${it.index}" } }
        .map { (_, calls) ->
            ToolCall(
                id = calls.first().id,
                function = FunctionCall(
                    name = calls.first().function.name,
                    arguments = calls.joinToString("") { it.function.arguments }
                )
            )
        }
}
```

### 5.3 Diff 算法实现

使用动态规划计算 LCS：

```kotlin
private fun computeLCS(oldLines: List<String>, newLines: List<String>): List<String> {
    val m = oldLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    
    for (i in 1..m) {
        for (j in 1..n) {
            if (oldLines[i - 1] == newLines[j - 1]) {
                dp[i][j] = dp[i - 1][j - 1] + 1
            } else {
                dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }
    
    // 回溯构建 LCS
    // ...
}
```

---

## 6. 扩展设计

### 6.1 添加新工具

1. 创建工具类，继承 `Tool`：
```kotlin
class MyTool : Tool(
    name = "my_tool",
    description = "工具描述",
    parameters = listOf(
        ToolParameter("param1", "string", "参数描述", required = true)
    )
) {
    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        // 实现逻辑
        return ToolResult.Success(mapOf("result" to "ok"))
    }
}
```

2. 在 `ToolManager` 中注册：
```kotlin
private fun registerTools() {
    // ...
    registerTool(MyTool())
}
```

### 6.2 添加新的 API Provider

在设置中添加新的 Provider 配置即可，无需修改代码。支持的 API 类型：
- `openai`：OpenAI 兼容 API
- `ollama`：Ollama 本地模型

---

## 7. 性能优化

### 7.1 Token 优化

- **历史消息限制**：保留最近 20 条消息
- **消息截断**：单条消息最大 10240 字符
- **工具结果截断**：最大 10240 字符

### 7.2 UI 优化

- **懒加载**：消息列表使用 LazyColumn
- **状态缓存**：使用 remember 缓存计算结果
- **异步更新**：使用 Coroutine 进行异步操作

---

## 8. 安全设计

### 8.1 API Key 保护

- API Key 存储在本地配置文件，不上传到版本控制
- 日志中不输出 API Key

### 8.2 文件操作安全

- 路径规范化，防止路径穿越攻击
- 只能操作项目目录内的文件

---

## 9. 错误处理

### 9.1 网络错误

- 连接超时：显示友好提示
- API 限流：自动重试（最多 2 次）
- 服务端错误：显示错误信息

### 9.2 工具执行错误

- 参数错误：返回错误信息，引导模型修正
- 执行异常：捕获异常，返回错误信息
- 取消操作：支持中断正在执行的工具

---

## 10. 测试策略

### 10.1 单元测试

- API 客户端测试：Mock HTTP 响应
- 工具执行测试：Mock Project 和 VirtualFile
- Diff 算法测试：验证各种边界情况

### 10.2 集成测试

- 完整对话流程测试
- 多轮工具调用测试
- 会话持久化测试

---

## 11. 部署说明

### 11.1 构建命令

```bash
./gradlew buildPlugin
```

### 11.2 输出位置

```
build/distributions/AiAgent-1.0-SNAPSHOT.zip
```

### 11.3 安装方式

1. 打开 IDE 设置 > Plugins
2. 点击齿轮图标 > Install Plugin from Disk
3. 选择 zip 文件
4. 重启 IDE

---

## 12. 版本规划

### v1.1（计划）
- [ ] 支持更多模型（Claude、Gemini）
- [ ] 代码补全功能
- [ ] 代码审查功能
- [ ] 多语言支持

### v1.2（计划）
- [ ] 自定义工具扩展
- [ ] 团队协作功能
- [ ] 知识库集成
