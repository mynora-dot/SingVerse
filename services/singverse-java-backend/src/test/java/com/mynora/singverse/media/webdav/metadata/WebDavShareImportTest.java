package com.mynora.singverse.media.webdav.metadata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.dto.MediaSourceDtos.Input;
import com.mynora.singverse.media.service.FilenameParserConfigService;
import com.mynora.singverse.media.service.MediaSourceService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Real receipt/chunk transactions and SQL; full-source preparation is independent of chunk count. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "singverse.metadata.worker-enabled=false", "singverse.metadata.file-worker-enabled=false",
        "singverse.webdav.metadata-worker-enabled=false", "singverse.webdav.share-worker-enabled=false",
        "singverse.classification.worker-enabled=false"})
@ActiveProfiles("sqlite")
@DirtiesContext
class WebDavShareImportTest {
    @TempDir static Path root;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("singverse.transcoding.cache-directory", () -> root.resolve("cache").toString());
        properties.add("singverse.transcoding.directory", () -> root.resolve("versions").toString());
        properties.add("singverse.audio.directory", () -> root.resolve("audio").toString());
        properties.add("singverse.metadata.cache-path", () -> root.resolve("covers").toString());
        properties.add("singverse.sqlite.path", () -> root.resolve("imports.db").toString());
        properties.add("singverse.auth.key-file", () -> root.resolve("auth.key").toString());
        properties.add("singverse.webdav.metadata-path", () -> root.resolve("metadata").toString());
    }
    @Autowired WebDavShareJobs jobs;
    @Autowired WebDavShareService sharing;
    @Autowired MediaSourceService sources;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @MockitoSpyBean WebDavMetadataMapper metadata;
    @MockitoSpyBean FilenameParserConfigService profiles;

    String source() {
        return sources.create(new Input("Import fixture", "WEBDAV", "http://127.0.0.1:18090/" + java.util.UUID.randomUUID() + "/",
                "", null, "default", false, null)).id();
    }
    byte[] bundle(int size) throws Exception {
        var manifest = json.createObjectNode().put("format", "singverse-media-index").put("version", 2);
        var entries = manifest.putArray("entries");
        for (int index = 0; index < size; index++) {
            var entry = entries.addObject().put("path", "Song-Artist-" + index + ".mp3").put("size", 1024);
            entry.putObject("metadata").putObject("tags").put("title", "Song " + index).putArray("artists").add("Artist");
        }
        return sharing.pack(manifest);
    }

    @Test void receiptImportPreparesOnceWhileEveryChunkRefreshesItsLockedProfile() throws Exception {
        String source = source(); byte[] bundle = bundle(250);
        clearInvocations(metadata, profiles);
        assertThat(jobs.importPart(source, bundle)).containsEntry("imported", 250).containsEntry("processed", 250);
        verify(metadata, times(1)).bootstrap(argThat(args -> args.get("songId") == null && source.equals(args.get("sourceId"))));
        verify(metadata, times(250)).bootstrap(argThat(args -> args.get("songId") != null && source.equals(args.get("sourceId"))));
        verify(profiles, times(4)).lockBindings();
        verify(profiles, times(3)).savedProfile("default");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM media_song WHERE source_id=?", Integer.class, source)).isEqualTo(250);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM webdav_resource WHERE source_id=? AND state='IMPORTED'", Integer.class, source)).isEqualTo(250);

        clearInvocations(metadata, profiles);
        assertThat(jobs.importPart(source, bundle)).containsEntry("imported", 250).containsEntry("processed", 250);
        verify(metadata, never()).bootstrap(anyMap());
        verify(profiles, never()).lockBindings();
        verify(profiles, never()).savedProfile(anyString());
        assertThat(database.queryForObject("SELECT COUNT(*) FROM media_song WHERE source_id=?", Integer.class, source)).isEqualTo(250);
    }

    @Test void standaloneBundleImportStillPreparesTheSourceAndInstallsEachNewResource() throws Exception {
        String source = source(); byte[] bundle = bundle(2);
        clearInvocations(metadata, profiles);
        assertThat(sharing.importBundle(source, bundle)).containsEntry("imported", 2).containsEntry("skipped", 0);
        verify(metadata, times(1)).bootstrap(argThat(args -> args.get("songId") == null && source.equals(args.get("sourceId"))));
        verify(metadata, times(2)).bootstrap(argThat(args -> args.get("songId") != null && source.equals(args.get("sourceId"))));
        verify(profiles, times(1)).lockBindings();
        verify(profiles, times(1)).savedProfile("default");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM webdav_resource WHERE source_id=? AND state='IMPORTED'", Integer.class, source)).isEqualTo(2);
    }

    @Test void preparedImportRequiresTheReceiptTransaction() throws Exception {
        String source = source(); byte[] bundle = bundle(1);
        clearInvocations(metadata, profiles);
        assertThatThrownBy(() -> sharing.importPreparedBundle(source, bundle, "not-used-without-transaction"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(metadata, profiles);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM media_song WHERE source_id=?", Integer.class, source)).isZero();
    }
}
