package com.mynora.singverse.media.transcoding;

import static com.mynora.singverse.media.transcoding.CacheEvictionPolicy.DAY;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CacheEvictionPolicyTest {
    private static final long NOW = Instant.parse("2026-11-07T00:00:00Z").toEpochMilli();

    @Test void heatIsBoundedAndHalvesEveryFourteenDays() {
        assertThat(CacheEvictionPolicy.decay(20, NOW, NOW)).isEqualTo(1);
        assertThat(CacheEvictionPolicy.decay(1, NOW - 14 * DAY, NOW)).isEqualTo(.5);
        assertThat(CacheEvictionPolicy.decay(1, NOW - 28 * DAY, NOW)).isEqualTo(.25);
        assertThat(CacheEvictionPolicy.decay(Double.NaN, NOW, NOW)).isZero();
        assertThat(CacheEvictionPolicy.decay(1, null, NOW)).isZero();
    }

    @Test void exactWeightedComponentsAndLogarithmicFrequencyRemainNormalized() {
        var score = CacheEvictionPolicy.score(1, NOW, NOW - 7 * DAY, 20, NOW, NOW);
        assertThat(score.H()).isEqualTo(1);
        assertThat(score.R()).isEqualTo(.5);
        assertThat(score.F()).isEqualTo(1);
        assertThat(score.score()).isCloseTo(.8, within(1e-12));
        assertThat(score.priority()).isEqualTo(score.score());
        assertThat(CacheEvictionPolicy.score(1, NOW, NOW, Long.MAX_VALUE, NOW, NOW).score()).isEqualTo(1);
    }

    @Test void recentHardProtectionHasAnExactTwentyFourHourBoundary() {
        assertThat(CacheEvictionPolicy.recent(NOW, NOW)).isTrue();
        assertThat(CacheEvictionPolicy.recent(NOW - DAY + 1, NOW)).isTrue();
        assertThat(CacheEvictionPolicy.recent(NOW - DAY, NOW)).isFalse();
        assertThat(CacheEvictionPolicy.recent(null, NOW)).isFalse();
    }

    @Test void softProtectionDecaysBetweenOneAndThreeDays() {
        for (int hours : new int[]{24, 48, 72}) {
            var score = CacheEvictionPolicy.score(0, null, NOW - hours * 3_600_000L, 0, 0, NOW);
            double expectedBonus = hours == 24 ? .15 : hours == 48 ? .075 : 0;
            assertThat(score.priority()).isCloseTo(score.score() + (1 - score.score()) * expectedBonus, within(1e-12));
        }
    }

    @Test void lifecycleUsesRealActivityAndNeverInventsHeatForNewCache() {
        assertThat(CacheEvictionPolicy.score(0, null, NOW - 6 * DAY, 3, 0, NOW).lifecycle()).isEqualTo("HOT");
        assertThat(CacheEvictionPolicy.score(0, null, NOW - 6 * DAY, 2, 0, NOW).lifecycle()).isEqualTo("WARM");
        assertThat(CacheEvictionPolicy.score(0, null, NOW - 7 * DAY, 3, 0, NOW).lifecycle()).isEqualTo("WARM");
        assertThat(CacheEvictionPolicy.score(0, null, NOW - 30 * DAY, 0, 0, NOW).lifecycle()).isEqualTo("COLD");
        assertThat(CacheEvictionPolicy.score(1, NOW - 60 * DAY, NOW - 60 * DAY, 0, 0, NOW).lifecycle()).isEqualTo("EVICT");
        var fresh = CacheEvictionPolicy.score(0, null, null, 0, NOW, NOW);
        assertThat(fresh.H()).isZero(); assertThat(fresh.R()).isZero(); assertThat(fresh.F()).isZero();
        assertThat(fresh.score()).isZero(); assertThat(fresh.priority()).isZero();
        assertThat(fresh.lifecycle()).isEqualTo("WARM");
        assertThat(CacheEvictionPolicy.score(0, null, null, 0, NOW - 60 * DAY, NOW).lifecycle()).isEqualTo("EVICT");
    }

    @Test void aHugeOldHistoryHasNoPermanentProtectionAndLowHeatOldSongRanksFirst() {
        var historical = CacheEvictionPolicy.score(1, NOW - 61 * DAY, NOW - 61 * DAY, 0, 0, NOW);
        var unused = CacheEvictionPolicy.score(.05, NOW - 61 * DAY, NOW - 61 * DAY, 0, 0, NOW);
        var newPlay = CacheEvictionPolicy.score(.05, NOW, NOW, 1, 0, NOW);
        assertThat(historical.priority()).isLessThan(.03);
        assertThat(unused.priority()).isLessThan(historical.priority());
        assertThat(newPlay.priority()).isGreaterThan(historical.priority());
    }
}
