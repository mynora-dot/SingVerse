// Adapted from ktv-home (MIT); see docs/third-party/ktv-home-LICENSE.txt.
package com.mynora.singverse.media.metadata;

public class MusicSourceException extends RuntimeException {
    private final MusicProvider provider;

    public MusicSourceException(MusicProvider provider, String message) { super(message); this.provider = provider; }
    public MusicSourceException(MusicProvider provider, String message, Throwable cause) { super(message, cause); this.provider = provider; }
    public boolean rateLimited() { return getMessage() != null && getMessage().contains("HTTP 429"); }
    public MusicProvider provider() { return provider; }
}
