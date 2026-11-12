package com.mynora.singverse.tv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun tvHistoryStatus(status: String): String = when (status) {
    "COMPLETED" -> "已唱完"
    "SKIPPED" -> "已跳过"
    "STOPPED" -> "已停止"
    "ERROR", "FAILED" -> "播放失败"
    "INTERRUPTED" -> "已中断"
    "PLAYING" -> "演唱中"
    else -> "演唱记录"
}

/** The server emits UTC; format in the television's timezone without requiring API 26. */
internal fun tvHistoryDate(value: String): String = runCatching {
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(invitationExpiry(value)))
}.getOrDefault("时间未知")
