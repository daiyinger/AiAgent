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
    private fun log(message: String) = LogService.log("[EditFileTool] $message")
    
    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val path = params["path"] as? String ?: return ToolResult.Error("Missing required parameter: path")
        val newText = (params["new_text"] as? String)?.replace("\r\n", "\n") 
            ?: return ToolResult.Error("Missing required parameter: new_text")
        
        // 预处理：统一将 \r\n 替换为 \n，防止 AI 产生的系统级换行符差异导致匹配失败
        val oldText = (params["old_text"] as? String)?.replace("\r\n", "\n")
        
        // 检查是否提供了行号参数
        val startLine = (params["start_line"] as? Number)?.toInt()
        val endLine = (params["end_line"] as? Number)?.toInt()
        
        // 确保至少提供了 old_text 或行号
        if (oldText == null && (startLine == null || endLine == null)) {
            return ToolResult.Error("Missing required parameter: either 'old_text' or both 'start_line' and 'end_line' must be provided")
        }

        log("开始执行编辑文件操作")
        log("参数: path=$path")
        if (oldText != null) {
            log("参数: oldText长度=${oldText.length}, 前50字符=${oldText.take(50)}")
        } else {
            log("参数: oldText=null (使用行号模式)")
        }
        log("参数: newText长度=${newText.length}, 前50字符=${newText.take(50)}")
        log("参数: startLine=${startLine}, endLine=${endLine}")

        // 验证行号参数
        if (startLine != null && endLine != null) {
            if (startLine < 1) {
                log("错误: startLine必须大于0, 实际值=$startLine")
                return ToolResult.Error("Invalid start_line: must be greater than 0, got $startLine")
            }
            if (endLine < startLine) {
                log("错误: endLine必须大于或等于startLine, 实际值=$endLine")
                return ToolResult.Error("Invalid end_line: must be greater than or equal to start_line, got $endLine")
            }
        }

        return try {
            val resolvedPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Project base path not found")

            log("解析后的路径: $resolvedPath")
            val virtualFile = findVirtualFile(resolvedPath)

            if (virtualFile != null && virtualFile.exists()) {
                log("文件已存在，开始编辑: ${virtualFile.path}")
                handleExistingFile(project, virtualFile, path, mapOf(
                    "path" to path,
                    "old_text" to (oldText ?: ""),
                    "new_text" to newText,
                    "start_line" to (startLine ?: 0),
                    "end_line" to (endLine ?: 0)
                ) as Map<String, Any>)
            } else {
                log("文件不存在，创建新文件: $path")
                handleNewFile(project, path, newText)
            }
        } catch (e: Exception) {
            log("编辑文件失败: ${e.message}, 异常类型=${e.javaClass.simpleName}")
            log("异常堆栈: ${e.stackTraceToString()}")
            ToolResult.Error("Error editing file: ${e.message}")
        }
    }

    private fun handleExistingFile(
        project: Project, virtualFile: VirtualFile, path: String, params: Map<String, Any>
    ): ToolResult {
        log("handleExistingFile: 开始处理已存在文件")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return ToolResult.Error("...")
        val newText = (params["new_text"] as String).replace("\r\n", "\n")
        
        val startLine = (params["start_line"] as? Number)?.toInt()
        val endLine = (params["end_line"] as? Number)?.toInt()
        val oldText = params["old_text"] as? String

        log("handleExistingFile: startLine=$startLine, endLine=$endLine, oldText=${oldText?.take(30)}...")

        return try {
            if (startLine != null && endLine != null && startLine > 0 && endLine > 0) {
                // =============== 方案 A：基于行号精确替换 ===============
                log("使用行号替换模式: startLine=$startLine, endLine=$endLine")
                val lineCount = document.lineCount
                log("文件总行数: $lineCount")
                
                if (startLine < 1 || endLine > lineCount) {
                    log("错误: 行号超出范围. startLine=$startLine, endLine=$endLine, lineCount=$lineCount")
                    return ToolResult.Error("Invalid line numbers. File has $lineCount lines, but you specified $startLine to $endLine.")
                }
                
                // 转换为 IntelliJ 的 0-based offset
                val startOffset = document.getLineStartOffset(startLine - 1)
                val endOffset = document.getLineEndOffset(endLine - 1)
                log("行号转offset: startOffset=$startOffset, endOffset=$endOffset")
                
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(startOffset, endOffset, newText)
                    FileDocumentManager.getInstance().saveDocument(document)
                }
                log("行号替换成功")
                
                val startLineText = "Replaced lines $startLine to $endLine"
                val oldLineCount = 0
                val newLineCount = 0
                val startLineNumber = startLine
                
                buildSuccessResult(path, startLineText, newText, oldLineCount, newLineCount, startLineNumber)

            } else if (oldText != null) {
                // =============== 方案 B：退回到之前的字符串匹配 ===============
                log("使用文本匹配替换模式")
                val cleanOld = oldText.replace("\r\n", "\n")
                val fileContent = document.text
                log("文件内容长度: ${fileContent.length}")
                
                // 1. 尝试精确检查 
                val startIndex = fileContent.indexOf(cleanOld) 
                log("精确查找结果: startIndex=$startIndex")
                if (startIndex == -1) { 
                    // 【新增】：去空格模糊检查，帮助 AI 诊断问题 
                    val strippedFile = fileContent.replace(Regex("\\s+"), "") 
                    val strippedOld = cleanOld.replace(Regex("\\s+"), "") 
                    log("模糊查找: strippedFile长度=${strippedFile.length}, strippedOld长度=${strippedOld.length}")
                    log("模糊查找结果: ${strippedFile.contains(strippedOld)}")
                    
                    if (strippedFile.contains(strippedOld)) { 
                        log("错误: 匹配失败，空格/缩进不同")
                        return ToolResult.Error( 
                            "Match failed due to whitespace/indentation differences. " + 
                            "The code exists, but your 'old_text' has different spaces or newlines. " + 
                            "CRITICAL: You must copy the exact indentation and blank lines from the file!" 
                        ) 
                    } else { 
                        log("错误: 未找到匹配文本")
                        return ToolResult.Error("Old text not found in file: $path. Are you sure this exact code block exists?") 
                    } 
                } 
                
                // 2. 防御性检查：确保 oldText 在文件中是唯一的
                val secondIndex = fileContent.indexOf(cleanOld, startIndex + cleanOld.length)
                log("唯一性检查: secondIndex=$secondIndex")
                if (secondIndex != -1) {
                    log("警告: 文本重复出现，将替换第一个匹配项")
                } 
                
                // 找到唯一匹配，进行替换
                val startOffset = startIndex
                val endOffset = startIndex + cleanOld.length
                log("准备替换: startOffset=$startOffset, endOffset=$endOffset, 替换长度=${cleanOld.length}")
                
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(startOffset, endOffset, newText)
                    FileDocumentManager.getInstance().saveDocument(document)
                }
                log("文本替换成功")
                
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
                log("替换统计: startLineNumber=$startLineNumber, oldLineCount=$oldLineCount, newLineCount=$newLineCount")
                
                buildSuccessResult(path, cleanOld, newText, oldLineCount, newLineCount, startLineNumber)
            } else {
                log("错误: 缺少必要参数")
                ToolResult.Error("You must provide either 'old_text' OR ('start_line' and 'end_line').")
            }
        } catch (e: IndexOutOfBoundsException) {
            log("异常: IndexOutOfBoundsException - ${e.message}")
            ToolResult.Error("Invalid line numbers provided. The file might be shorter than expected.")
        } catch (e: Exception) {
            log("异常: ${e.javaClass.simpleName} - ${e.message}")
            log("异常堆栈: ${e.stackTraceToString()}")
            ToolResult.Error("Error in handleExistingFile: ${e.message}")
        }
    }

    private fun handleNewFile(
        project: Project,
        path: String,
        newText: String
    ): ToolResult {
        log("handleNewFile: 开始创建新文件")
        log("handleNewFile: path=$path")
        log("handleNewFile: newText长度=${newText.length}")

        val document = WriteCommandAction.runWriteCommandAction<com.intellij.openapi.editor.Document?>(project) {
            try {
                val basePath = project.basePath ?: return@runWriteCommandAction null
                log("handleNewFile: basePath=$basePath")
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return@runWriteCommandAction null
                log("handleNewFile: baseDir=${baseDir.path}")

                val normalizedRelPath = normalizePath(path)
                log("handleNewFile: normalizedRelPath=$normalizedRelPath")
                val parentDirPath = normalizedRelPath.substringBeforeLast('/', "")
                val fileName = normalizedRelPath.substringAfterLast('/')
                log("handleNewFile: parentDirPath=$parentDirPath, fileName=$fileName")

                // 使用 VfsUtil 一键创建多级目录，替代原有的 for 循环
                val targetDir = if (parentDirPath.isEmpty()) baseDir else VfsUtil.createDirectoryIfMissing(baseDir, parentDirPath)
                log("handleNewFile: targetDir=${targetDir?.path}")
                val createdVirtualFile = targetDir?.createChildData(this, fileName)
                log("handleNewFile: createdVirtualFile=${createdVirtualFile?.path}")

                val doc = FileDocumentManager.getInstance().getDocument(createdVirtualFile ?: return@runWriteCommandAction null) ?: return@runWriteCommandAction null
                doc.setText(newText)
                FileDocumentManager.getInstance().saveDocument(doc)
                log("handleNewFile: 文件内容已设置并保存")
                doc
            } catch (e: Exception) {
                log("handleNewFile: 创建文件异常 - ${e.javaClass.simpleName}: ${e.message}")
                log("handleNewFile: 异常堆栈 - ${e.stackTraceToString()}")
                null
            }
        } ?: return ToolResult.Error("Could not create or initialize file: $path")

        log("handleNewFile: 新文件创建成功")
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