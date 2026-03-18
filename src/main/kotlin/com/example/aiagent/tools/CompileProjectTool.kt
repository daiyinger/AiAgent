package com.example.aiagent.tools

import com.example.aiagent.service.LogService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

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
            description = "Skip tests during build (default: true)",
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
        // 默认改为 true，Android 编译时跑 Test 极易失败报错
        val skipTests = params["skip_tests"] as? Boolean ?: true 
        val timeoutMinutes = (params["timeout_minutes"] as? Number)?.toInt() ?: 30

        return try {
            val basePath = project.basePath ?: return ToolResult.Error("Project base path not found")
            val projectBasePath = basePath.toString()

            log("Starting compilation in $mode mode (buildType=$buildType, skipTests=$skipTests)")

            val projectDirPath = Paths.get(projectBasePath)
            if (!Files.exists(projectDirPath) || !Files.isDirectory(projectDirPath)) {
                return ToolResult.Error("Project directory not found: $projectBasePath")
            }

            if (!checkGradleFiles(projectDirPath)) {
                return ToolResult.Error("No Gradle files found in project directory. This may not be a Gradle project.")
            }

            val (commandList, description) = buildGradleCommandList(mode, buildType, skipTests, projectBasePath)
            log("Executing: $description in $projectBasePath")

            withContext(Dispatchers.IO) {
                withTimeout(timeoutMinutes * 60L * 1000) {
                    val processBuilder = ProcessBuilder(commandList)
                    processBuilder.directory(File(projectBasePath))
                    // 【重要修复】合并输出流，防止死锁
                    processBuilder.redirectErrorStream(true) 
                    
                    // 【重要修复】尝试继承环境变量，尤其是 ANDROID_HOME 和 JAVA_HOME
                    val env = processBuilder.environment()
                    
                    // 直接从系统环境变量获取 GRADLE_USER_HOME（不区分大小写）
                    val gradleUserHome = System.getenv().entries.find { it.key.equals("GRADLE_USER_HOME", ignoreCase = true) }?.value
                    if (gradleUserHome != null) {
                        env.put("GRADLE_USER_HOME", gradleUserHome)
                        log("从系统环境变量获取到 GRADLE_USER_HOME: $gradleUserHome")
                    } else {
                        log("系统环境变量中未找到 GRADLE_USER_HOME")
                    }
                    
                    // 然后复制系统环境变量
                    System.getenv().forEach { (k, v) -> env.putIfAbsent(k, v) }

                    val process = processBuilder.start()
                    val output = StringBuilder()

                    // 只需一个 Job 即可读取所有输出
                    val readJob = launch {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String? = null
                            // 加上 isActive 判断，以便在超时或外部取消时能及时退出循环
                            while (isActive && reader.readLine().also { line = it } != null) {
                                if (isCancelled?.invoke() == true) {
                                    process.destroy()
                                    break
                                }
                                line?.let {
                                    output.append(it).append("\n")
                                    onOutput?.invoke(it)
                                }
                            }
                        }
                    }

                    // 轮询检查取消状态，并等待进程结束
                    while (process.isAlive) {
                        if (isCancelled?.invoke() == true) {
                            process.destroy()
                            readJob.cancel()
                            throw CancellationException("Compilation cancelled by user")
                        }
                        delay(500) // 降低轮询频率，节省 CPU
                    }

                    val exitCode = process.exitValue()
                    readJob.cancelAndJoin() // 确保读取协程结束

                    if (exitCode == 0) {
                        log("Compilation successful")
                        ToolResult.Success(
                            mapOf(
                                "message" to "Compilation successful",
                                "mode" to mode,
                                "buildType" to buildType,
                                "output" to "Build Successful. (Details omitted to save space)" // 避免把巨量Log丢给Agent导致Token爆掉
                            )
                        )
                    } else {
                        log("Compilation failed with exit code $exitCode")
                        // 获取最后一部分日志（通常错误在最后）
                        val lastOutput = output.lines().takeLast(100).joinToString("\n")
                        val errorMessage = """
                            Compilation failed with exit code $exitCode
                            Last 100 lines of output:
                            $lastOutput
                            
                            Suggested solutions:
                            1. Check local.properties for correct sdk.dir (ANDROID_HOME).
                            2. Ensure correct JAVA_HOME is set for this project.
                            3. Check for dependency conflicts or syntax errors.
                        """.trimIndent()
                        ToolResult.Error(errorMessage)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            log("Compilation timed out after $timeoutMinutes minutes")
            ToolResult.Error("Compilation timed out after $timeoutMinutes minutes. Network issue or stuck process.")
        } catch (e: CancellationException) {
            throw e // 协程正常的取消抛出
        } catch (e: Exception) {
            log("Error executing gradle: ${e.message}")
            ToolResult.Error("Error compiling project: ${e.message}")
        }
    }

    private fun checkGradleFiles(projectDirPath: java.nio.file.Path): Boolean {
        val files = Files.list(projectDirPath).toList()
        return files.any { path ->
            val fileName = path.fileName?.toString() ?: ""
            fileName in listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        }
    }

    private fun buildGradleCommandList(
        mode: String, 
        buildType: String, 
        skipTests: Boolean, 
        projectBasePath: String
    ): Pair<List<String>, String> {
        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("windows")

        val gradlewFileName = if (isWindows) "gradlew.bat" else "gradlew"
        val gradlewFile = File(projectBasePath, gradlewFileName)
        val gradlewExists = gradlewFile.exists()

        val commands = mutableListOf<String>()
        if (gradlewExists) {
            if (!isWindows) {
                // 【重要修复】确保 Mac/Linux 下拥有执行权限，否则会报 Permission denied
                gradlewFile.setExecutable(true)
            }
            if (isWindows) {
                commands.addAll(listOf("cmd", "/c", gradlewFileName))
            } else {
                commands.add("./$gradlewFileName")
            }
        } else {
            // 如果没有 gradlew，尝试使用全局 gradle（前提是配了环境变量）
            if (isWindows) {
                commands.addAll(listOf("cmd", "/c", "gradle"))
            } else {
                commands.add("gradle")
            }
        }

        val mainTask = when (mode) {
            "clean" -> "clean"
            "assemble" -> "assemble${buildType.replaceFirstChar { it.uppercase() }}"
            else -> "build"
        }
        commands.add(mainTask)

        // 【重要修复】正确处理跳过测试逻辑
        if (skipTests) {
            commands.add("-x")
            commands.add("test")
            commands.add("-x")
            commands.add("connectedAndroidTest")
            commands.add("-x")
            commands.add("lint") // 建议把 lint 也跳过，否则非常耗时且容易因为警告导致编译失败
        }

        // 移除 --refresh-dependencies，这个参数会拉跨整个编译速度，引发超时
        // commands.add("--refresh-dependencies")

        val description = buildString {
            append(if (gradlewExists) gradlewFileName else "gradle")
            append(" ")
            append(commands.drop(if (isWindows) 3 else 1).joinToString(" "))
        }

        return Pair(commands, description)
    }
}