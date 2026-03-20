package com.example.aiagent.tools

import com.intellij.openapi.project.Project
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 获取当前时间工具
 * 返回当前日期和时间，支持不同时区
 */
class GetCurrentTimeTool : Tool(
    name = "get_current_time",
    description = "Get the current date and time. Returns formatted datetime string with timezone information.",
    parameters = listOf(
        ToolParameter(
            name = "timezone",
            type = "string",
            description = "Timezone ID (e.g., 'Asia/Shanghai', 'America/New_York', 'UTC'). Default is system default timezone.",
            required = false
        ),
        ToolParameter(
            name = "format",
            type = "string",
            description = "DateTime format pattern. Default is 'yyyy-MM-dd HH:mm:ss'. Examples: 'yyyy-MM-dd', 'HH:mm:ss', 'yyyy年MM月dd日 HH时mm分ss秒'",
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
        val timezoneId = params["timezone"] as? String ?: ZoneId.systemDefault().id
        val formatPattern = params["format"] as? String ?: "yyyy-MM-dd HH:mm:ss"

        return try {
            val zoneId = ZoneId.of(timezoneId)
            val now = ZonedDateTime.now(zoneId)
            val formatter = DateTimeFormatter.ofPattern(formatPattern)
            val formattedTime = now.format(formatter)

            val result = mapOf(
                "datetime" to formattedTime,
                "timezone" to timezoneId,
                "timestamp" to now.toEpochSecond(),
                "iso8601" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "year" to now.year,
                "month" to now.monthValue,
                "day" to now.dayOfMonth,
                "hour" to now.hour,
                "minute" to now.minute,
                "second" to now.second,
                "day_of_week" to now.dayOfWeek.name,
                "day_of_week_number" to now.dayOfWeek.value
            )

            onOutput?.invoke("当前时间: $formattedTime ($timezoneId)")

            ToolResult.Success(result)
        } catch (e: Exception) {
            ToolResult.Error("Invalid timezone or format: ${e.message}")
        }
    }
}
