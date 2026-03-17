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
        ),
        ToolParameter(
            name = "start_line",
            type = "integer",
            description = "起始行号（从1开始）。如果不指定，则从第1行开始",
            required = false
        ),
        ToolParameter(
            name = "end_line",
            type = "integer",
            description = "结束行号。如果不指定，则读取到文件末尾或达到max_lines限制",
            required = false
        ),
        ToolParameter(
            name = "max_lines",
            type = "integer",
            description = "最多读取的行数。如果不指定，则读取指定范围内的所有行",
            required = false
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

            val fullContent = String(virtualFile.contentsToByteArray(), Charset.forName("UTF-8"))
            val allLines = fullContent.lineSequence().toList()
            val totalLineCount = allLines.size
            
            // 解析行范围参数
            val startLine = (params["start_line"] as? Number)?.toInt()?.coerceIn(1, totalLineCount) ?: 1
            val endLine = (params["end_line"] as? Number)?.toInt()?.coerceIn(1, totalLineCount) ?: totalLineCount
            val maxLines = (params["max_lines"] as? Number)?.toInt()?.coerceAtLeast(1)
            
            // 计算实际读取的行范围
            var actualStartLine = startLine
            var actualEndLine = endLine
            
            // 确保开始行不大于结束行
            if (actualStartLine > actualEndLine) {
                actualEndLine = actualStartLine
            }
            
            // 应用max_lines限制
            if (maxLines != null) {
                val requestedLineCount = actualEndLine - actualStartLine + 1
                if (requestedLineCount > maxLines) {
                    actualEndLine = actualStartLine + maxLines - 1
                }
            }
            
            // 确保不超过文件总行数
            actualEndLine = actualEndLine.coerceAtMost(totalLineCount)
            
            // 提取指定行范围的内容
            val selectedLines = if (actualStartLine <= actualEndLine) {
                allLines.subList(actualStartLine - 1, actualEndLine)
            } else {
                emptyList()
            }
            
            val selectedContent = selectedLines.joinToString("\n")
            val actualReadLineCount = selectedLines.size

            ToolResult.Success(
                mapOf(
                    "path" to path,
                    "content" to selectedContent,
                    "size" to selectedContent.length,
                    "lineCount" to totalLineCount,
                    "actualStartLine" to actualStartLine,
                    "actualEndLine" to actualEndLine,
                    "actualReadLineCount" to actualReadLineCount
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("Error reading file: ${e.message}")
        }
    }
}