package com.example.aiagent.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * 智能缓存实现
 * 支持 LRU 淘汰、TTL 过期、容量限制
 */

/**
 * 缓存条目
 */
data class CacheEntry<V>(
    val value: V,
    val timestamp: Long = System.currentTimeMillis(),
    var accessCount: Long = 0
) {
    val age: Long get() = System.currentTimeMillis() - timestamp
}

/**
 * 缓存配置
 */
data class CacheConfig(
    val maxSize: Int = 100,
    val ttlMinutes: Long = 30,
    val cleanupIntervalMinutes: Long = 5
)

/**
 * 智能缓存接口
 */
interface SmartCache<K, V> {
    fun get(key: K): V?
    fun put(key: K, value: V)
    fun remove(key: K): V?
    fun clear()
    fun size(): Int
    fun containsKey(key: K): Boolean
    fun getOrPut(key: K, defaultValue: () -> V): V
}

/**
 * LRU 缓存实现
 * 当缓存满时，淘汰最久未访问的条目
 */
class LRUCache<K, V>(
    private val config: CacheConfig = CacheConfig()
) : SmartCache<K, V> {
    
    private val cache = object : LinkedHashMap<K, CacheEntry<V>>(config.maxSize, 0.75f, true) {
        // true 表示按访问顺序排序（LRU）
    }
    
    override fun get(key: K): V? {
        val entry = cache[key] ?: return null
        
        // 检查是否过期
        if (isExpired(entry)) {
            cache.remove(key)
            return null
        }
        
        entry.accessCount++
        return entry.value
    }
    
    override fun put(key: K, value: V) {
        // 清理过期条目
        cleanupExpired()
        
        // 如果达到容量限制，移除最旧的条目
        while (cache.size >= config.maxSize) {
            val oldestKey = cache.keys.firstOrNull() ?: break
            cache.remove(oldestKey)
        }
        
        cache[key] = CacheEntry(value)
    }
    
    override fun remove(key: K): V? {
        return cache.remove(key)?.value
    }
    
    override fun clear() {
        cache.clear()
    }
    
    override fun size(): Int = cache.size
    
    override fun containsKey(key: K): Boolean {
        val entry = cache[key] ?: return false
        if (isExpired(entry)) {
            cache.remove(key)
            return false
        }
        return true
    }
    
    override fun getOrPut(key: K, defaultValue: () -> V): V {
        return get(key) ?: run {
            val value = defaultValue()
            put(key, value)
            value
        }
    }
    
    private fun isExpired(entry: CacheEntry<V>): Boolean {
        return entry.age > config.ttlMinutes * 60 * 1000
    }
    
    private fun cleanupExpired() {
        cache.entries.removeAll { isExpired(it.value) }
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getStats(): CacheStats {
        val entries = cache.values
        return CacheStats(
            size = cache.size,
            maxSize = config.maxSize,
            averageAge = if (entries.isNotEmpty()) entries.sumOf { it.age } / entries.size else 0,
            totalAccesses = entries.sumOf { it.accessCount }
        )
    }
}

/**
 * 缓存统计信息
 */
data class CacheStats(
    val size: Int,
    val maxSize: Int,
    val averageAge: Long,
    val totalAccesses: Long
) {
    val usageRate: Double get() = size.toDouble() / maxSize * 100
}

/**
 * TTL 缓存实现
 * 纯粹基于过期时间，不考虑容量
 */
class TTLCache<K, V>(
    private val ttlMinutes: Long = 30
) : SmartCache<K, V> {
    
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    
    override fun get(key: K): V? {
        val entry = cache[key] ?: return null
        
        if (isExpired(entry)) {
            cache.remove(key)
            return null
        }
        
        entry.accessCount++
        return entry.value
    }
    
    override fun put(key: K, value: V) {
        cache[key] = CacheEntry(value)
    }
    
    override fun remove(key: K): V? {
        return cache.remove(key)?.value
    }
    
    override fun clear() {
        cache.clear()
    }
    
    override fun size(): Int {
        cleanupExpired()
        return cache.size
    }
    
    override fun containsKey(key: K): Boolean {
        val entry = cache[key] ?: return false
        if (isExpired(entry)) {
            cache.remove(key)
            return false
        }
        return true
    }
    
    override fun getOrPut(key: K, defaultValue: () -> V): V {
        return get(key) ?: run {
            val value = defaultValue()
            put(key, value)
            value
        }
    }
    
    private fun isExpired(entry: CacheEntry<V>): Boolean {
        return entry.age > ttlMinutes * 60 * 1000
    }
    
    private fun cleanupExpired() {
        cache.entries.removeAll { isExpired(it.value) }
    }
}

/**
 * 消息响应缓存
 * 专门用于缓存 AI 响应
 */
class MessageResponseCache(
    private val maxSize: Int = 20,
    private val ttlMinutes: Long = 30
) {
    private val cache = LRUCache<String, String>(
        CacheConfig(maxSize = maxSize, ttlMinutes = ttlMinutes)
    )
    
    /**
     * 生成缓存键
     */
    private fun generateKey(message: String): String {
        // 规范化消息：去除空白，转小写，限制长度
        val normalized = message.trim().lowercase().take(100)
        return normalized.hashCode().toString()
    }
    
    fun get(message: String): String? {
        val key = generateKey(message)
        return cache.get(key)
    }
    
    fun put(message: String, response: String) {
        // 不缓存过长的消息或响应
        if (message.length > 100 || response.length > 500) return
        
        val key = generateKey(message)
        cache.put(key, response)
    }
    
    fun clear() {
        cache.clear()
    }
    
    fun getStats(): CacheStats = cache.getStats()
}

/**
 * Diff 计算缓存
 */
class DiffCalculationCache(
    private val maxSize: Int = 50
) {
    private val cache = LRUCache<String, List<Any>>(
        CacheConfig(maxSize = maxSize, ttlMinutes = 60)
    )
    
    fun get(oldText: String, newText: String, contextLines: Int): List<Any>? {
        val key = generateKey(oldText, newText, contextLines)
        return cache.get(key)
    }
    
    fun put(oldText: String, newText: String, contextLines: Int, diff: List<Any>) {
        // 不缓存过大的 diff
        if (diff.size > 1000) return
        
        val key = generateKey(oldText, newText, contextLines)
        cache.put(key, diff)
    }
    
    private fun generateKey(oldText: String, newText: String, contextLines: Int): String {
        return "${oldText.hashCode()}_${newText.hashCode()}_$contextLines"
    }
    
    fun clear() {
        cache.clear()
    }
}

/**
 * 文件内容缓存
 */
class FileContentCache(
    private val maxSize: Int = 30,
    private val ttlMinutes: Long = 10
) {
    private val cache = LRUCache<String, CachedFileContent>(
        CacheConfig(maxSize = maxSize, ttlMinutes = ttlMinutes)
    )
    
    data class CachedFileContent(
        val content: String,
        val checksum: String,
        val lineCount: Int
    )
    
    fun get(filePath: String): CachedFileContent? {
        return cache.get(filePath)
    }
    
    fun put(filePath: String, content: String, checksum: String, lineCount: Int) {
        cache.put(filePath, CachedFileContent(content, checksum, lineCount))
    }
    
    fun invalidate(filePath: String) {
        cache.remove(filePath)
    }
    
    fun clear() {
        cache.clear()
    }
}