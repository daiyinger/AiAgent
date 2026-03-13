# Android Studio AI Agent 插件

一个专为Android Studio开发的AI辅助工具插件，帮助开发者分析、理解和优化Android项目。

## 功能特性

### 🔍 智能项目分析
- **项目结构分析**：自动分析Android项目的目录结构和文件组织
- **依赖分析**：分析项目的build.gradle文件，识别依赖关系
- **源代码分析**：分析关键Kotlin/Java源代码文件
- **Manifest分析**：分析AndroidManifest.xml文件，识别组件和权限

### 🛠️ 强大的工具集
- **文件操作**：读取、编辑项目文件
- **项目构建**：执行Gradle构建命令
- **文件搜索**：在项目中搜索文件
- **目录浏览**：列出目录中的文件，支持文件类型过滤

### 💬 智能对话
- **流式响应**：支持大模型的流式输出，实时显示回复
- **工具调用**：AI可以自动调用工具执行任务
- **多轮对话**：支持连续的对话和分析
- **消息历史**：保存对话历史，便于参考

### 🎨 现代化UI
- **深色主题**：支持Android Studio的深色主题
- **响应式布局**：适配不同屏幕尺寸
- **文本选择**：支持对话文本的选择和复制
- **模型选择**：支持多种AI模型

## 安装方法

### 方法一：从插件市场安装
1. 打开Android Studio
2. 点击 `File` > `Settings` > `Plugins`
3. 在搜索框中输入 "AI Agent"
4. 点击 "Install" 按钮安装
5. 重启Android Studio

### 方法二：从本地安装
1. 克隆或下载本项目
2. 执行 `./gradlew buildPlugin` 构建插件
3. 打开Android Studio
4. 点击 `File` > `Settings` > `Plugins` > `Install Plugin from Disk...`
5. 选择构建生成的插件文件（位于 `build/distributions/` 目录）
6. 重启Android Studio

## 使用指南

### 基本使用
1. 打开Android Studio
2. 在右侧边栏点击 "AI Agent" 标签
3. 在输入框中输入您的问题，例如：
   - "分析当前工程"
   - "查看MainActivity.kt文件"
   - "分析项目依赖"
4. 点击发送按钮或按Enter键
5. AI会分析您的请求并提供响应

### 高级功能

#### 模型设置
1. 点击插件面板顶部的设置图标
2. 在设置页面中：
   - 添加或删除模型Provider
   - 配置API参数（如API Key、温度等）
   - 选择默认模型

#### 工具调用示例
AI可以自动调用工具执行任务，例如：
- 分析项目结构：`list_files`
- 查看文件内容：`read_file`
- 编辑文件：`edit_file`
- 构建项目：`build`
- 搜索文件：`search_files`

## 支持的模型

- **OpenAI**：GPT-3.5, GPT-4
- **Ollama**：本地运行的模型，如Llama 3, Mistral等
- **其他**：支持兼容OpenAI API的模型服务

## 配置示例

### OpenAI配置
- **API Host**：`https://api.openai.com`
- **API Key**：您的OpenAI API密钥
- **模型**：`gpt-3.5-turbo` 或 `gpt-4`

### Ollama配置
- **API Host**：`http://localhost`
- **API Port**：`11434`
- **模型**：`llama3.1:latest` 或其他本地模型

## 最佳实践

1. **明确指令**：给AI提供清晰、具体的指令
2. **逐步分析**：对于复杂项目，建议逐步分析不同部分
3. **使用工具**：利用AI的工具调用能力执行具体任务
4. **参考历史**：查看之前的对话历史，避免重复问题
5. **提供反馈**：如果AI的回答不符合预期，提供具体的反馈

## 常见问题

### Q: 插件无法连接到模型服务？
A: 检查网络连接和API配置，确保API密钥正确且服务可访问。

### Q: 工具调用失败？
A: 检查文件路径是否正确，确保文件存在且有权限访问。

### Q: 响应速度慢？
A: 对于本地模型，响应速度取决于您的硬件配置；对于在线模型，取决于网络速度。

### Q: 工具调用被重复执行？
A: 这是因为大模型在生成回复时可能会分多个chunk发送，系统会在每个chunk中检查工具调用。如果出现此问题，请尝试使用更明确的指令。

## 技术架构

- **开发语言**：Kotlin
- **UI框架**：JetBrains Compose
- **构建工具**：Gradle
- **API客户端**：Java HTTP Client
- **状态管理**：JetBrains Compose State
- **配置持久化**：IntelliJ Platform PersistentStateComponent

## 贡献

欢迎贡献代码、报告问题或提出建议！请在GitHub上提交Issue或Pull Request。

## 许可证

本项目采用MIT许可证。详见LICENSE文件。

## 联系我们

- **GitHub**：[https://github.com/yourusername/ai-agent-plugin](https://github.com/yourusername/ai-agent-plugin)
- **Email**：contact@example.com

---

**注意**：本插件使用AI模型，请注意保护敏感信息，不要在对话中包含密码、API密钥等敏感数据。