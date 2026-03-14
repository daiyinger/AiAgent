package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SearchFilesTool : Tool(
    name = "search_files",
    description = "Search for files in the project by name or pattern",
    parameters = listOf(
        ToolParameter(
            name = "pattern",
            type = "string",
            description = "The file name or pattern to search for (supports wildcards like *.kt)",
            required = true
        ),
        ToolParameter(
            name = "max_results",
            type = "integer",
            description = "Maximum number of results to return. Default is 20",
            required = false
        )
    )
) {
    override suspend fun execute(project: Project, params: Map<String, Any>, onOutput: ((String) -> Unit)?): ToolResult {
        val pattern = params["pattern"] as? String ?: return ToolResult.Error("Missing required parameter: pattern")
        val maxResults = (params["max_results"] as? Number)?.toInt() ?: 20
        
        return try {
            val projectBasePath = project.basePath ?: return ToolResult.Error("Project base path not found")
            
            val files = mutableListOf<Map<String, Any>>()
            
            val virtualFiles = FilenameIndex.getAllFilesByExt(
                project,
                "",
                GlobalSearchScope.projectScope(project)
            ).take(maxResults * 10)
            
            val regex = if (pattern.contains("*") || pattern.contains("?")) {
                pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".")
            } else {
                ".*$pattern.*"
            }
            
            val patternRegex = Regex(regex, RegexOption.IGNORE_CASE)
            
            for (virtualFile in virtualFiles) {
                if (files.size >= maxResults) break
                
                val fileName = virtualFile.name
                if (patternRegex.matches(fileName)) {
                    val relativePath = virtualFile.path.substringAfter(projectBasePath).replace("\\", "/").removePrefix("/")
                    files.add(
                        mapOf(
                            "path" to relativePath,
                            "name" to fileName,
                            "size" to virtualFile.length,
                            "is_directory" to virtualFile.isDirectory
                        )
                    )
                }
            }
            
            ToolResult.Success(
                mapOf(
                    "pattern" to pattern,
                    "count" to files.size,
                    "files" to files
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("Error searching files: ${e.message}")
        }
    }
}