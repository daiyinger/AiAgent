package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.nio.charset.Charset

class ReadFileTool : Tool(
    name = "read_file",
    description = "Read the contents of a file in the project",
    parameters = listOf(
        ToolParameter(
            name = "path",
            type = "string",
            description = "The relative path to the file from the project root",
            required = true
        )
    )
) {
    override suspend fun execute(project: Project, params: Map<String, Any>, onOutput: ((String) -> Unit)?): ToolResult {
        val path = params["path"] as? String ?: return ToolResult.Error("Missing required parameter: path")

        return try {
            val resolvedPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Project base path not found")

            println("ReadFileTool - Resolved path: $resolvedPath")

            val virtualFile = findVirtualFile(resolvedPath)
                ?: return ToolResult.Error("File not found: $path (resolved: $resolvedPath)")

            if (!virtualFile.exists()) {
                return ToolResult.Error("File does not exist: $path")
            }

            if (virtualFile.isDirectory) {
                return ToolResult.Error("Cannot read directory: $path")
            }

            val content = String(virtualFile.contentsToByteArray(), Charset.forName("UTF-8"))

            ToolResult.Success(
                mapOf(
                    "path" to path,
                    "content" to content,
                    "size" to content.length
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("Error reading file: ${e.message}")
        }
    }
}