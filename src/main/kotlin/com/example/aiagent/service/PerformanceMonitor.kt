package com.example.aiagent.service

import java.util.concurrent.ConcurrentHashMap

/**
 * 性能监控器
 * 收集和分析性能指标
 */
object PerformanceMonitor {
    
    private val metrics = ConcurrentHashMap<String, PerformanceMetric>()
    
    /**
     * 性能指标数据类
     */
    data class PerformanceMetric(
        var count: Long = 0,
        var totalTimeMs: Long = 0,
        var minTimeMs: Long = Long.MAX_VALUE,
        var maxTimeMs: Long = 0,
        var errorCount: Long = 0
    ) {
        val avgTimeMs: Double 
            get() = if (count > 0) totalTimeMs.toDouble() / count else 0.0
        
        val errorRate: Double
            get() = if (count > 0) errorCount.toDouble() / count * 100 else 0.0
    }
    
    /**
     * 测量代码块执行时间
     */
    fun <T> measure(operation: String, block: () -> T): T {
        val startTime = System.currentTimeMillis()
        var success = true
        try {
            return block()
        } catch (e: Exception) {
            success = false
            throw e
        } finally {
            val duration = System.currentTimeMillis() - startTime
            recordMetric(operation, duration, success)
        }
    }
    
    /**
     * 测量挂起函数执行时间
     */
    suspend fun <T> measureSuspend(operation: String, block: suspend () -> T): T {
        val startTime = System.currentTimeMillis()
        var success = true
        try {
            return block()
        } catch (e: Exception) {
            success = false
            throw e
        } finally {
            val duration = System.currentTimeMillis() - startTime
            recordMetric(operation, duration, success)
        }
    }
    
    /**
     * 记录性能指标
     */
    private fun recordMetric(operation: String, durationMs: Long, success: Boolean) {
        val metric = metrics.getOrPut(operation) { PerformanceMetric() }
        synchronized(metric) {
            metric.count++
            metric.totalTimeMs += durationMs
            metric.minTimeMs = minOf(metric.minTimeMs, durationMs)
            metric.maxTimeMs = maxOf(metric.maxTimeMs, durationMs)
            if (!success) {
                metric.errorCount++
            }
        }
    }
    
    /**
     * 获取指定操作的性能指标
     */
    fun getMetric(operation: String): PerformanceMetric? {
        return metrics[operation]
    }
    
    /**
     * 获取所有性能指标
     */
    fun getAllMetrics(): Map<String, PerformanceMetric> {
        return metrics.toMap()
    }
    
    /**
     * 获取性能报告
     */
    fun getReport(): String {
        return buildString {
            appendLine("=== Performance Report ===")
            appendLine("Generated at: ${java.time.LocalDateTime.now()}")
            appendLine()
            
            if (metrics.isEmpty()) {
                appendLine("No metrics recorded yet.")
                return@buildString
            }
            
            // 按平均耗时排序
            val sortedMetrics = metrics.entries.sortedByDescending { it.value.avgTimeMs }
            
            sortedMetrics.forEach { (operation, metric) ->
                appendLine("Operation: $operation")
                appendLine("  Count: ${metric.count}")
                appendLine("  Avg: ${String.format("%.2f", metric.avgTimeMs)}ms")
                appendLine("  Min: ${metric.minTimeMs}ms")
                appendLine("  Max: ${metric.maxTimeMs}ms")
                appendLine("  Total: ${metric.totalTimeMs}ms")
                if (metric.errorCount > 0) {
                    appendLine("  Errors: ${metric.errorCount} (${String.format("%.1f", metric.errorRate)}%)")
                }
                appendLine()
            }
            
            // 总体统计
            val totalCount = metrics.values.sumOf { it.count }
            val totalTime = metrics.values.sumOf { it.totalTimeMs }
            val totalErrors = metrics.values.sumOf { it.errorCount }
            
            appendLine("=== Summary ===")
            appendLine("Total operations: $totalCount")
            appendLine("Total time: ${totalTime}ms")
            if (totalErrors > 0) {
                appendLine("Total errors: $totalErrors (${String.format("%.1f", totalErrors.toDouble() / totalCount * 100)}%)")
            }
        }
    }
    
    /**
     * 重置所有指标
     */
    fun reset() {
        metrics.clear()
    }
    
    /**
     * 重置指定操作的指标
     */
    fun reset(operation: String) {
        metrics.remove(operation)
    }
}