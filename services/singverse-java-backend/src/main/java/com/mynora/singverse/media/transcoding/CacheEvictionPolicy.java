package com.mynora.singverse.media.transcoding;

/** Pure, bounded retention policy. Playback activity, never HTTP cache hits, supplies its inputs. */
public final class CacheEvictionPolicy {
    public static final long DAY = 86_400_000L;
    public record Score(double H, double R, double F, double score, double priority, String lifecycle) {}
    private CacheEvictionPolicy() {}

    public static double decay(double heat, Long updatedAt, long now) {
        return updatedAt == null ? 0 : clamp(heat) * Math.pow(2, -age(now, updatedAt) / (14.0 * DAY));
    }

    public static Score score(double heat, Long heatAt, Long lastPlayedAt, long plays7d, long createdAt, long now) {
        double h = decay(heat, heatAt, now);
        double r = lastPlayedAt == null ? 0 : Math.pow(2, -age(now, lastPlayedAt) / (7.0 * DAY));
        double f = Math.min(1, Math.log1p(Math.max(0, plays7d)) / Math.log(21));
        double score = clamp(.4 * h + .4 * r + .2 * f);
        double bonus = lastPlayedAt == null ? 0 : .15 * clamp((3.0 * DAY - age(now, lastPlayedAt)) / (2.0 * DAY));
        double priority = clamp(score + (1 - score) * bonus);
        long age = age(now, lastPlayedAt == null ? createdAt : lastPlayedAt);
        String state = lastPlayedAt != null && age < 7 * DAY && plays7d >= 3 ? "HOT"
                : age < 30 * DAY ? "WARM" : age < 60 * DAY ? "COLD" : "EVICT";
        return new Score(h, r, f, score, priority, state);
    }

    public static boolean recent(Long playedAt, long now) { return playedAt != null && age(now, playedAt) < DAY; }
    private static long age(long now, long then) { return then >= now ? 0 : Math.max(0, now - then); }
    private static double clamp(double value) { return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }
}
