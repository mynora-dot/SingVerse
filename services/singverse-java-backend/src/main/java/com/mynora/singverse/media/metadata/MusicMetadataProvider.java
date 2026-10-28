// Adapted from ktv-home (MIT); see docs/third-party/ktv-home-LICENSE.txt.
package com.mynora.singverse.media.metadata;

import java.time.Duration;
import java.util.List;

public interface MusicMetadataProvider {
    MusicProvider provider();
    List<ExternalTrack> search(String keyword, int limit, Duration timeout);
    ExternalTrack detail(String externalId, Duration timeout);

    default ProviderHealth health(Duration timeout) {
        long started = System.nanoTime();
        search("周杰伦", 1, timeout);
        return new ProviderHealth(provider(), true, (System.nanoTime() - started) / 1_000_000, null);
    }

    record ProviderHealth(MusicProvider provider, boolean healthy, long latencyMs, String error) {}
}
