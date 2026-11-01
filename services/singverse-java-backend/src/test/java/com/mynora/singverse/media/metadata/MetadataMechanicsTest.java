package com.mynora.singverse.media.metadata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MetadataMechanicsTest {
    ExternalTrack track(String title, List<String> artists) {
        return new ExternalTrack(MusicProvider.NETEASE, "id", title, artists, null, null, null, List.of(), null, "UNKNOWN", null);
    }
    @Test void matchingKeepsVersionDifferencesAndMissingArtistCannotAutoApply() {
        var matcher = new MetadataMatcher();
        assertThat(matcher.score("晴天", List.of("周杰伦"), track("晴天", List.of("周杰伦")))).isEqualTo(.95);
        assertThat(matcher.score("晴天", List.of(), track("晴天", List.of("周杰伦")))).isLessThan(.8);
        assertThat(matcher.score("晴天", List.of("周杰伦"), track("晴天 Live", List.of("周杰伦")))).isLessThan(.95);
        assertThat(matcher.score("Ｓｕｎｎｙ", List.of("Singer"), track("sunny", List.of("singer")))).isEqualTo(.95);
    }
    @Test void highestCandidateAutomaticallyAppliesWhenThresholdIsMet() {
        var one = new MetadataDtos.Candidate("1", track("晴天", List.of("周杰伦")), .95);
        var close = new MetadataDtos.Candidate("2", track("晴天", List.of("周杰伦")), .94);
        assertThat(MetadataWorker.automatic(List.of(one), .95)).isTrue();
        assertThat(MetadataWorker.automatic(List.of(one, close), .95)).isTrue();
        assertThat(MetadataWorker.automatic(List.of(one, new MetadataDtos.Candidate("3", track("晴天", List.of("周杰伦")), .95)), .8)).isTrue();
        assertThat(MetadataWorker.automatic(List.of(one, close), .96)).isFalse();
        assertThat(MetadataWorker.automatic(List.of(one), Double.POSITIVE_INFINITY)).isFalse();
    }
    @Test void oversizedHttpResponseCancelsSubscriptionBeforeAccumulatingBody() {
        var response = new BoundedResponse(8); AtomicBoolean cancelled = new AtomicBoolean();
        response.onSubscribe(new Flow.Subscription() {
            public void request(long count) {}
            public void cancel() { cancelled.set(true); }
        });
        response.onNext(List.of(ByteBuffer.wrap(new byte[9])));
        assertThat(cancelled).isTrue();
        assertThat(response.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
    @Test void coversAllowOnlyBoundedJpegPngAndValidPlatformHosts() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        assertThat(MetadataCoverService.validate(output.toByteArray()).type().toString()).isEqualTo("image/png");
        output.reset(); ImageIO.write(new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        assertThatThrownBy(() -> MetadataCoverService.validate(output.toByteArray()));
        assertThatThrownBy(() -> MetadataCoverService.checked(MusicProvider.QQ, "https://user@y.gtimg.cn/cover.jpg"));
        assertThatThrownBy(() -> MetadataCoverService.checked(MusicProvider.QQ, "http://y.gtimg.cn/cover.jpg"));
        assertThatThrownBy(() -> MetadataCoverService.checked(MusicProvider.QQ, "https://y.gtimg.cn:443/cover.jpg"));
    }
    @Test void rateLimitedProviderCoolsDownWithoutSendingAnotherRequest() {
        var guard = new ProviderCallGuard();
        assertThatThrownBy(() -> guard.call(MusicProvider.QQ, () -> { throw new MusicSourceException(MusicProvider.QQ, "平台返回 HTTP 429"); })).isInstanceOf(MusicSourceException.class);
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> guard.call(MusicProvider.QQ, () -> { called.set(true); return "unexpected"; })).hasMessageContaining("冷却");
        assertThat(called).isFalse();
        assertThat(guard.call(MusicProvider.NETEASE, () -> "ok")).isEqualTo("ok");
    }

    @Test void scheduledBatchDoesNotStartNextSongBeforeConfiguredDelay() {
        var settings = mock(MetadataSettingsService.class);
        when(settings.read()).thenReturn(new MetadataDtos.Settings(true, List.of(MusicProvider.NETEASE), false, .95, 300, 300));
        var worker = spy(new MetadataWorker(mock(MetadataMapper.class), mock(MetadataJson.class),
                mock(MetadataService.class), mock(com.mynora.singverse.media.repository.MediaSongRepository.class),
                settings, mock(org.springframework.transaction.PlatformTransactionManager.class), true));
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "ready", true);
        doNothing().when(worker).processNext();
        worker.tick(); worker.tick();
        verify(worker, times(1)).processNext();
    }

}
