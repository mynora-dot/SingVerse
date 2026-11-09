package com.mynora.singverse.media.download;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LxCoverServiceTest {
    static byte[] webp(){return Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAdQqFIUr/+BiOh/AAA=");}
    @Test void servesBoundedWebpCoversWithoutChangingTheirBytes() {
        byte[] bytes=webp();var cover=LxCoverService.validate(bytes);
        assertThat(cover.type().toString()).isEqualTo("image/webp");assertThat(cover.bytes()).isEqualTo(bytes);
    }
    @Test void rejectsTruncatedOversizedAndAnimatedWebpHeaders() {
        byte[] bytes=webp();assertThatThrownBy(()->LxCoverService.validate(Arrays.copyOf(bytes,bytes.length-1))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        bytes[21]=(byte)255;bytes[22]=(byte)255;assertThatThrownBy(()->LxCoverService.validate(bytes)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        byte[] animated=webp();System.arraycopy("ANIM".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,animated,12,4);assertThatThrownBy(()->LxCoverService.validate(animated)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
