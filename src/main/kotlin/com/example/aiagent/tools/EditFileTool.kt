package com.example.aiagent.tools

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

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
            val resolvedPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Project base path not found")

            println("EditFileTool - Resolved path: $resolvedPath")

            var virtualFile = findVirtualFile(resolvedPath)
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

                oldLineCount = oldText.lines().size
                newLineCount = newText.lines().size

                newContent = fileContent.replace(oldText, newText)

                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(newContent)
                    FileDocumentManager.getInstance().saveDocument(document)
                }
            } else {
                // 文件不存在，创建新文件
                println("EditFileTool - File not found, creating new file: $path")

                oldLineCount = 0
                newLineCount = newText.lines().size

                val parentPath = resolvedPath.parent
                    ?: return ToolResult.Error("Cannot determine parent directory for: $path")
                val fileName = resolvedPath.fileName?.toString()
                    ?: return ToolResult.Error("Cannot determine file name for: $path")

                var createdVirtualFile: VirtualFile? = null
                val createdDocument = WriteCommandAction.runWriteCommandAction<Any>(project) {
                    // 确保父目录链存在：从项目根逐层创建
                    val basePath = project.basePath ?: return@runWriteCommandAction null
                    val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                        ?: return@runWriteCommandAction null

                    val normalizedRelPath = normalizePath(path)
                    val parts = normalizedRelPath.split("/")
                    val dirParts = parts.dropLast(1)  // 目录部分
                    val fileNamePart = parts.last()    // 文件名

                    var currentDir: VirtualFile = baseDir
                    for (dirName in dirParts) {
                        if (dirName.isEmpty()) continue
                        val child = currentDir.findChild(dirName)
                        currentDir = if (child != null && child.isDirectory) {
                            child
                        } else {
                            currentDir.createChildDirectory(this, dirName)
                        }
                    }

                    // 在最终目录中创建文件
                    createdVirtualFile = currentDir.createChildData(this, fileNamePart)

                    val doc = FileDocumentManager.getInstance().getDocument(createdVirtualFile!!)
                        ?: return@runWriteCommandAction null

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
                lineChange < 0 -> "$lineChange "
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