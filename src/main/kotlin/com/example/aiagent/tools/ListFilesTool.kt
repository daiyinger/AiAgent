package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
    override suspend fun execute(project: Project, params: Map<String, Any>): ToolResult {
        val path = params["path"] as? String ?: ""
        val recursive = params["recursive"] as? Boolean ?: false
        val extension = params["extension"] as? String
        
        return try {
            val projectBasePath = project.basePath ?: return ToolResult.Error("Project base path not found")
            val targetPath = if (path.isEmpty() || path == "root") {
                Paths.get(projectBasePath)
            } else {
                Paths.get(projectBasePath, path.replace("/", "\\"))
            }
            
            if (!Files.exists(targetPath)) {
                return ToolResult.Error("Path does not exist: $path")
            }
            
            val files = mutableListOf<Map<String, Any>>()
            val ignoreDirs = listOf("build", ".gradle", ".git", ".idea", "captures", "app/build")
            
            if (recursive) {
                Files.walk(targetPath, 4) // 限制最大遍历深度为4
                    .forEach { file ->
                        // 过滤无用目录
                        var current = file
                        var shouldIgnore = false
                        while (current != targetPath.parent) {
                            if (ignoreDirs.contains(current.fileName?.toString())) {
                                shouldIgnore = true
                                break
                            }
                            current = current.parent
                            if (current == null) break
                        }
                        if (shouldIgnore) return@forEach
                        
                        val fileName = file.fileName?.toString() ?: ""
                        // 如果指定了扩展名，则只返回符合条件的文件，不返回目录
                        if (extension != null) {
                            if (Files.isDirectory(file)) {
                                // 如果指定了扩展名，跳过目录
                                return@forEach
                            } else if (!fileName.endsWith(".$extension", ignoreCase = true)) {
                                // 如果文件扩展名不匹配，跳过
                                return@forEach
                            }
                        }
                        val relativePath = file.toString().substringAfter(projectBasePath).replace("\\", "/").removePrefix("/")
                        files.add(
                            mapOf(
                                "path" to relativePath,
                                "name" to fileName,
                                "size" to Files.size(file),
                                "is_directory" to Files.isDirectory(file)
                            )
                        )
                    }
            } else {
                Files.list(targetPath)
                    .forEach { file ->
                        // 过滤无用目录
                        val fileName = file.fileName?.toString() ?: ""
                        if (Files.isDirectory(file) && ignoreDirs.contains(fileName)) {
                            return@forEach
                        }
                        
                        // 如果指定了扩展名，则只返回符合条件的文件，不返回目录
                        if (extension != null) {
                            if (Files.isDirectory(file)) {
                                // 如果指定了扩展名，跳过目录
                                return@forEach
                            } else if (!fileName.endsWith(".$extension", ignoreCase = true)) {
                                // 如果文件扩展名不匹配，跳过
                                return@forEach
                            }
                        }
                        val relativePath = file.toString().substringAfter(projectBasePath).replace("\\", "/").removePrefix("/")
                        files.add(
                            mapOf(
                                "path" to relativePath,
                                "name" to fileName,
                                "size" to Files.size(file),
                                "is_directory" to Files.isDirectory(file)
                            )
                        )
                    }
            }
            
            // 限制返回的文件数量，防止 Token 爆炸
            val maxFiles = 100
            val limitedFiles = if (files.size > maxFiles) {
                files.take(maxFiles)
            } else {
                files
            }
            
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