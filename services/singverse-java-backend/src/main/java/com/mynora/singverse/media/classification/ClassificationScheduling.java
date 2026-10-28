package com.mynora.singverse.media.classification;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration(proxyBeanMethods=false)
public class ClassificationScheduling {
    @Bean
    public ThreadPoolTaskScheduler classificationScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("classification-");scheduler.setRemoveOnCancelPolicy(true);return scheduler;
    }
}
