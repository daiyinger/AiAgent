# IntelliJ IDEA AI Agent 插件

一个为 IntelliJ IDEA / Android Studio 开发的 AI 辅助编程插件，支持多种 AI 模型，提供智能对话、代码分析、文件操作等功能。

## 功能特性

### 🤖 多模型支持
- **OpenAI 兼容 API**：支持 OpenAI、DeepSeek、通义千问等兼容 OpenAI API 的模型服务
- **Ollama 本地模型**：支持本地运行的 Llama、Mistral、Qwen 等模型
- **自定义 Provider**：可配置多个 API 服务商，灵活切换

### 🛠️ 丰富的工具集
| 工具 | 功能描述 |
|------|----------|
| `read_file` | 读取文件内容，支持指定行范围 |
| `edit_file` | 编辑文件，支持行号精确替换和插入 |
| `delete_file` | 删除文件或目录 |
| `create_directory` | 创建目录 |
| `list_files` | 列出目录内容 |
| `search_files` | 按文件名搜索文件 |
| `build` | 执行 Gradle 构建 |
| `compile_project` | 编译项目并分析错误 |
| `web_search` | 联网搜索（支持百度、Google、Bing 等） |
| `shell_command` | 执行 Shell 命令 |
| `get_current_time` | 获取当前时间 |
| `android_project_analysis` | 分析 Android 项目结构 |

### 💬 智能对话
- **流式响应**：实时显示 AI 回复，提供流畅的交互体验
- **多轮对话**：支持上下文连贯的连续对话
- **工具调用**：AI 可自动调用工具执行任务
- **会话管理**：支持多会话，可切换和管理历史对话
- **Token 优化**：智能压缩历史消息，优化 Token 使用

### 🎨 现代化 UI
- **Compose UI**：使用 JetBrains Compose 构建现代化界面
- **深色主题**：完美适配 IDE 深色主题
- **Diff 显示**：文件修改时显示差异对比，清晰展示变更
- **Markdown 渲染**：支持代码高亮和格式化显示
- **实时反馈**：工具执行过程可视化展示

## 环境要求

- **IntelliJ IDEA** 2025.2.4 或更高版本
- **JDK** 21 或更高版本
- **Gradle** 8.x

## 安装方法

### 方法一：从源码构建
```bash
# 克隆项目
git clone https://github.com/yourusername/AiAgent.git
cd AiAgent

# 构建插件
./gradlew buildPlugin

# 插件位于 build/distributions/ 目录
```

### 方法二：安装到 IDE
1. 打开 `File` > `Settings` > `Plugins`
2. 点击齿轮图标 > `Install Plugin from Disk...`
3. 选择构建好的 `.zip` 文件
4. 重启 IDE

## 配置指南

### 添加 AI 模型 Provider

1. 点击插件面板右上角的设置图标
2. 添加新的 Provider 配置：

**OpenAI 配置示例：**
```
名称: OpenAI
API 类型: openai
API 地址: https://api.openai.com
API Key: sk-xxx
模型: gpt-4o, gpt-4o-mini
```

**Ollama 配置示例：**
```
名称: Ollama Local
API 类型: ollama
API 地址: http://localhost:11434
模型: llama3.1:latest, qwen2.5:latest
```

**DeepSeek 配置示例：**
```
名称: DeepSeek
API 类型: openai
API 地址: https://api.deepseek.com
API Key: sk-xxx
模型: deepseek-chat, deepseek-coder
```

### 系统提示词

内置多种系统提示词模板：
- **默认助手**：通用对话助手
- **Android 开发者**：专为 Android 开发优化

也可自定义系统提示词以适应不同场景。

## 使用示例

### 代码分析
```
用户: 分析当前项目的结构
AI: [调用 list_files 工具分析目录结构，给出详细报告]
```

### 文件操作
```
用户: 读取 MainActivity.kt 文件
AI: [调用 read_file 工具，显示文件内容]

用户: 把第 50-60 行的代码优化一下
AI: [调用 edit_file 工具，修改代码并显示 diff]
```

### 项目构建
```
用户: 编译项目并告诉我有什么错误
AI: [调用 compile_project 工具，分析编译结果]
```

### 联网搜索
```
用户: 搜索 Kotlin 协程的最佳实践
AI: [调用 web_search 工具，汇总搜索结果]
```

## 项目结构

```
AiAgent/
├── src/main/kotlin/com/example/aiagent/
│   ├── api/                    # API 客户端
│   │   ├── OpenAiClient.kt     # OpenAI 兼容 API 客户端
│   │   └── ToolDefinitions.kt  # 工具定义
│   ├── service/                # 服务层
│   │   ├── AiAgentService.kt   # AI 服务
│   │   ├── LangChainAgentService.kt  # LangChain 集成
│   │   ├── ChatStateService.kt # 会话状态管理
│   │   ├── TokenOptimizer.kt   # Token 优化
│   │   └── LogService.kt       # 日志服务
│   ├── settings/               # 设置
│   │   └── AiAgentSettings.kt  # 配置管理
│   ├── tools/                  # 工具实现
│   │   ├── Tool.kt             # 工具基类
│   │   ├── ToolManager.kt      # 工具管理器
│   │   ├── ReadFileTool.kt     # 文件读取
│   │   ├── EditFileTool.kt     # 文件编辑
│   │   ├── WebSearchTool.kt    # 网络搜索
│   │   └── ...
│   └── ui/                     # 用户界面
│       ├── ChatPanel.kt        # 聊天面板
│       └── SettingsPanel.kt    # 设置面板
└── build.gradle.kts            # 构建配置
```

## 技术栈

- **Kotlin** 2.1.20
- **JetBrains Compose** - UI 框架
- **IntelliJ Platform Plugin SDK** 2.10.2
- **Jackson** - JSON 处理
- **Jsoup** - HTML 解析
- **JTokkit** - Token 计算

## 常见问题

### Q: 连接模型服务失败？
A: 
1. 检查 API 地址是否正确
2. 确认 API Key 有效
3. 对于 Ollama，确保服务已启动：`ollama serve`

### Q: 工具执行失败？
A: 
1. 检查文件路径是否正确
2. 确认有足够的权限
3. 查看日志获取详细错误信息

### Q: 响应速度慢？
A: 
1. 本地模型取决于硬件配置
2. 云端模型取决于网络状况
3. 可调整 `timeoutSeconds` 参数

### Q: 连续编辑时行号错误？
A: 
每次编辑后，工具会返回 `total_lines` 和 `new_end_line`，请根据这些信息计算后续编辑的正确行号。

## 开发指南

### 构建项目
```bash
./gradlew buildPlugin
```

### 运行测试 IDE
```bash
./gradlew runIde
```

### 添加新工具
1. 继承 `Tool` 类
2. 实现必要的属性和方法
3. 在 `ToolManager` 中注册

```kotlin
class MyTool : Tool(
    name = "my_tool",
    description = "工具描述",
    parameters = listOf(...)
) {
    override suspend fun execute(...): ToolResult {
        // 实现逻辑
    }
}
```

## 更新日志

### v1.0.0
- 初始版本发布
- 支持多模型 Provider
- 实现 12 个常用工具
- 流式对话支持
- 文件编辑 Diff 显示

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

MIT License

## 联系方式

- GitHub Issues: [提交问题](https://github.com/yourusername/AiAgent/issues)

---

**⚠️ 安全提示**：请勿在对话中包含密码、API Key 等敏感信息。
