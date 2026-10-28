package com.mynora.singverse.media.filename;

import java.util.Locale;

final class ExtensionHandler {
    record Parts(String normalized, String stem, String extension, boolean supported) {}

    static Parts extract(String normalized) {
        int dot = normalized.lastIndexOf('.');
        if (dot < 0 || !normalized.substring(dot + 1).matches("[A-Za-z0-9]{1,10}"))
            return new Parts(normalized, normalized, null, true);
        String extension = normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
        String stem = normalized.substring(0, dot).strip();
        return new Parts(normalized.substring(0, dot + 1) + extension,
                stem, extension, MediaFileTypes.supported(extension));
    }
}
