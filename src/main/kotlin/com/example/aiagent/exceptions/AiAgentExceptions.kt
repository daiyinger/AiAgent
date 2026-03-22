package com.example.aiagent.exceptions

/**
 * AI Agent 插件异常体系
 * 提供详细的错误分类和上下文信息
 */

/**
 * AI Agent 基础异常
 */
sealed class AiAgentException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    
    /**
     * 网络相关异常
     */
    class NetworkException(
        message: String,
        cause: Throwable? = null
    ) : AiAgentException(message, cause)
    
    /**
     * 工具执行异常
     */
    class ToolExecutionException(
        val toolName: String,
        message: String,
        cause: Throwable? = null
    ) : AiAgentException("Tool '$toolName' failed: $message", cause)
    
    /**
     * 文件操作异常
     */
    class FileOperationException(
        message: String,
        val filePath: String? = null,
        cause: Throwable? = null
    ) : AiAgentException(message, cause)
    
    /**
     * 参数验证异常
     */
    class ValidationException(
        message: String,
        val parameterName: String? = null
    ) : AiAgentException(message)
    
    /**
     * API 调用异常
     */
    class ApiException(
        message: String,
        val statusCode: Int? = null,
        val responseBody: String? = null,
        cause: Throwable? = null
    ) : AiAgentException(message, cause)
    
    /**
     * 配置异常
     */
    class ConfigurationException(
        message: String,
        val configKey: String? = null
    ) : AiAgentException(message)
    
    /**
     * 取消操作异常
     */
    class CancellationException(
        message: String = "Operation cancelled"
    ) : AiAgentException(message)
    
    /**
     * 超时异常
     */
    class TimeoutException(
        message: String,
        val timeoutMs: Long? = null
    ) : AiAgentException(message)
    
    /**
     * 权限异常
     */
    class PermissionException(
        message: String,
        val resource: String? = null
    ) : AiAgentException(message)
}

/**
 * 异常工具类
 */
object ExceptionUtils {
    
    /**
     * 判断异常是否可重试
     */
    fun isRetryable(throwable: Throwable): Boolean {
        return when (throwable) {
            is AiAgentException.NetworkException -> true
            is AiAgentException.ApiException -> {
                throwable.statusCode in listOf(429, 500, 502, 503, 504)
            }
            is AiAgentException.TimeoutException -> true
            else -> {
                val message = throwable.message?.lowercase() ?: ""
                message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("429") ||
                message.contains("500") ||
                message.contains("502") ||
                message.contains("503")
            }
        }
    }
    
    /**
     * 判断异常是否为用户取消
     */
    fun isCancellation(throwable: Throwable): Boolean {
        return throwable is AiAgentException.CancellationException ||
               throwable.message?.contains("cancelled", ignoreCase = true) == true ||
               throwable.message?.contains("取消", ignoreCase = true) == true
    }
    
    /**
     * 获取用户友好的错误消息
     */
    fun getUserFriendlyMessage(throwable: Throwable): String {
        return when (throwable) {
            is AiAgentException.NetworkException -> {
                "网络连接失败，请检查网络设置后重试"
            }
            is AiAgentException.ApiException -> {
                when (throwable.statusCode) {
                    401 -> "API Key 无效或已过期，请检查配置"
                    403 -> "没有权限访问该资源"
                    429 -> "请求过于频繁，请稍后再试"
                    500, 502, 503, 504 -> "服务器暂时不可用，请稍后重试"
                    else -> "API 调用失败: ${throwable.message}"
                }
            }
            is AiAgentException.ToolExecutionException -> {
                "工具 '${throwable.toolName}' 执行失败: ${throwable.message}"
            }
            is AiAgentException.FileOperationException -> {
                "文件操作失败: ${throwable.message}"
            }
            is AiAgentException.ValidationException -> {
                "参数错误: ${throwable.message}"
            }
            is AiAgentException.ConfigurationException -> {
                "配置错误: ${throwable.message}"
            }
            is AiAgentException.TimeoutException -> {
                "操作超时，请重试"
            }
            is AiAgentException.PermissionException -> {
                "权限不足: ${throwable.message}"
            }
            is AiAgentException.CancellationException -> {
                "操作已取消"
            }
            else -> {
                throwable.message ?: "未知错误"
            }
        }
    }
    
    /**
     * 格式化异常信息用于日志
     */
    fun formatForLog(throwable: Throwable): String {
        return buildString {
            appendLine("Exception: ${throwable.javaClass.simpleName}")
            appendLine("Message: ${throwable.message}")
            if (throwable is AiAgentException) {
                when (throwable) {
                    is AiAgentException.ToolExecutionException -> {
                        appendLine("Tool: ${throwable.toolName}")
                    }
                    is AiAgentException.ApiException -> {
                        appendLine("Status Code: ${throwable.statusCode}")
                        appendLine("Response: ${throwable.responseBody?.take(200)}")
                    }
                    is AiAgentException.FileOperationException -> {
                        appendLine("File: ${throwable.filePath}")
                    }
                    is AiAgentException.ValidationException -> {
                        appendLine("Parameter: ${throwable.parameterName}")
                    }
                    is AiAgentException.TimeoutException -> {
                        appendLine("Timeout: ${throwable.timeoutMs}ms")
                    }
                    is AiAgentException.PermissionException -> {
                        appendLine("Resource: ${throwable.resource}")
                    }
                    else -> { /* 其他类型不额外输出 */ }
                }
            }
            appendLine("Stack: ${throwable.stackTrace.take(5).joinToString("\n")}")
        }
    }
}

/**
 * 异常处理扩展函数
 */
fun <T> Result<T>.onNetworkError(action: (AiAgentException.NetworkException) -> Unit): Result<T> {
    return onFailure { throwable ->
        if (throwable is AiAgentException.NetworkException) {
            action(throwable)
        }
    }
}

fun <T> Result<T>.onToolError(action: (AiAgentException.ToolExecutionException) -> Unit): Result<T> {
    return onFailure { throwable ->
        if (throwable is AiAgentException.ToolExecutionException) {
            action(throwable)
        }
    }
}

fun <T> Result<T>.onFileError(action: (AiAgentException.FileOperationException) -> Unit): Result<T> {
    return onFailure { throwable ->
        if (throwable is AiAgentException.FileOperationException) {
            action(throwable)
        }
    }
}