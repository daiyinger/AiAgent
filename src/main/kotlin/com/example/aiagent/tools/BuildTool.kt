package com.example.aiagent.tools

import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File

class BuildTool : Tool(
    name = "build",
    description = "Build the project using the appropriate build system (Gradle, Maven, etc.)",
    parameters = listOf(
        ToolParameter(
            name = "task",
            type = "string",
            description = "The build task to run (e.g., 'build', 'compile', 'test'). Default is 'build'",
            required = false
        )
    )
) {
    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val task = params["task"] as? String ?: "build"
        
        return try {
            val projectBasePath = project.basePath ?: return ToolResult.Error("Project base path not found")
            val projectDir = File(projectBasePath)
            
            val buildSystem = detectBuildSystem(projectDir)
            
            val command = when (buildSystem) {
                "gradle" -> {
                    if (File(projectDir, "gradlew.bat").exists()) {
                        listOf("cmd", "/c", "gradlew.bat", task)
                    } else if (File(projectDir, "gradlew").exists()) {
                        listOf("./gradlew", task)
                    } else {
                        listOf("gradle", task)
                    }
                }
                "maven" -> {
                    if (File(projectDir, "mvnw.cmd").exists()) {
                        listOf("cmd", "/c", "mvnw.cmd", task)
                    } else if (File(projectDir, "mvnw").exists()) {
                        listOf("./mvnw", task)
                    } else {
                        listOf("mvn", task)
                    }
                }
                else -> return ToolResult.Error("No supported build system found in project")
            }
            
            val commandLine = GeneralCommandLine(command)
                .withWorkDirectory(projectDir)
                .withCharset(Charsets.UTF_8)
            
            val output = StringBuilder()
            val errorOutput = StringBuilder()
            
            val processHandler = OSProcessHandler(commandLine)
            
            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text
                    output.append(text)
                    if (outputType == ProcessOutputTypes.STDERR) {
                        errorOutput.append(text)
                    }
                    onOutput?.invoke(text)
                }
            })
            
            processHandler.startNotify()
            
            while (!processHandler.isProcessTerminated) {
                if (isCancelled?.invoke() == true) {
                    processHandler.destroyProcess()
                    return ToolResult.Error("Build cancelled by user")
                }
                kotlinx.coroutines.delay(100)
            }
            
            val exitCode = processHandler.exitCode ?: -1
            
            if (exitCode == 0) {
                ToolResult.Success(
                    mapOf(
                        "build_system" to buildSystem,
                        "task" to task,
                        "exit_code" to exitCode,
                        "output" to output.toString()
                    )
                )
            } else {
                ToolResult.Error(
                    "Build failed with exit code $exitCode\n\nError output:\n${errorOutput.toString()}"
                )
            }
        } catch (e: Exception) {
            ToolResult.Error("Error building project: ${e.message}")
        }
    }
    
    private fun detectBuildSystem(projectDir: File): String {
        return when {
            File(projectDir, "build.gradle").exists() || 
            File(projectDir, "build.gradle.kts").exists() ||
            File(projectDir, "settings.gradle").exists() ||
            File(projectDir, "gradlew").exists() ||
            File(projectDir, "gradlew.bat").exists() -> "gradle"
            File(projectDir, "pom.xml").exists() ||
            File(projectDir, "mvnw").exists() ||
            File(projectDir, "mvnw.cmd").exists() -> "maven"
            else -> "unknown"
        }
    }
}

object ProcessOutputTypes {
    val STDOUT = Key.create<Any>("STDOUT")
    val STDERR = Key.create<Any>("STDERR")
}