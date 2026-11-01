package com.mynora.singverse.media.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class AudioModelControllerTest {
    final TranscodeClient compute = mock(TranscodeClient.class);
    final AudioProcessingController controller = new AudioProcessingController(mock(AudioProcessingService.class), compute);

    @Test void modelSelectionIsValidatedBeforeComputeDownload() {
        assertThatThrownBy(() -> controller.download("unknown")).hasMessageContaining("未知音频模型");
        verifyNoInteractions(compute);
        controller.download("htdemucs-openvino");
        verify(compute).call(isNull(), eq("/internal/v1/audio-roles/models/download?model=htdemucs-openvino"), eq("POST"), isNull());
    }

    @Test void uploadEnforcesSeparateManifestBoundsAndStreamsTheOriginalBody() throws Exception {
        var request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(101L * 1024 * 1024);
        assertThatThrownBy(() -> controller.upload(request, "htdemucs")).hasMessageContaining("大小限制");
        verifyNoInteractions(compute);
        var input = new org.springframework.mock.web.DelegatingServletInputStream(new ByteArrayInputStream(new byte[] {1, 2}));
        when(request.getInputStream()).thenReturn(input);
        controller.upload(request, "htdemucs-openvino");
        verify(compute).uploadAudioModel(same(input), eq(101L * 1024 * 1024), eq("htdemucs-openvino"));
        when(request.getContentLengthLong()).thenReturn(129L * 1024 * 1024);
        assertThatThrownBy(() -> controller.upload(request, "htdemucs-openvino")).hasMessageContaining("大小限制");
    }
}
