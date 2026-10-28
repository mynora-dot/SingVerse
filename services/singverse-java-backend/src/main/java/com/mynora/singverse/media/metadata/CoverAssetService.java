package com.mynora.singverse.media.metadata;

import static com.mynora.singverse.media.metadata.MetadataJson.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.mynora.singverse.media.repository.MediaSongRepository;

@Service
public class CoverAssetService {
    private final SingleReviewMapper mapper;
    private final MetadataMapper metadata;
    private final MediaSongRepository songs;
    private final Path root;
    private final TransactionTemplate tx;
    public CoverAssetService(SingleReviewMapper mapper, MetadataMapper metadata, MediaSongRepository songs,
            PlatformTransactionManager manager, @Value("${singverse.metadata.cache-path:./data/metadata/covers}") String path) {
        this.mapper=mapper; this.metadata=metadata; this.songs=songs; root=Path.of(path).toAbsolutePath().normalize().resolve("uploads"); tx=new TransactionTemplate(manager);
    }
    public SingleReviewDtos.Asset upload(String songId,String fingerprint,MultipartFile file) {
        var song=songs.get(songId);
        if (!MediaSongRepository.fingerprint(song).equals(fingerprint)) throw conflict("歌曲文件已变化，请重新加载");
        if (file==null || file.isEmpty() || file.getSize()>2_000_000 || !Set.of("image/png","image/jpeg").contains(Objects.toString(file.getContentType(),""))) throw bad("请选择 2 MB 以内的 JPEG 或 PNG 图片");
        String id=UUID.randomUUID().toString(); Path target=path(id); Path temp=null;
        try {
            byte[] bytes;
            try(var in=file.getInputStream()) { bytes=in.readNBytes(2_000_001); }
            var cover=MetadataCoverService.validate(bytes);
            if (!cover.type().toString().equals(file.getContentType())) throw bad("图片类型与实际内容不一致");
            var decoded=ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded==null) throw bad("图片无法解码");
            var out=new ByteArrayOutputStream();
            String format=cover.type().equals(MediaType.IMAGE_PNG)?"png":"jpeg";
            if (!ImageIO.write(decoded,format,out) || out.size()>2_000_000) throw bad("图片重新编码后超过 2 MB，请压缩后重试");
            Files.createDirectories(root); temp=Files.createTempFile(root,"upload-",".tmp");
            Files.write(temp,out.toByteArray()); Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
            String time=now();
            tx.executeWithoutResult(s -> { metadata.lock();
                if (!MediaSongRepository.fingerprint(songs.get(songId)).equals(fingerprint)) throw conflict("上传期间文件已变化，请重新加载");
                mapper.assetSave(id,songId,fingerprint,cover.type().toString(),decoded.getWidth(),decoded.getHeight(),time);
            });
            return new SingleReviewDtos.Asset(id,cover.type().toString(),decoded.getWidth(),decoded.getHeight(),time);
        } catch (ResponseStatusException error) { remove(target); if(error.getStatusCode().value()==404) throw bad("图片内容无效，请上传可解码的 JPEG 或 PNG"); throw error; }
        catch(Exception error) { remove(target); throw bad("封面上传失败，请检查图片或存储权限后重试"); }
        finally { if(temp!=null) remove(temp); }
    }
    public MetadataCoverService.Cover image(String id) {
        var rows=mapper.asset(id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"封面资源不存在或已清理");
        try { var file=path(id); if(Files.isSymbolicLink(file)) throw new IOException();
            try(var in=Files.newInputStream(file)) { return MetadataCoverService.validate(in.readNBytes(2_000_001)); }
        } catch(IOException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"封面文件不可用"); }
    }
    private Path path(String id) {
        if(id==null || !id.matches("[a-fA-F0-9-]{36}")) throw bad("封面资源标识无效"); return root.resolve(id+".image");
    }
    private static void remove(Path path) { try { Files.deleteIfExists(path); } catch(IOException ignored) {} }
    @Scheduled(fixedDelay=3600000,initialDelay=60000)
    public void cleanup() {
        String before=Instant.now().minus(Duration.ofDays(7)).toString();
        tx.executeWithoutResult(s -> { metadata.lock();
            for(var row:mapper.oldAssets(before)) { String id=row.get("id").toString();
                if(mapper.references("%asset:"+id+"%") == 0) { remove(path(id)); mapper.assetDelete(id); }
            }
            mapper.pruneReceipts(before); metadata.pruneCandidates(before);
        });
        // Recover orphan files left by a process crash before the database commit.
        if(Files.isDirectory(root)) try(var files=Files.list(root)) {
            for(Path file:files.limit(1000).toList()) {
                if(Files.isSymbolicLink(file) || Files.getLastModifiedTime(file).toInstant().isAfter(Instant.parse(before))) continue;
                String name=file.getFileName().toString();
                if(name.matches("[a-fA-F0-9-]{36}\\.image") && mapper.asset(name.substring(0,36)).isEmpty()) remove(file);
                else if(name.startsWith("upload-") && name.endsWith(".tmp")) remove(file);
            }
        } catch(IOException ignored) { }
    }
}
