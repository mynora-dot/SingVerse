package com.mynora.singverse.media.artist;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Intercepts(
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class}))
public class LibraryChangeInterceptor implements Interceptor {
    private final ApplicationEventPublisher events;

    public LibraryChangeInterceptor(ApplicationEventPublisher events) {
        this.events = events;
    }

    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        String id = ((MappedStatement) invocation.getArgs()[0]).getId();
        Object parameter = invocation.getArgs()[1];
        String songId = null;
        if (parameter instanceof java.util.Map<?,?> values) {
            if (id.endsWith("media.metadata.MetadataMapper.insertApplied")
                    || id.endsWith("media.metadata.MetadataMapper.deleteApplied")
                    || id.endsWith("WebDavMetadataMapper.applyTags"))
                songId = (String) values.get("songId");
            if (id.endsWith("MediaSongMapper.insert") && values.get("song") instanceof com.mynora.singverse.media.repository.MediaSongRepository.Stored song)
                songId = song.id();
        }
        if (songId != null) {
            // Same executor/transaction: a rollback must roll back the pending file write too.
            var statement = (MappedStatement) invocation.getArgs()[0];
            var executor = (Executor) invocation.getTarget();
            var args = java.util.Map.<String,Object>of("songId", songId);
            String prefix = "com.mynora.singverse.media.metadata.MetadataFileMapper.";
            executor.update(statement.getConfiguration().getMappedStatement(prefix + "touch"), args);
            executor.update(statement.getConfiguration().getMappedStatement(prefix + "enqueue"), args);
        }
        if (id.endsWith("WebDavMetadataMapper.insertSongs") && parameter instanceof java.util.Map<?,?> values) {
            var statement = (MappedStatement) invocation.getArgs()[0];
            ((Executor) invocation.getTarget()).update(statement.getConfiguration().getMappedStatement(
                    "com.mynora.singverse.media.metadata.MetadataFileMapper.enqueueSource"), values);
        }
        if ((id.endsWith("ArtistMapper.insertOverride") || id.endsWith("ArtistMapper.deleteOverride"))
                && parameter instanceof java.util.Map<?,?> values) {
            var config = ((MappedStatement) invocation.getArgs()[0]).getConfiguration();
            var executor = (Executor) invocation.getTarget();
            String prefix = "com.mynora.singverse.media.metadata.MetadataFileMapper.";
            executor.update(config.getMappedStatement(prefix + "touchPath"), values);
            executor.update(config.getMappedStatement(prefix + "enqueuePath"), values);
        }
        if (((id.contains("MediaSongMapper.")
                                || (id.contains("MetadataMapper.")
                                        && (id.endsWith(".insertApplied")
                                                || id.endsWith(".deleteApplied")
                                                || id.endsWith(".cleanupSource")
                                                || id.endsWith(".cleanupOrphans"))))
                        && !id.endsWith(".lock")
                        && !id.contains("Settings")
                        && !id.contains("settings"))
                || id.contains("ArtistMapper.insertOverride"))
            events.publishEvent(new ArtistDtos.Changed());
        return result;
    }
}
