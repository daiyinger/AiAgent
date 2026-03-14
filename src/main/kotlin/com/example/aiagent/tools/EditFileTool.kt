package com.example.aiagent.tools

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile

class EditFileTool : Tool(
    name = "edit_file",
    description = "Edit a file by replacing a specific portion of its content",
    parameters = listOf(
        ToolParameter(
            name = "path",
            type = "string",
            description = "The relative path to the file from the project root",
            required = true
        ),
        ToolParameter(
            name = "old_text",
            type = "string",
            description = "The text to be replaced",
            required = true
        ),
        ToolParameter(
            name = "new_text",
            type = "string",
            description = "The new text to replace with",
            required = true
        )
    )
) {
    override suspend fun execute(project: Project, params: Map<String, Any>, onOutput: ((String) -> Unit)?): ToolResult {
        val path = params["path"] as? String ?: return ToolResult.Error("Missing required parameter: path")
        val oldText = params["old_text"] as? String ?: return ToolResult.Error("Missing required parameter: old_text")
        val newText = params["new_text"] as? String ?: return ToolResult.Error("Missing required parameter: new_text")
        
        return try {
            val projectBasePath = project.basePath ?: return ToolResult.Error("Project base path not found")
            val fullPath = if (path == "root") {
                projectBasePath
            } else {
                "$projectBasePath\\$path".replace("/", "\\")
            }
            
            // 打印调试信息
            println("EditFileTool - Project base path: $projectBasePath")
            println("EditFileTool - File path: $path")
            println("EditFileTool - Full path: $fullPath")
            println("EditFileTool - Full path URL: file://$fullPath")
            
            var virtualFile = VirtualFileManager.getInstance().findFileByUrl("file:///$fullPath")
            var document = if (virtualFile?.exists() == true) {
                FileDocumentManager.getInstance().getDocument(virtualFile)
            } else {
                null
            }
            
            var newContent: String
            var oldLineCount = 0
            var newLineCount = 0
            
            if (document != null) {
                // 文件存在，检查并替换内容
                val fileContent = document.text
                
                if (!fileContent.contains(oldText)) {
                    return ToolResult.Error("Old text not found in file: $path")
                }
                
                // 计算旧文本和新文本的行数
                oldLineCount = oldText.lines().size
                newLineCount = newText.lines().size
                
                newContent = fileContent.replace(oldText, newText)
                
                // 在WriteCommandAction中执行文件修改
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(newContent)
                    FileDocumentManager.getInstance().saveDocument(document)
                }
            } else {
                // 文件不存在，创建新文件
                println("EditFileTool - File not found, creating new file: $path")
                
                // 计算新文本的行数（旧行数为0）
                oldLineCount = 0
                newLineCount = newText.lines().size
                
                // 在WriteCommandAction中执行文件和目录创建
                var createdVirtualFile: VirtualFile? = null
                var createdDocument = WriteCommandAction.runWriteCommandAction<Any>(project) {
                    // 确保父目录存在
                    val parentPath = fullPath.substring(0, fullPath.lastIndexOf('\\'))
                    var parentVirtualFile = VirtualFileManager.getInstance().findFileByUrl("file:///$parentPath")
                    
                    if (parentVirtualFile == null || !parentVirtualFile.exists()) {
                        // 创建父目录
                        println("EditFileTool - Parent directory not found, creating: $parentPath")
                        
                        // 从项目根目录开始创建目录结构
                        var currentPath = projectBasePath
                        var currentVirtualFile = VirtualFileManager.getInstance().findFileByUrl("file:///$currentPath")
                        
                        val pathParts = path.split('/').dropLast(1) // 移除文件名，保留目录部分
                        for (part in pathParts) {
                            currentPath += "\\$part"
                            val nextVirtualFile = VirtualFileManager.getInstance().findFileByUrl("file:///$currentPath")
                            
                            if (nextVirtualFile == null || !nextVirtualFile.exists()) {
                                // 创建目录
                                currentVirtualFile = currentVirtualFile?.createChildDirectory(this, part)
                                    ?: return@runWriteCommandAction null
                            } else {
                                currentVirtualFile = nextVirtualFile
                            }
                        }
                        
                        // 创建新文件
                        val fileName = path.substring(path.lastIndexOf('/') + 1)
                        createdVirtualFile = currentVirtualFile?.createChildData(this, fileName)
                            ?: return@runWriteCommandAction null
                    } else {
                        // 父目录存在，直接创建文件
                        val fileName = path.substring(path.lastIndexOf('/') + 1)
                        createdVirtualFile = parentVirtualFile.createChildData(this, fileName)
                            ?: return@runWriteCommandAction null
                    }
                    
                    // 获取新文件的文档
                    val doc = FileDocumentManager.getInstance().getDocument(createdVirtualFile)
                        ?: return@runWriteCommandAction null
                    
                    // 直接使用newText作为文件内容（因为文件是新创建的）
                    doc.setText(newText)
                    FileDocumentManager.getInstance().saveDocument(doc)
                    
                    doc
                } as? com.intellij.openapi.editor.Document
                
                if (createdDocument == null) {
                    return ToolResult.Error("Could not create or initialize file: $path")
                }
                
                virtualFile = createdVirtualFile
                document = createdDocument
                newContent = newText
            }
            
            println("EditFileTool - Virtual file: ${virtualFile?.path}")
            
            val lineChange = newLineCount - oldLineCount
            val lineChangeText = when {
                lineChange > 0 -> "+$lineChange "
                lineChange < 0 -> "-$lineChange "
                else -> "0 "
            }
            
            ToolResult.Success(
                mapOf(
                    "path" to path,
                    "message" to "File edited successfully ($lineChangeText)",
                    "changes" to mapOf(
                        "old_text_length" to oldText.length,
                        "new_text_length" to newText.length,
                        "old_line_count" to oldLineCount,
                        "new_line_count" to newLineCount,
                        "line_change" to lineChange
                    )
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("Error editing file: ${e.message}")
        }
    }
}