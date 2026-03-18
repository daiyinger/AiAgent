package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.io.File

/**
 * 创建目录工具
 */
class CreateDirectoryTool : Tool(
    name = "create_directory",
    description = "Create a new directory in project. Parent directories will be created if they don't exist.",
    parameters = listOf(
        ToolParameter(
            name = "path",
            type = "string",
            description = "The path of directory to create (relative to project root or absolute)",
            required = true
        )
    )
) {
    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val path = params["path"] as? String ?: return ToolResult.Error("缺少必要参数: path")

        return try {
            val resolvedPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("无效路径: $path")

            val file = resolvedPath.toFile()

            // 安全检查：确保目录在项目目录内
            val projectBasePath = project.basePath
            if (projectBasePath != null) {
                val canonicalFilePath = file.canonicalPath
                val canonicalProjectPath = File(projectBasePath).canonicalPath
                if (canonicalFilePath == null || canonicalProjectPath == null ||
                    !canonicalFilePath.startsWith(canonicalProjectPath)) {
                    return ToolResult.Error("不能在项目目录外创建目录")
                }
            }

            // 检查路径是否已存在
            if (file.exists()) {
                if (file.isDirectory) {
                    onOutput?.invoke("ℹ️ 目录已存在: $path")
                    return ToolResult.Success(
                        mapOf(
                            "path" to path,
                            "name" to file.name,
                            "created" to false,
                            "already_exists" to true
                        )
                    )
                } else {
                    return ToolResult.Error("路径已存在且不是目录: $path")
                }
            }

            onOutput?.invoke("📁 创建目录: $path...")

            // 创建目录（包括所有不存在的父目录）
            val created = file.mkdirs()

            if (created) {
                onOutput?.invoke("✅ 成功创建目录: ${file.name}")
                ToolResult.Success(
                    mapOf(
                        "path" to path,
                        "name" to file.name,
                        "created" to true,
                        "already_exists" to false
                    )
                )
            } else {
                ToolResult.Error("创建目录失败: $path")
            }
        } catch (e: Exception) {
            ToolResult.Error("创建目录时出错: ${e.message}")
        }
    }
}
