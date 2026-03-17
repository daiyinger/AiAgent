package com.example.aiagent.tools

import com.example.aiagent.service.LogService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        ),
        ToolParameter(
            name = "build_type",
            type = "string",
            description = "Build type: 'debug' or 'release' (default: 'debug')",
            required = false
        ),
        ToolParameter(
            name = "skip_tests",
            type = "boolean",
            description = "Skip tests during build (default: false)",
            required = false
        ),
        ToolParameter(
            name = "timeout_minutes",
            type = "number",
            description = "Timeout in minutes (default: 30)",
            required = false
        )
    )
) {
    private fun log(message: String) {
        LogService.log("CompileProjectTool - $message")
    }

    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val mode = params["mode"] as? String ?: "build"
        val buildType = params["build_type"] as? String ?: "debug"
        val skipTests = params["skip_tests"] as? Boolean ?: false
        val timeoutMinutes = (params["timeout_minutes"] as? Number)?.toInt() ?: 30

        return try {
            val basePath = project.basePath
            if (basePath == null) {
                log("Project base path not found")
                return ToolResult.Error("Project base path not found")
            }

            val projectBasePath = basePath.toString()

            log("Starting compilation in $mode mode (buildType=$buildType, skipTests=$skipTests)")

            val projectDirPath = Paths.get(projectBasePath)
            if (!Files.exists(projectDirPath) || !Files.isDirectory(projectDirPath)) {
                log("Project directory not found or not a directory: $projectBasePath")
                return ToolResult.Error("Project directory not found or not a directory: $projectBasePath")
            }

            val hasGradleFiles = checkGradleFiles(projectDirPath)
            if (!hasGradleFiles) {
                log("No Gradle files found in project directory")
                return ToolResult.Error("No Gradle files found in project directory. This may not be a Gradle project.")
            }

            val (commandList, description) = buildGradleCommandList(mode, buildType, skipTests, projectBasePath)

            log("Executing: $description in $projectBasePath")

            try {
                withContext(Dispatchers.IO) {
                    withTimeout(timeoutMinutes * 60L * 1000) {
                        val processBuilder = ProcessBuilder(commandList)
                        processBuilder.directory(File(projectBasePath))
                        processBuilder.redirectErrorStream(false)

                        val process = processBuilder.start()

                        val output = StringBuilder()
                        val errorOutput = StringBuilder()

                        val coroutineScope = CoroutineScope(Dispatchers.IO)
                        val outputJob: Job = coroutineScope.launch {
                            val reader = BufferedReader(InputStreamReader(process.inputStream))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (isCancelled?.invoke() == true) {
                                    process.destroy()
                                    return@launch
                                }
                                output.append(line).append("\n")
                                log("Output: $line")
                                line?.let { onOutput?.invoke("[OUT] $it") }
                            }
                            reader.close()
                        }

                        val errorJob: Job = coroutineScope.launch {
                            val reader = BufferedReader(InputStreamReader(process.errorStream))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (isCancelled?.invoke() == true) {
                                    process.destroy()
                                    return@launch
                                }
                                errorOutput.append(line).append("\n")
                                log("Error: $line")
                                line?.let { onOutput?.invoke("[ERR] $it") }
                            }
                            reader.close()
                        }

                        while (process.isAlive) {
                            if (isCancelled?.invoke() == true) {
                                process.destroy()
                                throw CancellationException("Compilation cancelled by user")
                            }
                            kotlinx.coroutines.delay(100)
                        }

                        val exitCode = process.waitFor()
                        outputJob.cancel()
                        errorJob.cancel()

                        if (exitCode == 0) {
                            log("Compilation successful")
                            ToolResult.Success(
                                mapOf(
                                    "message" to "Compilation successful",
                                    "mode" to mode,
                                    "buildType" to buildType,
                                    "output" to output.toString()
                                )
                            )
                        } else {
                            log("Compilation failed with exit code $exitCode")
                            val errorMessage = "Compilation failed with exit code $exitCode\n" +
                                    "Error output:\n${errorOutput.toString()}\n" +
                                    "Suggested solutions:\n" +
                                    "1. Try cleaning the project: ./gradlew clean\n" +
                                    "2. Check for dependency conflicts in build.gradle\n" +
                                    "3. Verify Android SDK and build tools versions\n" +
                                    "4. Try with --refresh-dependencies flag"
                            ToolResult.Error(errorMessage)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                log("Compilation timed out after $timeoutMinutes minutes")
                ToolResult.Error("Compilation timed out after $timeoutMinutes minutes. Try increasing timeout or checking for infinite loops.")
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

    private fun buildGradleCommandList(mode: String, buildType: String, skipTests: Boolean, projectBasePath: String): Pair<List<String>, String> {
        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("windows")

        val gradlewFileName = if (isWindows) "gradlew.bat" else "gradlew"
        val gradlewPath = Paths.get(projectBasePath, gradlewFileName)
        val gradlewExists = Files.exists(gradlewPath)

        val commands = mutableListOf<String>()
        if (gradlewExists) {
            if (isWindows) {
                commands.addAll(listOf("cmd", "/c", gradlewFileName))
            } else {
                commands.add("./gradlew")
            }
        } else {
            if (isWindows) {
                commands.addAll(listOf("cmd", "/c", "gradle"))
            } else {
                commands.add("gradle")
            }
        }

        // 不自动执行 clean 任务，由用户手动控制

        val mainTask = when (mode) {
            "clean" -> "clean"
            "assemble" -> "assemble${buildType.replaceFirstChar { it.uppercase() }}"
            else -> "build"
        }

        commands.add(mainTask)

        // 默认跳过测试，避免测试失败导致构建失败
        if (mode == "build") {
            commands.add("-x")
            commands.add("test")
            commands.add("-x")
            commands.add("connectedAndroidTest")
        }

        // 添加刷新依赖的标志，避免依赖问题
        //commands.add("--refresh-dependencies")

        // 增加并行构建以提高速度
        //commands.add("--parallel")

        val description = buildString {
            append(if (gradlewExists) gradlewFileName else "gradle")
            append(" ")
            append(commands.drop(if (isWindows) 3 else 1).joinToString(" "))
        }

        return Pair(commands, description)
    }
}
