package com.mynora.singverse.media.transcoding;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TranscodeScheduling {
    @Bean
    ThreadPoolTaskScheduler transcodeScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("transcode-");
        return scheduler;
    }
}
