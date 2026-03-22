package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

abstract class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
) {
    abstract suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): ToolResult

    /**
     * 将模型传入的路径归一化：
     * - 去除前导 / \ ./ .\
     * - 统一分隔符
     * - 去除两端空白
     */
    protected fun normalizePath(path: String): String {
        var p = path.trim()
        // 统一分隔符为系统分隔符
        p = p.replace("\\", "/")
        // 去除前导 ./ 或 /
        while (p.startsWith("./") || p.startsWith("/")) {
            p = p.removePrefix("./").removePrefix("/")
        }
        return p
    }

    /**
     * 将相对路径解析为绝对 Path 对象
     * 自动处理 "root"、空字符串、相对路径、绝对路径 等各种情况
     */
    protected fun resolveFilePath(project: Project, rawPath: String): Path? {
        val basePath = project.basePath ?: return null
        val normalized = normalizePath(rawPath)

        if (normalized.isEmpty() || normalized == "root") {
            return Paths.get(basePath)
        }

        // 如果模型传了绝对路径，先检查它是否在项目内
        val asPath = Paths.get(normalized)
        if (asPath.isAbsolute) {
            return asPath.normalize()
        }

        return Paths.get(basePath, normalized).normalize()
    }

    /**
     * 通过 LocalFileSystem 查找 VirtualFile（跨平台安全）
     * 优先使用 LocalFileSystem 而非手拼 file:// URL
     */
    protected fun findVirtualFile(absolutePath: Path): VirtualFile? {
        val file = absolutePath.toFile()
        return LocalFileSystem.getInstance().findFileByIoFile(file)
            ?: run {
                // 刷新后重试一次
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            }
    }

    /**
     * 便捷方法：直接从 project + 相对路径 获取 VirtualFile
     */
    protected fun findVirtualFile(project: Project, rawPath: String): VirtualFile? {
        val resolved = resolveFilePath(project, rawPath) ?: return null
        return findVirtualFile(resolved)
    }

    /**
     * 计算内容的校验和（SHA-256）
     * 用于检测文件在读取和编辑之间是否被修改
     */
    protected fun computeContentChecksum(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 验证文件校验和
     * @return true 如果校验和匹配或未提供预期校验和
     */
    protected fun verifyChecksum(currentContent: String, expectedChecksum: String?): Boolean {
        if (expectedChecksum == null) return true
        val currentChecksum = computeContentChecksum(currentContent)
        return currentChecksum == expectedChecksum
    }
}

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

sealed class ToolResult {
    data class Success(val data: Any) : ToolResult()
    data class Error(val message: String) : ToolResult()
    data class Progress(val message: String) : ToolResult()
    data class OutputUpdate(val output: String) : ToolResult()
}