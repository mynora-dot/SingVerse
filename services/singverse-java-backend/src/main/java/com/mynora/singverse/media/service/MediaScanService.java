package com.mynora.singverse.media.service;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.infrastructure.compute.MediaTagsClient;
import com.mynora.singverse.media.dto.MediaSongDtos;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.repository.MediaSourceMapper;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Bounded local filename indexing. Requests embedded tags from the compute service; never writes original files. */
@Service
public class MediaScanService {
    private final MediaSourceRepository sources;
    private final MediaSourceMapper sourceMapper;
    private final MediaSongRepository songs;
    private final FilenameParserConfigService profiles;
    private final MediaTagsClient fileTags;
    private final TransactionTemplate transaction;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private record Context(Source source, FilenameParserConfigService.ProfileSnapshot profile) {}

    public MediaScanService(MediaSourceRepository sources, MediaSourceMapper sourceMapper,
            MediaSongRepository songs, FilenameParserConfigService profiles, PlatformTransactionManager manager, MediaTagsClient fileTags) {
        this.sources = sources; this.sourceMapper = sourceMapper; this.songs = songs;
        this.profiles = profiles; this.fileTags = fileTags; this.transaction = new TransactionTemplate(manager);
    }

    public MediaSongDtos.Page list(String sourceId, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw bad("分页参数超出范围");
        sources.get(sourceId);
        return new MediaSongDtos.Page(songs.list(sourceId, page, size), page, size, songs.count(sourceId));
    }

    public MediaSongDtos.Scan scan(String sourceId) {
        if (!running.add(sourceId)) throw conflict("该媒体来源正在扫描，请稍后重试");
        try { return scanLocal(sourceId); }
        finally { running.remove(sourceId); }
    }

    private MediaSongDtos.Scan scanLocal(String sourceId) {
        Context context = transaction.execute(status -> {
            profiles.lockBindings();
            Source source = sources.get(sourceId);
            if (!"LOCAL".equals(source.type())) throw bad("WebDAV 请使用后台扫描任务入口");
            return new Context(source, profiles.savedProfile(source.parserProfileId()));
        });
        Objects.requireNonNull(context);
        Path root = Path.of(context.source().location());
        var tagReader = fileTags.session();
        var indexed = new ArrayList<MediaSongRepository.Stored>();
        var parser = new KtvFilenameParser();
        String scannedAt = Instant.now().toString();
        int[] visited = {0}, skipped = {0}, matched = {0}, review = {0};
        long deadline = System.nanoTime() + 30_000_000_000L;
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(root)
                    || !root.toRealPath().equals(root)) throw bad("本地目录已离线或路径发生变化，请检查挂载");
            Files.walkFileTree(root, Set.of(), 32, new SimpleFileVisitor<>() {
                private void budget() {
                    if (++visited[0] > 20000 || System.nanoTime() > deadline)
                        throw bad("目录超过单次扫描范围（20000 个条目 / 30 秒），请拆分媒体来源后重试");
                }
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    budget();
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    budget();
                    if (attributes.isDirectory()) throw bad("目录超过 32 层，请拆分媒体来源后重试");
                    if (!attributes.isRegularFile() || !KtvFilenameParser.supportsMediaFilename(file.getFileName().toString())) {
                        skipped[0]++; return FileVisitResult.CONTINUE;
                    }
                    if (!file.toRealPath().startsWith(root) || !Files.isReadable(file))
                        throw bad("歌曲文件不可读或路径发生变化，请检查权限后重试");
                    if (indexed.size() >= 2000) throw bad("单次最多扫描 2000 个媒体文件，请拆分媒体来源后重试");
                    String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                    if (relative.length() > 2048) throw bad("歌曲相对路径过长，请缩短目录后重试");
                    var parsed = parser.preview(file.getFileName().toString(), context.profile().rules());
                    if (parsed.selectedRule() != null) matched[0]++;
                    String id = UUID.nameUUIDFromBytes((sourceId + "\0" + relative).getBytes(StandardCharsets.UTF_8)).toString();
                    indexed.add(new MediaSongRepository.Stored(id, sourceId, relative, AuthCrypto.hash(relative),
                            attributes.size(), attributes.lastModifiedTime().toInstant().toString(), scannedAt,
                            context.profile().id(), context.profile().revision(),
                            parsed.selectedRule() == null ? null : parsed.selectedRule().id(), songs.encode(parsed.result()), parsed.result().needsReview(), null));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException error) {
            throw bad("扫描未完成：目录或文件不可读取，原歌曲数据已保留，请检查挂载和权限");
        }
        // Validate traversal limits before launching any bounded tag subprocesses.
        for (int index = 0; index < indexed.size(); index++) {
            var song = indexed.get(index);
            Path file = root.resolve(song.relativePath());
            String embedded = tagReader.apply(file);
            try {
                var afterRead = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!afterRead.isRegularFile() || afterRead.size() != song.fileSize()
                        || !afterRead.lastModifiedTime().toInstant().toString().equals(song.modifiedAt())
                        || !file.toRealPath().startsWith(root))
                    throw conflict("扫描期间文件已变化，原歌曲索引保留，请重试");
            } catch (IOException error) { throw conflict("扫描期间文件已变化，原歌曲索引保留，请重试"); }
            if (System.nanoTime() > deadline) throw bad("读取文件标签超出单次扫描30秒范围，请拆分媒体来源后重试");
            if (song.needsReview() && !fileTags.hasIdentity(embedded)) review[0]++;
            indexed.set(index, new MediaSongRepository.Stored(song.id(), song.sourceId(), song.relativePath(), song.pathHash(),
                    song.fileSize(), song.modifiedAt(), song.scannedAt(), song.profileId(), song.revision(), song.ruleId(), song.resultJson(), song.needsReview(), embedded));
        }
        transaction.executeWithoutResult(status -> {
            profiles.lockBindings();
            Source current = sources.get(sourceId);
            if (!com.mynora.singverse.media.dto.MediaSourceDtos.sameScanConfiguration(current, context.source())
                    || profiles.savedProfile(current.parserProfileId()).revision() != context.profile().revision())
                throw conflict("媒体来源或解析方案已修改，本次扫描未保存，请重新扫描");
            if (indexed.isEmpty() && songs.count(sourceId) > 0)
                throw conflict("本次未发现媒体文件，已有歌曲索引已保留；请先检查目录挂载与文件类型");
            if (sourceMapper.touch(sourceId, context.source().updatedAt(), Instant.now().toString()) != 1)
                throw conflict("媒体来源已修改，本次扫描未保存，请重新扫描");
            songs.replace(sourceId, indexed);
        });
        return new MediaSongDtos.Scan(sourceId, context.profile().id(), context.profile().name(),
                context.profile().revision(), indexed.size(), matched[0], review[0], skipped[0], scannedAt);
    }

    private static ResponseStatusException bad(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }
}
