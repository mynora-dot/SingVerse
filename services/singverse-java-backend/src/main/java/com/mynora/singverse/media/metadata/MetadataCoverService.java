package com.mynora.singverse.media.metadata;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpRequest;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetadataCoverService {
    public record Cover(byte[] bytes, MediaType type) {}
    private final MetadataService service;
    private final CoverAssetService assets;
    private final MetadataSettingsService settings;
    private final MediaSongRepository songs;
    private final Path cache;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Map<MusicProvider, Set<String>> HOSTS = Map.of(
            MusicProvider.NETEASE, Set.of("music.126.net"), MusicProvider.QQ, Set.of("y.gtimg.cn"),
            MusicProvider.KUGOU, Set.of("kugou.com", "kugoucdn.com", "kgimg.com"));
    public MetadataCoverService(MetadataService service, MetadataSettingsService settings, MediaSongRepository songs, CoverAssetService assets,
            @Value("${singverse.metadata.cache-path:./data/metadata/covers}") String path) {
        this.assets=assets; this.service = service; this.settings = settings; this.songs = songs; this.cache = Path.of(path).toAbsolutePath().normalize();
    }
    public Cover candidate(String id) { var track = service.candidateTrack(id); return load(track.provider(), track.coverUrl()); }
    public Cover applied(String songId) {
        var metadata = songs.get(songId).metadata();
        if (metadata == null || metadata.stale() || !metadata.fields().contains("cover") || !metadata.origins().containsKey("cover")) throw missing();
        if (metadata.track().coverUrl()!=null && metadata.track().coverUrl().startsWith("asset:")) return assets.image(metadata.track().coverUrl().substring(6));
        return load(metadata.origins().get("cover").provider(), metadata.track().coverUrl());
    }
    private Cover load(MusicProvider provider, String url) {
        if (url == null) throw missing();
        URI uri = checked(provider, url);
        Path file = cache.resolve(AuthCrypto.hash(provider + ":" + uri) + ".image");
        try {
            if (Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
                try (var input = Files.newInputStream(file)) { return validate(input.readNBytes(2_000_001)); }
            }
            if (!settings.require().providers().contains(provider)) throw missing();
            rejectPrivate(uri.getHost());
            var request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(5))
                    .header("Accept", "image/jpeg,image/png").header("User-Agent", "SingVerse/0.1 metadata-cover").build();
            var response = client.send(request, info -> new BoundedResponse(2_000_000));
            if (response.statusCode() != 200) throw missing();
            Cover cover = validate(response.body());
            Files.createDirectories(cache);
            Path temp = Files.createTempFile(cache, "cover-", ".tmp");
            try { Files.write(temp, cover.bytes()); Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
            return cover;
        } catch (ResponseStatusException error) { throw error; }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "封面暂时不可用");
        }
    }
    static URI checked(MusicProvider provider, String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                    || HOSTS.get(provider).stream().noneMatch(allowed -> host.equalsIgnoreCase(allowed) || host.toLowerCase(Locale.ROOT).endsWith("." + allowed))) throw missing();
            return uri;
        } catch (IllegalArgumentException error) { throw missing(); }
    }
    private static void rejectPrivate(String host) throws Exception {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            byte[] bytes = address.getAddress();
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)) throw missing();
        }
    }
    static Cover validate(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 2_000_000) throw missing();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw missing();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("jpeg", "png").contains(format) || reader.getWidth(0) < 1 || reader.getHeight(0) < 1
                        || reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096) throw missing();
                return new Cover(bytes, format.equals("png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG);
            } finally { reader.dispose(); }
        } catch (Exception error) { throw missing(); }
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "没有可用的封面"); }
}
