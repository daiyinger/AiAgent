package com.example.aiagent.tools

import com.example.aiagent.service.LogService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths

class CompileProjectTool : Tool(
    name = "compile_project",
    description = "Compile Android project",
    parameters = listOf(
        ToolParameter(
            name = "mode",
            type = "string",
            description = "Compile mode: 'build' for full build, 'assemble' for assemble only, 'clean' for clean build",
            required = false
        )
    )
) {
    private fun log(message: String) {
        LogService.log("CompileProjectTool - $message")
    }
    
    override suspend fun execute(project: Project, params: Map<String, Any>): ToolResult {
        val mode = params["mode"] as? String ?: "build"
        
        return try {
            val basePath = project.basePath
            if (basePath == null) {
                println("CompileProjectTool - Project base path not found")
                return ToolResult.Error("Project base path not found")
            }
            
            val projectBasePath: String = basePath.toString()
            
            log("Starting compilation in $mode mode")
            println("CompileProjectTool - Starting compilation in $mode mode")
            
            // 检查项目结构
            val projectDirPath = Paths.get(projectBasePath)
            if (!Files.exists(projectDirPath) || !Files.isDirectory(projectDirPath)) {
                log("Project directory not found or not a directory: $projectBasePath")
                return ToolResult.Error("Project directory not found or not a directory: $projectBasePath")
            }
            
            // 检查是否存在build.gradle或settings.gradle文件
            val hasGradleFiles = checkGradleFiles(projectDirPath)
            
            if (!hasGradleFiles) {
                log("No Gradle files found in project directory")
                return ToolResult.Error("No Gradle files found in project directory. This may not be a Gradle project.")
            }
            
            // 构建Gradle命令
            val (commandList, description) = buildGradleCommandList(mode, projectBasePath)
            
            log("Executing: $description in $projectBasePath")
            println("CompileProjectTool - Executing: $description in $projectBasePath")
            
            try {
                // 使用ProcessBuilder执行命令
                val processBuilder = ProcessBuilder(commandList)
                processBuilder.directory(File(projectBasePath))
                processBuilder.redirectErrorStream(false)
                
                val process = processBuilder.start()
                
                // 读取输出
                val output = StringBuilder()
                val errorOutput = StringBuilder()
                
                // 读取标准输出
                val outputThread = Thread {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                        println("CompileProjectTool - Output: $line")
                        log("Output: $line")
                    }
                    reader.close()
                }
                
                // 读取错误输出
                val errorThread = Thread {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        errorOutput.append(line).append("\n")
                        println("CompileProjectTool - Error: $line")
                        log("Error: $line")
                    }
                    reader.close()
                }
                
                outputThread.start()
                errorThread.start()
                
                // 等待命令完成
                val exitCode = process.waitFor()
                outputThread.join()
                errorThread.join()
                
                if (exitCode == 0) {
                    log("Compilation successful")
                    ToolResult.Success(
                        mapOf(
                            "message" to "Compilation successful",
                            "mode" to mode,
                            "output" to output.toString().take(1000)
                        )
                    )
                } else {
                    log("Compilation failed with exit code $exitCode")
                    log("Error output: ${errorOutput.toString().take(500)}")
                    ToolResult.Error(
                        "Compilation failed with exit code $exitCode\n" +
                        "Error output: ${errorOutput.toString().take(500)}"
                    )
                }
            } catch (e: Exception) {
                log("Error executing gradle: ${e.message}")
                log("Stack trace: ${e.stackTraceToString()}")
                ToolResult.Error("Error compiling project: ${e.message}")
            }
        } catch (e: Exception) {
            log("Unexpected error: ${e.message}")
            log("Stack trace: ${e.stackTraceToString()}")
            ToolResult.Error("Error compiling project: ${e.message}")
        }
    }
    
    private fun checkGradleFiles(projectDirPath: java.nio.file.Path): Boolean {
        val files = Files.list(projectDirPath).toList()
        return files.any { path ->
            val fileName = path.fileName?.toString() ?: ""
            fileName == "build.gradle" || fileName == "build.gradle.kts" || fileName == "settings.gradle" || fileName == "settings.gradle.kts"
        }
    }
    
    /**
     * 构建Gradle命令列表，根据操作系统和项目结构选择正确的命令格式
     * 返回Pair<命令列表, 命令描述>
     */
    private fun buildGradleCommandList(mode: String, projectBasePath: String): Pair<List<String>, String> {
        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("windows")
        
        // 检查项目中是否存在gradlew文件
        val gradlewFileName = if (isWindows) "gradlew.bat" else "gradlew"
        val gradlewPath = Paths.get(projectBasePath, gradlewFileName)
        val gradlewExists = Files.exists(gradlewPath)
        
        val task = when (mode) {
            "clean" -> "clean"
            "assemble" -> "assembleDebug"
            else -> "build"
        }
        
        val commandList: List<String> = if (gradlewExists) {
            // 使用项目中的gradlew
            if (isWindows) {
                listOf("cmd", "/c", gradlewFileName, task)
            } else {
                listOf("./gradlew", task)
            }
        } else {
            // 使用系统环境中的gradle
            if (isWindows) {
                listOf("cmd", "/c", "gradle", task)
            } else {
                listOf("gradle", task)
            }
        }
        
        val description = if (gradlewExists) {
            "$gradlewFileName $task"
        } else {
            "gradle $task"
        }
        
        return Pair(commandList, description)
    }
}
