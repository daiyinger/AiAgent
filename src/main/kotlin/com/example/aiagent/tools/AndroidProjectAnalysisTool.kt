package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

class AndroidProjectAnalysisTool : Tool(
    name = "analyze_android_project",
    description = "分析Android项目的结构、依赖和关键文件",
    parameters = listOf(
        ToolParameter(
            name = "path",
            type = "string",
            description = "The relative path to the Android project from project root. Default is root directory",
            required = false
        )
    )
) {
    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val path = params["path"] as? String ?: ""
        
        return try {
            val projectBasePath = project.basePath ?: return ToolResult.Error("Project base path not found")
            val targetPath = resolveFilePath(project, path)
                ?: return ToolResult.Error("Project base path not found")
            
            if (!Files.exists(targetPath)) {
                return ToolResult.Error("Path does not exist: $path")
            }
            
            val analysisResult = mutableMapOf<String, Any>()
            
            // 1. 分析项目结构
            val projectStructure = analyzeProjectStructure(targetPath)
            analysisResult["project_structure"] = projectStructure
            
            // 2. 分析build.gradle文件
            val buildFiles = findBuildFiles(targetPath)
            analysisResult["build_files"] = buildFiles
            
            // 3. 分析关键源代码文件
            val sourceFiles = findSourceFiles(targetPath)
            analysisResult["source_files"] = sourceFiles
            
            // 4. 分析AndroidManifest.xml
            val manifestFiles = findManifestFiles(targetPath)
            analysisResult["manifest_files"] = manifestFiles
            
            ToolResult.Success(
                mapOf(
                    "path" to if (path.isEmpty()) "/" else path,
                    "analysis" to analysisResult,
                    "summary" to generateSummary(analysisResult)
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("Error analyzing Android project: ${e.message}")
        }
    }
    
    private fun analyzeProjectStructure(rootPath: Path): Map<String, Any> {
        val structure = mutableMapOf<String, Any>()
        val directories = mutableListOf<String>()
        val files = mutableListOf<String>()
        var directoryCount = 0
        var fileCount = 0
        
        // 需要排除的目录
        val excludedDirs = setOf(".git", ".idea", "build", ".gradle", "node_modules", "target", "out", "bin", "obj")
        
        Files.walk(rootPath).use { stream ->
            stream.filter { path ->
                // 过滤掉排除的目录及其子目录
                val relativePath = path.toString().substringAfter(rootPath.toString()).replace("\\", "/")
                excludedDirs.none { excluded ->
                    relativePath.contains("/$excluded/") || relativePath.endsWith("/$excluded")
                }
            }.forEach { path ->
                val relativePath = path.toString().substringAfter(rootPath.toString()).replace("\\", "/").removePrefix("/")
                if (Files.isDirectory(path)) {
                    if (directoryCount < 50) { // 限制目录数量
                        directories.add(relativePath)
                    }
                    directoryCount++
                } else {
                    if (fileCount < 100) { // 限制文件数量
                        files.add(relativePath)
                    }
                    fileCount++
                }
            }
        }
        
        structure["directories"] = directories
        structure["files"] = files
        structure["directory_count"] = directoryCount
        structure["file_count"] = fileCount
        structure["truncated"] = directoryCount > 50 || fileCount > 100
        
        // 识别Android项目类型
        val isAndroidProject = Files.exists(rootPath.resolve("build.gradle")) || 
                              Files.exists(rootPath.resolve("app")) ||
                              Files.exists(rootPath.resolve("AndroidManifest.xml"))
        structure["is_android_project"] = isAndroidProject
        
        return structure
    }
    
    private fun findBuildFiles(rootPath: Path): List<Map<String, Any>> {
        val buildFiles = mutableListOf<Map<String, Any>>()
        
        // 需要排除的目录
        val excludedDirs = setOf(".git", ".idea", "build", ".gradle", "node_modules", "target", "out", "bin", "obj")
        
        Files.walk(rootPath).use { stream ->
            stream.filter { path ->
                // 过滤掉排除的目录及其子目录
                val relativePath = path.toString().substringAfter(rootPath.toString()).replace("\\", "/")
                excludedDirs.none { excluded ->
                    relativePath.contains("/$excluded/") || relativePath.endsWith("/$excluded")
                }
            }.forEach { path ->
                val fileName = path.fileName.toString()
                if (fileName == "build.gradle" || fileName == "build.gradle.kts") {
                    val relativePath = path.toString().substringAfter(rootPath.toString()).replace("\\", "/").removePrefix("/")
                    
                    buildFiles.add(
                        mapOf(
                            "path" to relativePath,
                            "size" to Files.size(path)
                        )
                    )
                }
            }
        }
        
        return buildFiles.take(10) // 限制返回的文件数量
    }
    
    private fun findSourceFiles(rootPath: Path): List<Map<String, Any>> {
        val sourceFiles = mutableListOf<Map<String, Any>>()
        
        // 需要排除的目录
        val excludedDirs = setOf(".git", ".idea", "build", ".gradle", "node_modules", "target", "out", "bin", "obj")
        
        Files.walk(rootPath).use { stream ->
            stream.filter { path ->
                // 过滤掉排除的目录及其子目录
                val relativePath = path.toString().substringAfter(rootPath.toString()).replace("\\", "/")
                excludedDirs.none { excluded ->
                    relativePath.contains("/$excluded/") || relativePath.endsWith("/$excluded")
                }
            }.forEach { path ->
                val fileName = path.fileName.toString()
                if (fileName.endsWith(".java") || fileName.endsWith(".kt")) {
                    val relativePath = path.toString().substringAfter(rootPath.toString()).replace("\\", "/").removePrefix("/")
                    sourceFiles.add(
                        mapOf(
                            "path" to relativePath,
                            "size" to Files.size(path)
                        )
                    )
                }
            }
        }
        
        return sourceFiles.take(30) // 限制返回的文件数量
    }
    
    private fun findManifestFiles(rootPath: Path): List<Map<String, Any>> {
        val manifestFiles = mutableListOf<Map<String, Any>>()
        
        // 需要排除的目录
        val excludedDirs = setOf(".git", ".idea", "build", ".gradle", "node_modules", "target", "out", "bin", "obj")
        
        Files.walk(rootPath).use { stream ->
            stream.filter { path ->
                // 过滤掉排除的目录及其子目录
                val relativePath = path.toString().substringAfter(rootPath.toString()).replace("\\", "/")
                excludedDirs.none { excluded ->
                    relativePath.contains("/$excluded/") || relativePath.endsWith("/$excluded")
                }
            }.forEach { path ->
                val fileName = path.fileName.toString()
                if (fileName == "AndroidManifest.xml") {
                    val relativePath = path.toString().substringAfter(rootPath.toString()).replace("\\", "/").removePrefix("/")
                    
                    manifestFiles.add(
                        mapOf(
                            "path" to relativePath,
                            "size" to Files.size(path)
                        )
                    )
                }
            }
        }
        
        return manifestFiles.take(5) // 限制返回的文件数量
    }
    
    private fun generateSummary(analysis: Map<String, Any>): String {
        val projectStructure = analysis["project_structure"] as? Map<String, Any> ?: emptyMap()
        val buildFiles = analysis["build_files"] as? List<Map<String, Any>> ?: emptyList<Map<String, Any>>()
        val sourceFiles = analysis["source_files"] as? List<Map<String, Any>> ?: emptyList<Map<String, Any>>()
        val manifestFiles = analysis["manifest_files"] as? List<Map<String, Any>> ?: emptyList<Map<String, Any>>()
        
        val isAndroidProject = projectStructure["is_android_project"] as? Boolean ?: false
        val directoryCount = projectStructure["directory_count"] as? Int ?: 0
        val fileCount = projectStructure["file_count"] as? Int ?: 0
        
        val summary = StringBuilder()
        summary.appendLine("Android项目分析报告")
        summary.appendLine("==================")
        summary.appendLine("项目类型: ${if (isAndroidProject) "Android项目" else "非Android项目"}")
        summary.appendLine("目录数量: $directoryCount")
        summary.appendLine("文件数量: $fileCount")
        summary.appendLine("Build文件数量: ${buildFiles.size}")
        summary.appendLine("源代码文件数量: ${sourceFiles.size}")
        summary.appendLine("Manifest文件数量: ${manifestFiles.size}")
        
        if (buildFiles.isNotEmpty()) {
            summary.appendLine("\nBuild文件:")
            buildFiles.take(5).forEach { file ->
                val fileMap = file as? Map<String, Any> ?: emptyMap()
                summary.appendLine("- ${fileMap["path"]}")
            }
        }
        
        if (manifestFiles.isNotEmpty()) {
            summary.appendLine("\nManifest文件:")
            manifestFiles.forEach { file ->
                val fileMap = file as? Map<String, Any> ?: emptyMap()
                summary.appendLine("- ${fileMap["path"]}")
            }
        }
        
        return summary.toString()
    }
}