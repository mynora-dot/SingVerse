package com.mynora.singverse.media.artist;

import com.mynora.singverse.media.image.UploadedImage;
import com.mynora.singverse.media.metadata.ArtistArtworkProvider;
import com.mynora.singverse.tv.TvLibrary;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Component
public class ArtistArtworkWorker implements org.springframework.beans.factory.DisposableBean {
    private final java.util.concurrent.ScheduledExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "artist-artwork");
                        t.setDaemon(true);
                        return t;
                    });
    private final ArtistMapper mapper;
    private final ArtistService artists;
    private final TvLibrary library;
    private final ArtistArtworkProvider provider;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public ArtistArtworkWorker(
            ArtistMapper mapper,
            ArtistService artists,
            TvLibrary library,
            ArtistArtworkProvider provider,
            PlatformTransactionManager manager,
            ApplicationEventPublisher events,
            @Value("${singverse.artists.worker-enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.artists = artists;
        this.library = library;
        this.provider = provider;
        this.tx = new TransactionTemplate(manager);
        this.events = events;
        if (enabled)
            executor.scheduleWithFixedDelay(
                    () -> {
                        try {
                            tick();
                        } catch (Exception ignored) {
                        }
                    },
                    5,
                    2,
                    java.util.concurrent.TimeUnit.SECONDS);
    }

    public void destroy() {
        executor.shutdownNow();
    }

    public synchronized void tick() {
        // Building the shared index creates missing name records; it never performs network I/O.
        library.artists("", 0, 1);
        var pending = mapper.pending(Instant.now().toString());
        if (pending.isEmpty()) return;
        var job = pending.getFirst();
        String id = job.get("id").toString();
        long generation = ((Number) job.get("fetch_generation")).longValue();
        int attempts = ((Number) job.get("fetch_attempts")).intValue();
        com.fasterxml.jackson.databind.node.ObjectNode active;
        try {
            active = library.artistDetail(id);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            if (e.getStatusCode().value() != 404) throw e;
            tx.executeWithoutResult(
                    st -> mapper.fetchState(id, "DORMANT", attempts, "", generation));
            return;
        }
        if (mapper.policy(id, "PORTRAIT") > 0 || mapper.image(id, "PORTRAIT") != null) {
            tx.executeWithoutResult(st -> mapper.fetchState(id, "READY", attempts, "", generation));
            return;
        }
        var page = library.adminArtistSongs(id, 0, 3);
        var titles = new ArrayList<String>();
        var refs = new ArrayList<com.mynora.singverse.media.metadata.ExternalTrack.ArtistRef>();
        for (Object o : (List<?>) page.get("items")) {
            var song = (com.mynora.singverse.media.dto.MediaSongDtos.Song) o;
            var values = com.mynora.singverse.media.metadata.SingleReviewService.current(song);
            titles.add(values.get("title").toString());
            if (song.metadata() != null
                    && !song.metadata().stale()
                    && song.metadata().origins().containsKey("artists")
                    && "PLATFORM".equals(song.metadata().origins().get("artists").kind()))
                refs.addAll(song.metadata().track().artistRefs());
        }
        try {
            var found = provider.find(active.path("name").asText(), titles, refs);
            byte[] portrait =
                    found == null || found.portrait() == null ? null : read(found.portrait());
            tx.executeWithoutResult(
                    s -> {
                        mapper.lock();
                        var current = mapper.find(id);
                        if (((Number) current.get("fetch_generation")).longValue() != generation)
                            return;
                        if (found != null && portrait != null)
                            store(id, "PORTRAIT", portrait, found, found.portrait());
                        mapper.fetchState(
                                id, found == null ? "NO_MATCH" : "READY", attempts, "", generation);
                        events.publishEvent(new ArtistDtos.Changed());
                    });
        } catch (Exception e) {
            tx.executeWithoutResult(
                    s -> {
                        mapper.lock();
                        int next = attempts + 1;
                        mapper.fetchState(
                                id,
                                next > 3 ? "FAILED" : "RETRY",
                                next,
                                Instant.now()
                                        .plusSeconds(next == 1 ? 600 : next == 2 ? 3600 : 21600)
                                        .toString(),
                                generation);
                        events.publishEvent(new ArtistDtos.Changed());
                    });
        }
    }

    private void store(
            String id, String kind, byte[] bytes, ArtistArtworkProvider.Artwork found, String url) {
        if (mapper.policy(id, kind) > 0 || mapper.image(id, kind) != null) return;
        String version = java.util.HexFormat.of().formatHex(digest(bytes));
        mapper.insertImage(id, kind, version, bytes);
        mapper.imageSource(id, kind, "AUTO", found.provider(), found.externalId(), url);
    }

    private static byte[] digest(byte[] b) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected byte[] read(String url) throws Exception {
        return download(url);
    }

    public static byte[] download(String url) throws Exception {
        URI uri = URI.create(url);
        String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || !(host.equals("y.gtimg.cn")
                        || host.matches("p[1-4]\\.music\\.126\\.net")
                        || host.equals("music.126.net")))
            throw new IllegalArgumentException("图片来源无效");
        for (var address : InetAddress.getAllByName(host))
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) throw new IllegalArgumentException("图片地址无效");
        var client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var response =
                client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IllegalStateException("图片读取失败");
            byte[] bytes = stream.readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IllegalArgumentException("图片过大");
            String type =
                    response.headers().firstValue("Content-Type").orElse("").split(";")[0].strip();
            if (type.equalsIgnoreCase("image/jpg")) type = "image/jpeg";
            return UploadedImage.normalize(new Bytes(bytes, type));
        }
    }

    private record Bytes(byte[] bytes, String type)
            implements org.springframework.web.multipart.MultipartFile {
        public String getName() {
            return "file";
        }

        public String getOriginalFilename() {
            return "artist";
        }

        public String getContentType() {
            return type;
        }

        public boolean isEmpty() {
            return bytes.length == 0;
        }

        public long getSize() {
            return bytes.length;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        public void transferTo(java.io.File f) throws java.io.IOException {
            java.nio.file.Files.write(f.toPath(), bytes);
        }
    }
}
