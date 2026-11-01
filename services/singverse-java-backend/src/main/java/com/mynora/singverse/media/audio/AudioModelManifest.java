package com.mynora.singverse.media.audio;

/** Release-owned upload bounds; remote URLs and caller-provided manifests are not accepted. */
public final class AudioModelManifest {
    private AudioModelManifest() {}
    public static long maxBytes(String model) {
        return switch (model) {
            case "panns-cnn6" -> 32L * 1024 * 1024;
            case "htdemucs" -> 100L * 1024 * 1024;
            case "htdemucs-openvino" -> 128L * 1024 * 1024;
            default -> throw AudioJobService.fail(400, "未知音频模型");
        };
    }
}
