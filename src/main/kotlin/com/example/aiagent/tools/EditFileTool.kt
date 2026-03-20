package com.example.aiagent.tools

import com.example.aiagent.service.LogService
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class EditFileTool : Tool(
    name = "edit_file",
    description = """
        Edit a file by replacing lines at specified line numbers.
        
        Usage:
        - Provide 'path', 'new_text', 'start_line', and 'end_line' to replace specific lines
        - If file doesn't exist, it will be created with new_text content
        - Line numbers are 1-based (first line is line 1)
        - To insert new lines, set start_line = end_line + 1 (e.g., to insert after line 5, use start_line=6, end_line=5)
        
        Example:
        - Replace lines 10-15: start_line=10, end_line=15, new_text="new code here"
        - Insert after line 10: start_line=11, end_line=10, new_text="inserted line"
    """.trimIndent(),
    parameters = listOf(
        ToolParameter(
            name = "path",
            type = "string",
            description = "The file path to edit (relative to project root or absolute)",
            required = true
        ),
        ToolParameter(
            name = "new_text",
            type = "string",
            description = "The new text content to insert/replace with",
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
        )
    )
) {
    private fun log(message: String) = LogService.log("[EditFileTool] $message")

    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val path = params["path"] as? String
            ?: return ToolResult.Error("Missing required parameter: path")
        
        val newText = (params["new_text"] as? String)?.replace("\r\n", "\n")
            ?: return ToolResult.Error("Missing required parameter: new_text")
        
        val startLine = (params["start_line"] as? Number)?.toInt()
            ?: return ToolResult.Error("Missing required parameter: start_line")
        
        val endLine = (params["end_line"] as? Number)?.toInt()
            ?: return ToolResult.Error("Missing required parameter: end_line")

        log("=== 开始编辑文件 ===")
        log("路径: $path")
        log("起始行: $startLine, 结束行: $endLine")
        log("新文本长度: ${newText.length}")

        if (startLine < 1) {
            return ToolResult.Error("start_line must be >= 1, got $startLine")
        }

        if (endLine < startLine - 1) {
            return ToolResult.Error("end_line must be >= start_line - 1, got end_line=$endLine, start_line=$startLine")
        }

        return try {
            val resolvedPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Project base path not found")

            log("解析后路径: $resolvedPath")
            val virtualFile = findVirtualFile(resolvedPath)

            if (virtualFile != null && virtualFile.exists()) {
                log("文件存在，执行编辑")
                editExistingFile(project, virtualFile, path, startLine, endLine, newText)
            } else {
                log("文件不存在，创建新文件")
                createNewFile(project, path, newText)
            }
        } catch (e: Exception) {
            log("编辑失败: ${e.message}")
            log("堆栈: ${e.stackTraceToString()}")
            ToolResult.Error("Error editing file: ${e.message}")
        }
    }

    private fun editExistingFile(
        project: Project,
        virtualFile: VirtualFile,
        path: String,
        startLine: Int,
        endLine: Int,
        newText: String
    ): ToolResult {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: return ToolResult.Error("Cannot get document for file: $path")

        val lineCount = document.lineCount
        log("文件总行数: $lineCount")

        val isInsertion = endLine == startLine - 1
        val oldText: String
        val actualStartLine: Int
        val actualEndLine: Int

        if (isInsertion) {
            if (startLine > lineCount + 1) {
                return ToolResult.Error("Cannot insert at line $startLine: file only has $lineCount lines")
            }
            
            actualStartLine = startLine
            actualEndLine = startLine - 1
            oldText = ""
            
            log("插入模式: 在第 $startLine 行后插入")
            
            val insertOffset = if (startLine <= lineCount) {
                document.getLineEndOffset(startLine - 1)
            } else {
                document.textLength
            }
            
            val textToInsert = if (newText.startsWith("\n")) newText else "\n$newText"
            
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(insertOffset, textToInsert)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            
            log("插入成功")
        } else {
            if (startLine > lineCount) {
                return ToolResult.Error("start_line ($startLine) exceeds file length ($lineCount lines)")
            }
            if (endLine > lineCount) {
                return ToolResult.Error("end_line ($endLine) exceeds file length ($lineCount lines)")
            }

            actualStartLine = startLine
            actualEndLine = endLine

            val startOffset = document.getLineStartOffset(startLine - 1)
            val endOffset = document.getLineEndOffset(endLine - 1)
            
            oldText = document.getText(com.intellij.openapi.util.TextRange.create(startOffset, endOffset))
            
            log("替换模式: 替换第 $startLine 到 $endLine 行")
            log("原始文本长度: ${oldText.length}")
            
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(startOffset, endOffset, newText)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            
            log("替换成功")
        }

        val oldLineCount = if (oldText.isEmpty()) 0 else oldText.lines().size
        val newLineCount = newText.lines().size
        val lineChange = newLineCount - oldLineCount

        return ToolResult.Success(
            mapOf(
                "path" to path,
                "success" to true,
                "message" to buildChangeMessage(lineChange, isInsertion),
                "old_text" to oldText,
                "new_text" to newText,
                "start_line" to actualStartLine,
                "end_line" to actualEndLine,
                "old_line_count" to oldLineCount,
                "new_line_count" to newLineCount,
                "line_change" to lineChange,
                "is_insertion" to isInsertion
            )
        )
    }

    private fun createNewFile(
        project: Project,
        path: String,
        newText: String
    ): ToolResult {
        log("创建新文件: $path")

        val document = WriteCommandAction.runWriteCommandAction<com.intellij.openapi.editor.Document?>(project) {
            try {
                val basePath = project.basePath ?: return@runWriteCommandAction null
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                    ?: return@runWriteCommandAction null

                val normalizedRelPath = normalizePath(path)
                val parentDirPath = normalizedRelPath.substringBeforeLast('/', "")
                val fileName = normalizedRelPath.substringAfterLast('/')

                log("父目录: $parentDirPath, 文件名: $fileName")

                val targetDir = if (parentDirPath.isEmpty()) {
                    baseDir
                } else {
                    VfsUtil.createDirectoryIfMissing(baseDir, parentDirPath)
                }

                val createdFile = targetDir?.createChildData(this, fileName)
                val doc = createdFile?.let { 
                    FileDocumentManager.getInstance().getDocument(it) 
                }

                doc?.setText(newText)
                FileDocumentManager.getInstance().saveDocument(doc ?: return@runWriteCommandAction null)
                
                log("新文件创建成功")
                doc
            } catch (e: Exception) {
                log("创建文件异常: ${e.message}")
                null
            }
        } ?: return ToolResult.Error("Failed to create file: $path")

        val newLineCount = newText.lines().size

        return ToolResult.Success(
            mapOf(
                "path" to path,
                "success" to true,
                "message" to "Created new file with $newLineCount lines",
                "old_text" to "",
                "new_text" to newText,
                "start_line" to 1,
                "end_line" to 0,
                "old_line_count" to 0,
                "new_line_count" to newLineCount,
                "line_change" to newLineCount,
                "is_insertion" to false,
                "is_new_file" to true
            )
        )
    }

    private fun buildChangeMessage(lineChange: Int, isInsertion: Boolean): String {
        return when {
            isInsertion -> "Inserted ${if (lineChange > 0) "$lineChange lines" else "content"}"
            lineChange > 0 -> "Added $lineChange lines"
            lineChange < 0 -> "Removed ${-lineChange} lines"
            else -> "No line change"
        }
    }
}
