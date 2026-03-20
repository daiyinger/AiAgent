package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * 联网搜索工具 - 支持多个搜索引擎自动切换
 */
class WebSearchTool : Tool(
    name = "web_search",
    description = "Searches internet for information using multiple search engines (Baidu, Google, Bing, Sogou, 360). Automatically switches to another engine if one fails.",
    parameters = listOf(
        ToolParameter(
            name = "query",
            type = "string",
            description = "The search query to look up on the internet",
            required = true
        ),
        ToolParameter(
            name = "max_results",
            type = "integer",
            description = "Maximum number of search results to return (1-10). Default is 5",
            required = false
        )
    )
) {
    // 搜索引擎列表，按优先级排序
    private val searchEngines = listOf(
        SearchEngine("百度", ::searchBaidu),
        SearchEngine("Google", ::searchGoogle),
        SearchEngine("Bing", ::searchBing),
        SearchEngine("搜狗", ::searchSogou),
        SearchEngine("360搜索", ::search360)
    )

    override suspend fun execute(
        project: Project,
        params: Map<String, Any>,
        onOutput: ((String) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): ToolResult {
        val query = params["query"] as? String ?: return ToolResult.Error("缺少必要参数: query")
        val maxResults = ((params["max_results"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)

        onOutput?.invoke("🔍 正在搜索: \"$query\"...")

        // 依次尝试各个搜索引擎
        for ((index, engine) in searchEngines.withIndex()) {
            try {
                onOutput?.invoke("📡 尝试使用 ${engine.name} 搜索...")
                val results = engine.searchFunc(query, maxResults)

                if (results.isNotEmpty()) {
                    onOutput?.invoke("✅ 使用 ${engine.name} 找到 ${results.size} 条结果")
                    return ToolResult.Success(
                        mapOf(
                            "query" to query,
                            "engine" to engine.name,
                            "count" to results.size,
                            "results" to results
                        )
                    )
                }

                // 当前引擎无结果，尝试下一个
                if (index < searchEngines.size - 1) {
                    onOutput?.invoke("⚠️ ${engine.name} 未找到结果，尝试其他引擎...")
                }
            } catch (e: UnknownHostException) {
                onOutput?.invoke("❌ ${engine.name} 无法访问 (${e.message})，尝试其他引擎...")
            } catch (e: SocketTimeoutException) {
                onOutput?.invoke("⏱️ ${engine.name} 连接超时，尝试其他引擎...")
            } catch (e: Exception) {
                onOutput?.invoke("❌ ${engine.name} 搜索失败: ${e.message}，尝试其他引擎...")
            }
        }

        // 所有引擎都失败
        return ToolResult.Success(
            mapOf(
                "query" to query,
                "count" to 0,
                "results" to emptyList<Map<String, String>>(),
                "message" to "所有搜索引擎均无法访问或未找到结果"
            )
        )
    }

    /**
     * 搜索引擎数据类
     */
    private data class SearchEngine(
        val name: String,
        val searchFunc: (String, Int) -> List<Map<String, String>>
    )

    /**
     * 使用百度搜索
     */
    private fun searchBaidu(query: String, maxResults: Int): List<Map<String, String>> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.baidu.com/s?wd=$encodedQuery&rn=$maxResults")

        val connection = createConnection(url)

        val results = mutableListOf<Map<String, String>>()

        try {
            connection.inputStream.bufferedReader().use { reader ->
                val html = reader.readText()

                // 百度搜索结果解析
                val resultPattern = Regex(
                    "<div class=\"result c-container \"[^>]*>.*?<h3[^>]*>.*?<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>.*?</h3>.*?<div class=\"c-abstract\"[^>]*>(.*?)</div>.*?</div>",
                    RegexOption.DOT_MATCHES_ALL
                )

                val matches = resultPattern.findAll(html)

                for (match in matches.take(maxResults)) {
                    var url = match.groupValues[1].trim()
                    val title = cleanHtml(match.groupValues[2])
                    val snippet = cleanHtml(match.groupValues[3])

                    // 百度链接需要处理跳转
                    if (url.startsWith("http://www.baidu.com/link?url=")) {
                        url = url.substringAfter("url=").substringBefore("&")
                    }

                    if (title.isNotBlank() && url.isNotBlank()) {
                        results.add(
                            mapOf(
                                "title" to title,
                                "url" to url,
                                "snippet" to snippet
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return results
    }

    /**
     * 使用 Google 搜索
     */
    private fun searchGoogle(query: String, maxResults: Int): List<Map<String, String>> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.google.com/search?q=$encodedQuery&num=$maxResults")

        val connection = createConnection(url)

        val results = mutableListOf<Map<String, String>>()

        try {
            connection.inputStream.bufferedReader().use { reader ->
                val html = reader.readText()

                // Google 搜索结果解析
                val resultPattern = Regex(
                    "<div[^>]*class=\"g\"[^>]*>.*?<h3[^>]*>.*?<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>.*?</h3>.*?<div[^>]*class=\"VwiC3b\"[^>]*>(.*?)</div>.*?</div>",
                    RegexOption.DOT_MATCHES_ALL
                )

                val matches = resultPattern.findAll(html)

                for (match in matches.take(maxResults)) {
                    val url = match.groupValues[1].trim()
                    val title = cleanHtml(match.groupValues[2])
                    val snippet = cleanHtml(match.groupValues[3])

                    if (title.isNotBlank() && url.isNotBlank() && !url.contains("google.com")) {
                        results.add(
                            mapOf(
                                "title" to title,
                                "url" to url,
                                "snippet" to snippet
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return results
    }

    /**
     * 使用 Bing 中国版搜索
     */
    private fun searchBing(query: String, maxResults: Int): List<Map<String, String>> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://cn.bing.com/search?q=$encodedQuery&count=$maxResults")

        val connection = createConnection(url)

        val results = mutableListOf<Map<String, String>>()

        try {
            connection.inputStream.bufferedReader().use { reader ->
                val html = reader.readText()

                // Bing 搜索结果解析
                val resultPattern = Regex(
                    "<li class=\"b_algo\"[^>]*>.*?<h2[^>]*>.*?<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>.*?</h2>.*?<p[^>]*>(.*?)</p>.*?</li>",
                    RegexOption.DOT_MATCHES_ALL
                )

                val matches = resultPattern.findAll(html)

                for (match in matches.take(maxResults)) {
                    val url = match.groupValues[1].trim()
                    val title = cleanHtml(match.groupValues[2])
                    val snippet = cleanHtml(match.groupValues[3])

                    if (title.isNotBlank() && url.isNotBlank() && !url.contains("bing.com")) {
                        results.add(
                            mapOf(
                                "title" to title,
                                "url" to url,
                                "snippet" to snippet
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return results
    }

    /**
     * 使用搜狗搜索
     */
    private fun searchSogou(query: String, maxResults: Int): List<Map<String, String>> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.sogou.com/web?query=$encodedQuery")

        val connection = createConnection(url)

        val results = mutableListOf<Map<String, String>>()

        try {
            connection.inputStream.bufferedReader().use { reader ->
                val html = reader.readText()

                // 搜狗搜索结果解析
                val resultPattern = Regex(
                    "<div class=\"vrwrap\"[^>]*>.*?<h3[^>]*>.*?<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>.*?</h3>.*?<p[^>]*class=\"str\"[^>]*>(.*?)</p>.*?</div>",
                    RegexOption.DOT_MATCHES_ALL
                )

                val matches = resultPattern.findAll(html)

                for (match in matches.take(maxResults)) {
                    var url = match.groupValues[1].trim()
                    val title = cleanHtml(match.groupValues[2])
                    val snippet = cleanHtml(match.groupValues[3])

                    // 搜狗链接需要处理跳转
                    if (url.startsWith("/link?")) {
                        url = "https://www.sogou.com$url"
                    }

                    if (title.isNotBlank() && url.isNotBlank()) {
                        results.add(
                            mapOf(
                                "title" to title,
                                "url" to url,
                                "snippet" to snippet
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return results
    }

    /**
     * 使用 360 搜索
     */
    private fun search360(query: String, maxResults: Int): List<Map<String, String>> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.so.com/s?q=$encodedQuery")

        val connection = createConnection(url)

        val results = mutableListOf<Map<String, String>>()

        try {
            connection.inputStream.bufferedReader().use { reader ->
                val html = reader.readText()

                // 360 搜索结果解析
                val resultPattern = Regex(
                    "<li class=\"res-list\"[^>]*>.*?<h3[^>]*>.*?<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>.*?</h3>.*?<p class=\"res-desc\"[^>]*>(.*?)</p>.*?</li>",
                    RegexOption.DOT_MATCHES_ALL
                )

                val matches = resultPattern.findAll(html)

                for (match in matches.take(maxResults)) {
                    val url = match.groupValues[1].trim()
                    val title = cleanHtml(match.groupValues[2])
                    val snippet = cleanHtml(match.groupValues[3])

                    if (title.isNotBlank() && url.isNotBlank()) {
                        results.add(
                            mapOf(
                                "title" to title,
                                "url" to url,
                                "snippet" to snippet
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return results
    }

    /**
     * 创建 HTTP 连接
     */
    private fun createConnection(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.0")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            connectTimeout = 10000
            readTimeout = 10000
        }
    }

    /**
     * 清理 HTML 标签和实体
     */
    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "") // 移除 HTML 标签
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&middot;", "·")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "...")
            .replace(Regex("\\s+"), " ") // 合并多个空白字符
            .trim()
    }
}
