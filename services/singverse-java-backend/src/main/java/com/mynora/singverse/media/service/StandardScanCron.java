package com.mynora.singverse.media.service;

import java.time.*;
import org.springframework.scheduling.support.CronExpression;

/** Unix five-field cron, including OR semantics when both day fields are restricted. */
public final class StandardScanCron {
    private StandardScanCron() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static Instant next(String expression, Instant after) {
        if (expression == null || expression.length() > 120) throw new IllegalArgumentException("请输入五段 cron：分 时 日 月 周");
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5 || !expression.matches("[0-9A-Za-z*/ ,\\-]+")) throw new IllegalArgumentException("cron 必须为五段格式：分 时 日 月 周，不支持秒、年或宏");
        try {
            // Spring also accepts Quartz extensions: exclude them from standard Unix day fields.
            if (fields[2].matches(".*[A-Za-z].*") || fields[4].toUpperCase(java.util.Locale.ROOT).replaceAll("SUN|MON|TUE|WED|THU|FRI|SAT", "").matches(".*[A-Z#].*")) throw new IllegalArgumentException();
            ZonedDateTime next;
            var time = after.atZone(ZONE);
            if (!fields[2].startsWith("*") && !fields[4].startsWith("*")) {
                String[] byDate = fields.clone(), byWeek = fields.clone(); byDate[4] = "*"; byWeek[2] = "*";
                var a = CronExpression.parse("0 " + String.join(" ", byDate)).next(time);
                var b = CronExpression.parse("0 " + String.join(" ", byWeek)).next(time);
                next = a == null ? b : b == null || a.isBefore(b) ? a : b;
            } else next = CronExpression.parse("0 " + String.join(" ", fields)).next(time);
            if (next == null) throw new IllegalArgumentException();
            return next.toInstant();
        } catch (IllegalArgumentException error) { throw new IllegalArgumentException("cron 表达式无效或没有可执行日期，请检查分、时、日、月、周范围"); }
    }
}
