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
