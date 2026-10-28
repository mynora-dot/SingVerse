package com.mynora.singverse.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mynora.singverse.media.service.*;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.webdav.WebDavScanService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MediaScanScheduleTest {
    @Test void standardCronUsesFiveFieldsShanghaiAndUnixDayOrSemantics() {
        var now = Instant.parse("2026-10-28T00:01:00Z");
        assertThat(StandardScanCron.next("0 3 * * *", now)).isEqualTo(Instant.parse("2026-10-28T19:00:00Z"));
        assertThat(StandardScanCron.next("*/30 * * * *", now)).isEqualTo(Instant.parse("2026-10-28T00:30:00Z"));
        assertThat(StandardScanCron.next("0 9 * * WED", now)).isEqualTo(Instant.parse("2026-10-28T01:00:00Z"));
        assertThat(StandardScanCron.next("0 9 1 * 3", now)).isEqualTo(Instant.parse("2026-10-28T01:00:00Z"));
        assertThat(StandardScanCron.next("0 9 */2 * WED", now)).isEqualTo(Instant.parse("2026-11-11T01:00:00Z"));
        for (String invalid : List.of("0 0 3 * * *", "60 * * * *", "0 25 * * *", "@daily", "0 3 ? * *", "0 3 L * *", "0 3 * * 2#1", "0 0 31 2 *"))
            assertThatThrownBy(() -> StandardScanCron.next(invalid, now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void dueSourceIsClaimedBeforeScanAndDuplicateClaimDoesNotRun() throws Exception {
        var mapper = mock(MediaSourceMapper.class); var sources = mock(MediaSourceRepository.class);
        var local = mock(MediaScanService.class); var dav = mock(WebDavScanService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var now = Instant.parse("2026-10-28T00:00:00Z");
        when(mapper.due(anyString())).thenReturn(List.of(Map.of("id", "source", "scan_cron", "*/30 * * * *", "next_scan_at", now.toString())));
        when(mapper.claim(eq("source"), eq(now.toString()), anyString())).thenReturn(1, 0);
        var source = mock(com.mynora.singverse.media.dto.MediaSourceDtos.Source.class);
        when(source.scanEnabled()).thenReturn(true); when(source.scanCron()).thenReturn("*/30 * * * *"); when(source.type()).thenReturn("LOCAL");
        when(sources.get("source")).thenReturn(source);
        var scheduler = new MediaScanScheduler(mapper, sources, local, dav, manager, true);
        try {
            scheduler.dispatch(now);
            verify(local, timeout(3000)).scan("source");
            verify(mapper, timeout(3000)).scanResult(eq("source"), anyString(), eq("SUCCEEDED"), isNull());
            scheduler.dispatch(now);
            verify(local, times(1)).scan("source");
            verify(mapper, times(2)).claim("source", now.toString(), "2026-10-28T00:30:00Z");
        } finally { scheduler.stop(); }
    }
}
