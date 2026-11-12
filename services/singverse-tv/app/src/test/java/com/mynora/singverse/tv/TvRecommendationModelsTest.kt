package com.mynora.singverse.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class TvRecommendationModelsTest {
    @Test fun historyPreservesDifferentTerminalOutcomes() {
        assertEquals("已唱完", tvHistoryStatus("COMPLETED"))
        assertEquals("已跳过", tvHistoryStatus("SKIPPED"))
        assertEquals("已停止", tvHistoryStatus("STOPPED"))
        assertEquals("播放失败", tvHistoryStatus("ERROR"))
        assertEquals("已中断", tvHistoryStatus("INTERRUPTED"))
        assertEquals("演唱记录", tvHistoryStatus(""))
    }

    @Test fun datesAcceptServerFractionsAndUseTelevisionTimezone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
            assertEquals("11-12 09:25", tvHistoryDate("2026-11-12T01:25:30.123456789Z"))
            assertEquals("时间未知", tvHistoryDate("invalid"))
            assertEquals("时间未知", tvHistoryDate(""))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
