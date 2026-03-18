package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.io.File

/**
 * 删除文件或目录工具
 */
class DeleteFileTool : Tool(
    name = "delete_file",
    description = "Delete a file or directory in the project. For directories, all contents will be deleted recursively.",
    parameters = listOf(
        ToolParameter(
            name = "path",
            type = "string",
            description = "The path to the file or directory to delete (relative to project root or absolute)",
            required = true
        ),
        ToolParameter(
            name = "confirm",
            type = "boolean",
            description = "Must be set to true to confirm deletion. This is a safety measure to prevent accidental deletions.",
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
        val path = params["path"] as? String ?: return ToolResult.Error("Missing required parameter: path")
        val confirm = params["confirm"] as? Boolean ?: false

        if (!confirm) {
            return ToolResult.Error("Deletion not confirmed. Set 'confirm' parameter to true to delete the file.")
        }

        return try {
            val resolvedPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Invalid path: $path")

            val file = resolvedPath.toFile()

            if (!file.exists()) {
                return ToolResult.Error("File or directory does not exist: $path")
            }

            // 安全检查：确保文件在项目目录内
            val projectBasePath = project.basePath
            if (projectBasePath != null) {
                val canonicalFilePath = file.canonicalPath
                val canonicalProjectPath = File(projectBasePath).canonicalPath
                if (canonicalFilePath == null || canonicalProjectPath == null ||
                    !canonicalFilePath.startsWith(canonicalProjectPath)) {
                    return ToolResult.Error("Cannot delete files outside of project directory")
                }
            }

            val isDirectory = file.isDirectory
            val fileName = file.name

            onOutput?.invoke("🗑️ Deleting ${if (isDirectory) "directory" else "file"}: $path...")

            val deleted = deleteRecursively(file)

            if (deleted) {
                onOutput?.invoke("✅ Successfully deleted: $fileName")
                ToolResult.Success(
                    mapOf(
                        "path" to path,
                        "name" to fileName,
                        "is_directory" to isDirectory,
                        "deleted" to true
                    )
                )
            } else {
                ToolResult.Error("Failed to delete: $path")
            }
        } catch (e: Exception) {
            ToolResult.Error("Error deleting file: ${e.message}")
        }
    }

    /**
     * 递归删除文件或目录
     */
    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }
}
