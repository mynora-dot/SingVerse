package com.mynora.singverse.media.audio;

import java.util.Set;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Coalesced wakeups; network IO never runs on the shared Spring timer thread. */
@Component
public class AudioDispatch implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AudioDispatch.class);
    private final Channel jobs = new Channel("audio-jobs");
    private final Channel batches = new Channel("audio-batches");
    private final Set<String> backgroundPending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor background = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(3), runnable -> {var thread=new Thread(runnable,"audio-maintenance");thread.setDaemon(true);return thread;});

    void registerJobs(Runnable work) { jobs.work=work; }
    void registerBatches(Runnable work) { batches.work=work; }
    void jobs(long delayMillis) { jobs.wake(delayMillis); }
    void batches() { batches.wake(0); }
    void background(String key, Runnable work) {
        if (!backgroundPending.add(key)) return;
        try { background.execute(() -> {
            try {work.run();} catch(RuntimeException failure) {log.warn("Audio maintenance failed: {}", key, failure);}
            finally {backgroundPending.remove(key);}
        }); } catch(RejectedExecutionException stopping) {backgroundPending.remove(key);}
    }

    static final class Channel {
        final ScheduledThreadPoolExecutor executor;
        volatile Runnable work;
        ScheduledFuture<?> pending;
        long due;
        Channel(String name) {
            executor=new ScheduledThreadPoolExecutor(1,runnable->{var thread=new Thread(runnable,name);thread.setDaemon(true);return thread;});
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }
        synchronized void wake(long delayMillis) {
            if(work==null || executor.isShutdown())return;
            long target=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(delayMillis);
            if(pending!=null && !pending.isDone()) {
                if(due<=target)return;
                pending.cancel(false);
            }
            due=target;
            try {pending=executor.schedule(()->{
                synchronized(this){pending=null;}
                try{work.run();}catch(RuntimeException failure){log.warn("Audio dispatch failed",failure);}
            },delayMillis,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException stopping){pending=null;}
        }
    }
    @Override @jakarta.annotation.PreDestroy public void close() {
        jobs.executor.shutdownNow();batches.executor.shutdownNow();background.shutdownNow();
    }
}
