package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

class ListFilesTool : Tool(
    name = "list_files",
    description = "List files and directories in a given path. Supports filtering by file extension.",
    parameters = listOf(
        ToolParameter(
            name = "path",
            type = "string",
            description = "The relative path to list from project root. Default is root directory",
            required = false
        ),
        ToolParameter(
            name = "recursive",
            type = "boolean",
            description = "Whether to list files recursively. Default is false",
            required = false
        ),
        ToolParameter(
            name = "extension",
            type = "string",
            description = "File extension to filter (e.g., 'kt', 'java', 'gradle'). If not specified, lists all files",
            required = false
        )
    )
) {
    override suspend fun execute(project: Project, params: Map<String, Any>, onOutput: ((String) -> Unit)?): ToolResult {
        val path = params["path"] as? String ?: ""
        val recursive = params["recursive"] as? Boolean ?: false
        val extension = params["extension"] as? String

        return try {
            val projectBasePath = project.basePath ?: return ToolResult.Error("Project base path not found")
            val targetPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Project base path not found")

            if (!Files.exists(targetPath)) {
                return ToolResult.Error("Path does not exist: $path")
            }

            val files = mutableListOf<Map<String, Any>>()
            val ignoreDirs = listOf("build", ".gradle", ".git", ".idea", "captures", "app/build")

            val collectFile = { file: Path ->
                // 过滤无用目录
                var current = file
                var shouldIgnore = false
                while (current != targetPath.parent) {
                    if (ignoreDirs.contains(current.fileName?.toString())) {
                        shouldIgnore = true
                        break
                    }
                    current = current.parent ?: break
                }
                if (!shouldIgnore) {
                    val fileName = file.fileName?.toString() ?: ""
                    val skipByExtension = extension != null && (
                        Files.isDirectory(file) || !fileName.endsWith(".$extension", ignoreCase = true)
                    )
                    if (!skipByExtension) {
                        // 统一用 / 作为分隔符输出相对路径
                        val relativePath = targetPath.parent?.let { file.toString().removePrefix(projectBasePath) }
                            ?: file.toString().removePrefix(projectBasePath)
                        val cleanRelative = relativePath.replace("\\", "/").removePrefix("/")
                        files.add(
                            mapOf(
                                "path" to cleanRelative,
                                "name" to fileName,
                                "size" to try { Files.size(file) } catch (_: Exception) { 0L },
                                "is_directory" to Files.isDirectory(file)
                            )
                        )
                    }
                }
            }

            if (recursive) {
                Files.walk(targetPath, 4).forEach(collectFile)
            } else {
                Files.list(targetPath).forEach(collectFile)
            }

            val maxFiles = 100
            val limitedFiles = files.take(maxFiles)

            val result = mapOf(
                "path" to if (path.isEmpty()) "/" else path,
                "extension_filter" to (extension ?: "none"),
                "count" to files.size,
                "limited_count" to limitedFiles.size,
                "files" to limitedFiles
            )

            if (files.size > maxFiles) {
                ToolResult.Success(
                    result + mapOf(
                        "warning" to "文件数量过多，已限制为 $maxFiles 个文件，请按需查询具体目录"
                    )
                )
            } else {
                ToolResult.Success(result)
            }
        } catch (e: Exception) {
            ToolResult.Error("Error listing files: ${e.message}")
        }
    }
}