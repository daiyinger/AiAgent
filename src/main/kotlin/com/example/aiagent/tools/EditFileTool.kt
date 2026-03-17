package com.example.aiagent.tools

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class EditFileTool : Tool(
    name = "edit_file",
    description = "Edit a file by replacing a specific portion of its content. Ensure 'old_text' is unique in the file.",
    parameters = listOf(
         ToolParameter(name = "path", type = "string", description = "...", required = true),
        ToolParameter(name = "new_text", type = "string", description = "The new code to insert", required = true),
        // 将 old_text 改为非必填，或者作为备用选项
        ToolParameter(name = "old_text", type = "string", description = "The exact text to replace. (Optional if start_line and end_line are provided)", required = false),
        ToolParameter(name = "start_line", type = "integer", description = "The starting line number (1-based) to replace.", required = false),
        ToolParameter(name = "end_line", type = "integer", description = "The ending line number (1-based) to replace.", required = false)
    )
) {
    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val path = params["path"] as? String ?: return ToolResult.Error("Missing required parameter: path")
        // 预处理：统一将 \r\n 替换为 \n，防止 AI 产生的系统级换行符差异导致匹配失败
        val oldText = (params["old_text"] as? String)?.replace("\r\n", "\n") 
            ?: return ToolResult.Error("Missing required parameter: old_text")
        val newText = (params["new_text"] as? String)?.replace("\r\n", "\n") 
            ?: return ToolResult.Error("Missing required parameter: new_text")

        return try {
            val resolvedPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Project base path not found")

            println("EditFileTool - Resolved path: $resolvedPath")
            val virtualFile = findVirtualFile(resolvedPath)

            if (virtualFile != null && virtualFile.exists()) {
                handleExistingFile(project, virtualFile, path, mapOf(
                    "path" to path,
                    "old_text" to oldText,
                    "new_text" to newText
                ))
            } else {
                handleNewFile(project, path, newText)
            }
        } catch (e: Exception) {
            ToolResult.Error("Error editing file: ${e.message}")
        }
    }

    private fun handleExistingFile(
        project: Project, virtualFile: VirtualFile, path: String, params: Map<String, Any>
    ): ToolResult {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return ToolResult.Error("...")
        val newText = (params["new_text"] as String).replace("\r\n", "\n")
        
        val startLine = (params["start_line"] as? Number)?.toInt()
        val endLine = (params["end_line"] as? Number)?.toInt()
        val oldText = params["old_text"] as? String

        return try {
            if (startLine != null && endLine != null) {
                // =============== 方案 A：基于行号精确替换 ===============
                // 转换为 IntelliJ 的 0-based offset
                val startOffset = document.getLineStartOffset(startLine - 1)
                val endOffset = document.getLineEndOffset(endLine - 1)
                
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(startOffset, endOffset, newText)
                    FileDocumentManager.getInstance().saveDocument(document)
                }
                
                val startLineText = "Replaced lines $startLine to $endLine"
                val oldLineCount = 0
                val newLineCount = 0
                val startLineNumber = startLine
                
                buildSuccessResult(path, startLineText, newText, oldLineCount, newLineCount, startLineNumber)

            } else if (oldText != null) {
                // =============== 方案 B：退回到之前的字符串匹配 ===============
                val cleanOld = oldText.replace("\r\n", "\n")
                val fileContent = document.text
                
                // 1. 尝试精确检查 
                val startIndex = fileContent.indexOf(cleanOld) 
                if (startIndex == -1) { 
                    // 【新增】：去空格模糊检查，帮助 AI 诊断问题 
                    val strippedFile = fileContent.replace(Regex("\\s+"), "") 
                    val strippedOld = cleanOld.replace(Regex("\\s+"), "") 
                    
                    if (strippedFile.contains(strippedOld)) { 
                        return ToolResult.Error( 
                            "Match failed due to whitespace/indentation differences. " + 
                            "The code exists, but your 'old_text' has different spaces or newlines. " + 
                            "CRITICAL: You must copy the exact indentation and blank lines from the file!" 
                        ) 
                    } else { 
                        return ToolResult.Error("Old text not found in file: $path. Are you sure this exact code block exists?") 
                    } 
                } 
                
                // 2. 防御性检查：确保 oldText 在文件中是唯一的 
                if (fileContent.indexOf(cleanOld, startIndex + cleanOld.length) != -1) { 
                    return ToolResult.Error("The 'old_text' provided appears multiple times. Please include 2-3 lines of surrounding context (above and below) to make it unique.") 
                } 
                
                // 找到唯一匹配，进行替换
                val startOffset = startIndex
                val endOffset = startIndex + cleanOld.length
                
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(startOffset, endOffset, newText)
                    FileDocumentManager.getInstance().saveDocument(document)
                }
                
                val textBeforeOldText = fileContent.substring(0, startIndex)
                val linesBefore = textBeforeOldText.lines()
                val actualLineCount = if (textBeforeOldText.isEmpty()) 0 else {
                    if (textBeforeOldText.endsWith("\n")) {
                        linesBefore.size + 1
                    } else {
                        linesBefore.size
                    }
                }
                val startLineNumber = actualLineCount + 1
                val oldLineCount = cleanOld.lines().size
                val newLineCount = newText.lines().size
                
                buildSuccessResult(path, cleanOld, newText, oldLineCount, newLineCount, startLineNumber)
            } else {
                ToolResult.Error("You must provide either 'old_text' OR ('start_line' and 'end_line').")
            }
        } catch (e: IndexOutOfBoundsException) {
            ToolResult.Error("Invalid line numbers provided. The file might be shorter than expected.")
        }
    }

    private fun handleNewFile(
        project: Project,
        path: String,
        newText: String
    ): ToolResult {
        println("EditFileTool - File not found, creating new file: $path")

        val document = WriteCommandAction.runWriteCommandAction<com.intellij.openapi.editor.Document?>(project) {
            try {
                val basePath = project.basePath ?: return@runWriteCommandAction null
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return@runWriteCommandAction null

                val normalizedRelPath = normalizePath(path)
                val parentDirPath = normalizedRelPath.substringBeforeLast('/', "")
                val fileName = normalizedRelPath.substringAfterLast('/')

                // 使用 VfsUtil 一键创建多级目录，替代原有的 for 循环
                val targetDir = if (parentDirPath.isEmpty()) baseDir else VfsUtil.createDirectoryIfMissing(baseDir, parentDirPath)
                val createdVirtualFile = targetDir.createChildData(this, fileName)

                val doc = FileDocumentManager.getInstance().getDocument(createdVirtualFile) ?: return@runWriteCommandAction null
                doc.setText(newText)
                FileDocumentManager.getInstance().saveDocument(doc)
                doc
            } catch (e: Exception) {
                null
            }
        } ?: return ToolResult.Error("Could not create or initialize file: $path")

        return buildSuccessResult(path, "", newText, 0, newText.lines().size, 1)
    }

    private fun buildSuccessResult(
        path: String,
        oldText: String,
        newText: String,
        oldLineCount: Int,
        newLineCount: Int,
        startLineNumber: Int
    ): ToolResult {
        val lineChange = newLineCount - oldLineCount
        val lineChangeText = when {
            lineChange > 0 -> "+$lineChange "
            lineChange < 0 -> "$lineChange "
            else -> "0 "
        }

        return ToolResult.Success(
            mapOf(
                "path" to path,
                "message" to "File edited successfully ($lineChangeText)",
                "changes" to mapOf(
                    "old_text_length" to oldText.length,
                    "new_text_length" to newText.length,
                    "old_line_count" to oldLineCount,
                    "new_line_count" to newLineCount,
                    "line_change" to lineChange,
                    "start_line_number" to startLineNumber
                )
            )
        )
    }
}