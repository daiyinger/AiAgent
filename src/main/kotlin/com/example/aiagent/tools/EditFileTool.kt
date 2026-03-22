package com.example.aiagent.tools

import com.example.aiagent.exceptions.AiAgentException
import com.example.aiagent.service.EditFileLogger
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
        
        SAFETY FEATURES:
        - checksum: Optional SHA-256 hash to verify file hasn't changed since last read
        - expected_old_text: Optional text that should be at the edit location (validates before replacing)
        
        IMPORTANT: After each edit, the file's line numbers may change. Always use the returned 
        'total_lines' and 'new_end_line' from the previous edit result to calculate line numbers 
        for subsequent edits.
        
        Examples:
        - Replace lines 10-15: start_line=10, end_line=15, new_text="new code here"
        - Insert after line 10: start_line=11, end_line=10, new_text="inserted line"
        - Safe edit with checksum: path="src/Main.kt", start_line=10, end_line=10, new_text="new", checksum="abc123"
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
        ),
        ToolParameter(
            name = "checksum",
            type = "string",
            description = "Optional SHA-256 checksum from previous read_file to verify file hasn't changed",
            required = false
        ),
        ToolParameter(
            name = "expected_old_text",
            type = "string",
            description = "Optional: Expected text at the edit location. Edit will fail if content doesn't match.",
            required = false
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
            ?: throw AiAgentException.ValidationException("Missing required parameter: path", "path")
        
        val newText = (params["new_text"] as? String)?.replace("\r\n", "\n")
            ?: throw AiAgentException.ValidationException("Missing required parameter: new_text", "new_text")
        
        val startLine = (params["start_line"] as? Number)?.toInt()
            ?: throw AiAgentException.ValidationException("Missing required parameter: start_line", "start_line")
        
        val endLine = (params["end_line"] as? Number)?.toInt()
            ?: throw AiAgentException.ValidationException("Missing required parameter: end_line", "end_line")

        val checksum = params["checksum"] as? String
        val expectedOldText = params["expected_old_text"] as? String

        log("=== 开始编辑文件 ===")
        log("路径: $path")
        log("起始行: $startLine, 结束行: $endLine")
        log("新文本长度: ${newText.length}")
        log("校验和: ${checksum?.take(16) ?: "未提供"}...")
        log("预期旧文本: ${if (expectedOldText != null) "已提供 (${expectedOldText.length}字符)" else "未提供"}")

        if (startLine < 1) {
            throw AiAgentException.ValidationException(
                "start_line must be >= 1, got $startLine",
                parameterName = "start_line"
            )
        }

        if (endLine < startLine - 1) {
            throw AiAgentException.ValidationException(
                "end_line must be >= start_line - 1, got end_line=$endLine, start_line=$startLine",
                parameterName = "end_line"
            )
        }

        return try {
            val resolvedPath = resolveFilePath(project, path)
                ?: throw AiAgentException.ConfigurationException("Project base path not found")

            log("解析后路径: $resolvedPath")
            val virtualFile = findVirtualFile(resolvedPath)

            if (virtualFile != null && virtualFile.exists()) {
                log("文件存在，执行编辑")
                editExistingFile(project, virtualFile, path, startLine, endLine, newText, checksum, expectedOldText)
            } else {
                log("文件不存在，创建新文件")
                createNewFile(project, path, newText)
            }
        } catch (e: AiAgentException) {
            throw e
        } catch (e: Exception) {
            log("编辑失败: ${e.message}")
            log("堆栈: ${e.stackTraceToString()}")
            throw AiAgentException.FileOperationException(
                "Error editing file: ${e.message}",
                filePath = path,
                cause = e
            )
        }
    }

    private fun editExistingFile(
        project: Project,
        virtualFile: VirtualFile,
        path: String,
        startLine: Int,
        endLine: Int,
        newText: String,
        expectedChecksum: String?,
        expectedOldText: String?
    ): ToolResult {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: return ToolResult.Error("Cannot get document for file: $path")

        val currentContent = document.text
        val lineCount = document.lineCount
        log("文件总行数: $lineCount")

        // 验证校验和（如果提供）
        if (expectedChecksum != null) {
            if (!verifyChecksum(currentContent, expectedChecksum)) {
                val currentChecksum = computeContentChecksum(currentContent)
                throw AiAgentException.FileOperationException(
                    "File checksum mismatch! File may have been modified since last read. " +
                    "Expected: ${expectedChecksum.take(16)}..., Got: ${currentChecksum.take(16)}... " +
                    "Please re-read the file to get the current content and checksum.",
                    filePath = path
                )
            }
            log("校验和验证通过 ✓")
        }

        // 验证预期旧文本（如果提供）
        if (expectedOldText != null && endLine >= startLine) {
            val startOffset = document.getLineStartOffset(startLine - 1)
            val endOffset = document.getLineEndOffset(endLine - 1)
            val actualOldText = document.getText(com.intellij.openapi.util.TextRange.create(startOffset, endOffset))
            
            if (actualOldText.trim() != expectedOldText.trim()) {
                log("旧文本验证失败!")
                log("期望: ${expectedOldText.take(100)}...")
                log("实际: ${actualOldText.take(100)}...")
                throw AiAgentException.FileOperationException(
                    "Content mismatch at lines $startLine-$endLine! " +
                    "Expected text doesn't match actual content. " +
                    "The file may have been modified. Please re-read the file to verify current content.",
                    filePath = path
                )
            }
            log("旧文本验证通过 ✓")
        }

        val isInsertion = endLine == startLine - 1
        val oldText: String
        val actualStartLine: Int
        val actualEndLine: Int

        if (isInsertion) {
            if (startLine > lineCount + 1) {
                throw AiAgentException.FileOperationException(
                    "Cannot insert at line $startLine: file only has $lineCount lines",
                    filePath = path
                )
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
            
            // 记录edit执行前的日志
            EditFileLogger.logExecution(
                filePath = path,
                startLine = startLine,
                endLine = endLine,
                newText = newText,
                originalContent = "(Insertion - no original content)",
                actualContent = "(Will be inserted)",
                success = true,
                lineChange = newText.lines().size,
                totalLinesAfter = lineCount + newText.lines().size
            )
            
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(insertOffset, textToInsert)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            
            log("插入成功")
        } else {
            if (startLine > lineCount) {
                throw AiAgentException.FileOperationException(
                    "start_line ($startLine) exceeds file length ($lineCount lines)",
                    filePath = path
                )
            }
            if (endLine > lineCount) {
                throw AiAgentException.FileOperationException(
                    "end_line ($endLine) exceeds file length ($lineCount lines)",
                    filePath = path
                )
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
        val totalLinesAfter = document.lineCount
        val newEndLine = actualStartLine + newLineCount - 1

        // 记录edit执行后的日志
        EditFileLogger.logExecution(
            filePath = path,
            startLine = startLine,
            endLine = endLine,
            newText = newText,
            originalContent = oldText,
            actualContent = newText,
            success = true,
            lineChange = lineChange,
            totalLinesAfter = totalLinesAfter
        )

        // 生成行号映射信息，帮助用户理解后续编辑的行号变化
        val lineMapping = generateLineMapping(
            originalLineCount = lineCount,
            editStartLine = actualStartLine,
            editEndLine = actualEndLine,
            newLineCount = newLineCount,
            isInsertion = isInsertion
        )

        return ToolResult.Success(
            mapOf(
                "path" to path,
                "success" to true,
                "message" to buildChangeMessage(lineChange, isInsertion, actualStartLine, newEndLine, totalLinesAfter),
                "old_text" to oldText,
                "new_text" to newText,
                "start_line" to actualStartLine,
                "end_line" to actualEndLine,
                "old_line_count" to oldLineCount,
                "new_line_count" to newLineCount,
                "line_change" to lineChange,
                "is_insertion" to isInsertion,
                "total_lines" to totalLinesAfter,
                "new_end_line" to newEndLine,
                "line_mapping" to lineMapping,
                "edited_range" to mapOf(
                    "start" to actualStartLine,
                    "end" to newEndLine,
                    "original_start" to actualStartLine,
                    "original_end" to actualEndLine
                ),
                "warning" to "IMPORTANT: Line numbers have changed! For subsequent edits to lines AFTER this edit, adjust line numbers by $lineChange. Use 'total_lines' and 'new_end_line' from this result to calculate new line numbers."
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
        } ?: throw AiAgentException.FileOperationException(
            "Failed to create file: $path",
            filePath = path
        )

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
                "is_new_file" to true,
                "total_lines" to newLineCount,
                "new_end_line" to newLineCount
            )
        )
    }

    private fun buildChangeMessage(lineChange: Int, isInsertion: Boolean, startLine: Int, newEndLine: Int, totalLines: Int): String {
        return when {
            isInsertion -> "Inserted $lineChange lines at line $startLine. File now has $totalLines lines. New content ends at line $newEndLine."
            lineChange > 0 -> "Replaced lines $startLine-$newEndLine (+$lineChange lines). File now has $totalLines lines."
            lineChange < 0 -> "Replaced lines $startLine-$newEndLine (${lineChange} lines). File now has $totalLines lines."
            else -> "Replaced lines $startLine-$newEndLine (no line change). File has $totalLines lines."
        }
    }

    /**
     * 生成行号映射信息，帮助用户理解后续编辑时行号的变化
     */
    private fun generateLineMapping(
        originalLineCount: Int,
        editStartLine: Int,
        editEndLine: Int,
        newLineCount: Int,
        isInsertion: Boolean
    ): Map<String, Any> {
        val oldLineCount = if (isInsertion) 0 else editEndLine - editStartLine + 1
        val lineChange = newLineCount - oldLineCount
        
        return mapOf(
            "original_file_lines" to originalLineCount,
            "edited_range" to mapOf(
                "start" to editStartLine,
                "end" to if (isInsertion) editStartLine - 1 else editEndLine,
                "line_count_before" to oldLineCount,
                "line_count_after" to newLineCount
            ),
            "line_change" to lineChange,
            "mapping_rules" to listOf(
                "Lines BEFORE edit range (1 to ${editStartLine - 1}): unchanged",
                "Lines IN edit range (${editStartLine} to ${if (isInsertion) editStartLine - 1 else editEndLine}): replaced with $newLineCount lines",
                "Lines AFTER edit range (${if (isInsertion) editStartLine else editEndLine + 1} to $originalLineCount): shifted by $lineChange lines"
            ),
            "example" to if (lineChange != 0) {
                "If you need to edit line ${editEndLine + 1} from original file, use line ${editEndLine + 1 + lineChange} now"
            } else {
                "No line number adjustment needed for lines after the edit"
            }
        )
    }
}
