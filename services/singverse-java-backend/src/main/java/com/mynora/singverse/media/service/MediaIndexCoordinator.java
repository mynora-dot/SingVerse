package com.mynora.singverse.media.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes a complete local scan and publication of a downloaded song for the same source. */
public final class MediaIndexCoordinator {
    private static final ConcurrentHashMap<String,ReentrantLock> LOCKS=new ConcurrentHashMap<>();
    private MediaIndexCoordinator() {}
    public static <T> T locked(String sourceId,Supplier<T> work) {
        var lock=LOCKS.computeIfAbsent(sourceId,key->new ReentrantLock(true));
        lock.lock();
        try { return work.get(); } finally { lock.unlock(); }
    }
}
