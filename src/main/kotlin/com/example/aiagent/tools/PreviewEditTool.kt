package com.example.aiagent.tools

import com.example.aiagent.service.EditFileLogger
import com.example.aiagent.service.LogService
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import java.nio.charset.Charset

/**
 * 编辑预览工具
 * 在实际执行编辑前，预览将要进行的修改
 */
class PreviewEditTool : Tool(
    name = "preview_edit",
    description = """
        Preview what an edit would look like without actually applying it.
        
        This tool helps verify edits before execution by showing:
        - The diff between original and modified content
        - How many lines will be affected
        - Whether the edit can be safely applied
        
        Use this before edit_file to confirm your changes are correct.
        
        Example:
        - Preview replacing lines 10-15: path="src/Main.kt", start_line=10, end_line=15, new_text="new code"
    """.trimIndent(),
    parameters = listOf(
        ToolParameter(
            name = "path",
            type = "string",
            description = "The file path to preview edit for (relative to project root or absolute)",
            required = true
        ),
        ToolParameter(
            name = "start_line",
            type = "integer",
            description = "Starting line number (1-based). For insertion after line N, use start_line=N+1",
            required = true
        ),
        ToolParameter(
            name = "end_line",
            type = "integer",
            description = "Ending line number (1-based). For insertion, use end_line=start_line-1",
            required = true
        ),
        ToolParameter(
            name = "new_text",
            type = "string",
            description = "The new text content to insert/replace with",
            required = true
        ),
        ToolParameter(
            name = "context_lines",
            type = "integer",
            description = "Number of context lines to show before and after the change (default: 3)",
            required = false
        )
    )
) {
    private fun log(message: String) = LogService.log("[PreviewEditTool] $message")

    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val path = params["path"] as? String
            ?: return ToolResult.Error("Missing required parameter: path")

        val startLine = (params["start_line"] as? Number)?.toInt()
            ?: return ToolResult.Error("Missing required parameter: start_line")

        val endLine = (params["end_line"] as? Number)?.toInt()
            ?: return ToolResult.Error("Missing required parameter: end_line")

        val newText = (params["new_text"] as? String)?.replace("\r\n", "\n")
            ?: return ToolResult.Error("Missing required parameter: new_text")

        val contextLines = (params["context_lines"] as? Number)?.toInt()?.coerceIn(0, 10) ?: 3

        log("=== 预览编辑 ===")
        log("路径: $path")
        log("起始行: $startLine, 结束行: $endLine")
        log("上下文行数: $contextLines")

        if (startLine < 1) {
            return ToolResult.Error("start_line must be >= 1, got $startLine")
        }

        if (endLine < startLine - 1) {
            return ToolResult.Error("end_line must be >= start_line - 1, got end_line=$endLine, start_line=$startLine")
        }

        return try {
            val resolvedPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Project base path not found")

            val virtualFile = findVirtualFile(resolvedPath)
                ?: return ToolResult.Error("File not found: $path")

            if (!virtualFile.exists()) {
                return ToolResult.Error("File does not exist: $path")
            }

            if (virtualFile.isDirectory) {
                return ToolResult.Error("Cannot preview edit for directory: $path")
            }

            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: return ToolResult.Error("Cannot get document for file: $path")

            val currentContent = document.text
            val allLines = currentContent.lineSequence().toList()
            val totalLineCount = allLines.size

            log("文件总行数: $totalLineCount")

            // 验证行号范围
            if (startLine > totalLineCount + 1) {
                return ToolResult.Error("start_line ($startLine) exceeds file length ($totalLineCount lines)")
            }

            if (endLine > totalLineCount && endLine != startLine - 1) {
                return ToolResult.Error("end_line ($endLine) exceeds file length ($totalLineCount lines)")
            }

            val isInsertion = endLine == startLine - 1
            val oldText: String

            if (isInsertion) {
                oldText = ""
                log("插入模式: 在第 $startLine 行后插入")
            } else {
                // 获取要被替换的原始文本
                oldText = allLines.subList(startLine - 1, endLine).joinToString("\n")
                log("替换模式: 替换第 $startLine 到 $endLine 行")
            }

            // 计算修改后的内容
            val modifiedLines = allLines.toMutableList()
            if (isInsertion) {
                // 插入模式
                val insertIndex = if (startLine <= totalLineCount) startLine else totalLineCount
                val linesToInsert = newText.lines()
                modifiedLines.addAll(insertIndex, linesToInsert)
            } else {
                // 替换模式
                val linesToRemove = endLine - startLine + 1
                repeat(linesToRemove) {
                    if (modifiedLines.size >= startLine) {
                        modifiedLines.removeAt(startLine - 1)
                    }
                }
                val linesToInsert = newText.lines()
                modifiedLines.addAll(startLine - 1, linesToInsert)
            }

            val modifiedContent = modifiedLines.joinToString("\n")

            // 生成 diff
            val diff = generateDiff(
                oldLines = allLines,
                newLines = modifiedLines,
                newText = newText,
                startLine = startLine,
                oldEndLine = endLine,
                contextLines = contextLines
            )

            // 计算统计信息
            val oldLineCount = if (oldText.isEmpty()) 0 else oldText.lines().size
            val newLineCount = newText.lines().size
            val lineChange = newLineCount - oldLineCount
            val newEndLine = startLine + newLineCount - 1
            val totalLinesAfter = modifiedLines.size

            // 验证是否可以安全应用
            val canApply = startLine >= 1 && (endLine <= totalLineCount || isInsertion)

            // 记录预览日志
            EditFileLogger.logPreview(
                filePath = path,
                startLine = startLine,
                endLine = endLine,
                newText = newText,
                originalContent = oldText,
                diff = diff,
                lineChange = lineChange
            )

            ToolResult.Success(
                mapOf(
                    "path" to path,
                    "can_apply" to canApply,
                    "is_insertion" to isInsertion,
                    "start_line" to startLine,
                    "end_line" to endLine,
                    "old_text" to oldText,
                    "new_text" to newText,
                    "old_line_count" to oldLineCount,
                    "new_line_count" to newLineCount,
                    "line_change" to lineChange,
                    "new_end_line" to newEndLine,
                    "total_lines_before" to totalLineCount,
                    "total_lines_after" to totalLinesAfter,
                    "diff" to diff,
                    "summary" to buildSummary(lineChange, isInsertion, startLine, newEndLine, totalLinesAfter)
                )
            )
        } catch (e: Exception) {
            log("预览失败: ${e.message}")
            ToolResult.Error("Error previewing edit: ${e.message}")
        }
    }

    /**
     * 生成 diff 显示
     */
    private fun generateDiff(
        oldLines: List<String>,
        newLines: List<String>,
        newText: String,
        startLine: Int,
        oldEndLine: Int,
        contextLines: Int
    ): String {
        val diffBuilder = StringBuilder()
        val isInsertion = oldEndLine == startLine - 1
        
        // 计算上下文范围（修正后的逻辑）
        val editStartIndex = startLine - 1  // 0-based 起始索引
        val editEndIndex = if (isInsertion) editStartIndex else oldEndLine - 1  // 0-based 结束索引
        
        // 前导上下文：从编辑区域前 contextLines 行开始
        val contextStartIndex = (editStartIndex - contextLines).coerceAtLeast(0)
        // 后续上下文：到编辑区域后 contextLines 行结束
        val contextEndIndex = (editEndIndex + contextLines + 1).coerceAtMost(oldLines.size)
        
        // 添加前导上下文（编辑区域之前的行）
        for (i in contextStartIndex until editStartIndex) {
            val lineNum = i + 1
            diffBuilder.appendLine("  $lineNum| ${oldLines[i]}")
        }
        
        // 添加删除的行（如果是替换模式）
        if (!isInsertion) {
            for (i in editStartIndex..editEndIndex) {
                val lineNum = i + 1
                diffBuilder.appendLine("- $lineNum| ${oldLines[i]}")
            }
        }
        
        // 添加新增的行
        val newLinesList = newText.lines()
        for ((index, line) in newLinesList.withIndex()) {
            val lineNum = startLine + index
            diffBuilder.appendLine("+ $lineNum| $line")
        }
        
        // 添加后续上下文（编辑区域之后的行）
        val afterEditIndex = if (isInsertion) editStartIndex else editEndIndex + 1
        for (i in afterEditIndex until contextEndIndex) {
            val lineNum = i + 1
            diffBuilder.appendLine("  $lineNum| ${oldLines[i]}")
        }
        
        // 添加省略标记（如果需要）
        if (contextStartIndex > 0) {
            diffBuilder.insert(0, "... (省略 ${contextStartIndex} 行)\n")
        }
        if (contextEndIndex < oldLines.size) {
            diffBuilder.appendLine("... (省略 ${oldLines.size - contextEndIndex} 行)")
        }

        return diffBuilder.toString()
    }

    private fun buildSummary(lineChange: Int, isInsertion: Boolean, startLine: Int, newEndLine: Int, totalLines: Int): String {
        return when {
            isInsertion -> "Will insert $lineChange lines at line $startLine. File will have $totalLines lines."
            lineChange > 0 -> "Will replace lines $startLine-$newEndLine (+$lineChange lines). File will have $totalLines lines."
            lineChange < 0 -> "Will replace lines $startLine-$newEndLine ($lineChange lines). File will have $totalLines lines."
            else -> "Will replace lines $startLine-$newEndLine (no line change). File will have $totalLines lines."
        }
    }
}