package com.example.aiagent.tools

import com.example.aiagent.service.LogService
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ShellCommandTool : Tool(
    name = "run_shell_command",
    description = "Execute shell commands in the project directory. Use this for general terminal commands like git status, adb devices, grep, etc.",
    parameters = listOf(
        ToolParameter(
            name = "command",
            type = "string",
            description = "The shell command to execute (e.g., 'git status', 'adb devices', 'grep pattern file.txt')",
            required = true
        ),
        ToolParameter(
            name = "timeout_seconds",
            type = "number",
            description = "Command timeout in seconds (default: 30)",
            required = false
        )
    )
) {
    private fun log(message: String) = LogService.log("[ShellCommandTool] $message")

    override suspend fun execute(project: Project, params: Map<String, Any>, onOutput: ((String) -> Unit)?, isCancelled: (() -> Boolean)?): ToolResult {
        log("开始执行终端命令")
        
        val command = params["command"] as? String ?: return ToolResult.Error("Missing required parameter: command")
        val timeoutSeconds = (params["timeout_seconds"] as? Number)?.toLong() ?: 30
        
        log("执行命令: $command")
        log("超时设置: $timeoutSeconds 秒")
        
        try {
            val processBuilder = ProcessBuilder()
            
            // 根据操作系统设置不同的shell
            if (System.getProperty("os.name").lowercase().startsWith("windows")) {
                processBuilder.command("cmd", "/c", command)
            } else {
                processBuilder.command("sh", "-c", command)
            }
            
            // 设置工作目录为项目根目录
            processBuilder.directory(project.basePath?.let { java.io.File(it) })
            
            // 重定向错误流到标准输出
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            
            // 读取输出
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            
            // 启动线程读取输出
            val outputThread = Thread {
                try {
                    while (reader.readLine().also { line = it } != null) {
                        val lineContent = line ?: ""
                        output.append(lineContent).append("\n")
                        // 实时输出
                        onOutput?.invoke(lineContent + "\n")
                        
                        // 检查是否被取消
                        if (isCancelled?.invoke() == true) {
                            process.destroy()
                            break
                        }
                    }
                } catch (e: Exception) {
                    log("读取输出时发生异常: ${e.message}")
                }
            }
            outputThread.start()
            
            // 等待进程完成或超时
            var completed = false
            for (i in 1..timeoutSeconds) {
                if (process.waitFor(1, TimeUnit.SECONDS)) {
                    completed = true
                    break
                }
                
                // 检查是否被取消
                if (isCancelled?.invoke() == true) {
                    process.destroy()
                    return ToolResult.Error("Command execution cancelled")
                }
            }
            
            if (!completed) {
                process.destroy()
                return ToolResult.Error("Command timed out after $timeoutSeconds seconds")
            }
            
            // 等待输出线程完成
            outputThread.join(1000) // 最多等待1秒
            
            val exitCode = process.exitValue()
            val result = output.toString()
            
            log("命令执行完成，退出码: $exitCode")
            log("输出长度: ${result.length} 字符")
            
            if (exitCode == 0) {
                return ToolResult.Success("Command executed successfully:\n\n" + result.take(2000))
            } else {
                return ToolResult.Error("Command failed with exit code $exitCode:\n\n" + result.take(2000))
            }
            
        } catch (e: Exception) {
            log("执行命令时发生异常: ${e.message}")
            return ToolResult.Error("Error executing command: ${e.message}")
        }
    }
}
