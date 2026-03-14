package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile
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
            val projectBasePath = project.basePath ?: return ToolResult.Error("Project base path not found")
            val fullPath = if (path == "root") {
                projectBasePath
            } else {
                "$projectBasePath\\$path".replace("/", "\\")
            }
            
            // 打印调试信息
            println("ReadFileTool - Project base path: $projectBasePath")
            println("ReadFileTool - File path: $path")
            println("ReadFileTool - Full path: $fullPath")
            println("ReadFileTool - Full path URL: file://$fullPath")
            
            val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file:///$fullPath")
            if (virtualFile == null) {
                println("ReadFileTool - Virtual file is null")
                return ToolResult.Error("File not found: $path")
            }
            
            if (!virtualFile.exists()) {
                println("ReadFileTool - Virtual file does not exist")
                return ToolResult.Error("File does not exist: $path")
            }
            
            if (virtualFile.isDirectory) {
                println("ReadFileTool - Virtual file is a directory: ${virtualFile.path}")
                return ToolResult.Error("Cannot read directory: $path")
            }
            
            println("ReadFileTool - Virtual file found: ${virtualFile.path}")
            
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