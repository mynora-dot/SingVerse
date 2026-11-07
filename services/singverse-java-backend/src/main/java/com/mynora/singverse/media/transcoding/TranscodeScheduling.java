package com.mynora.singverse.media.transcoding;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TranscodeScheduling {
    @Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor originalPrefetchExecutor() {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("original-prefetch-"); executor.setAwaitTerminationSeconds(2);
        return executor;
    }
    @Bean
    ThreadPoolTaskScheduler audioScheduler() { var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("audio-");return scheduler; }

    @Bean
    ThreadPoolTaskScheduler transcodeScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("transcode-");
        return scheduler;
    }
}
