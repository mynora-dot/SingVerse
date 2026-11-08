package com.mynora.singverse.media.audio;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AudioDispatchTest {
    @Test void wakeupsCoalesceAndCompletionDoesNotWaitForTheFallbackPoll() throws Exception {
        try(var dispatch=new AudioDispatch()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var next=new CountDownLatch(1);
            var calls=new AtomicInteger();
            dispatch.registerJobs(()->{
                if(calls.incrementAndGet()==1){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
                else next.countDown();
            });
            dispatch.jobs(0);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            for(int index=0;index<100;index++)dispatch.jobs(1000);
            dispatch.jobs(0);release.countDown();
            assertThat(next.await(500,TimeUnit.MILLISECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(2);
        }
    }
    @Test void slowNetworkMaintenanceDoesNotBlockJobsOrBatches() throws Exception {
        try(var dispatch=new AudioDispatch()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ready=new CountDownLatch(2);
            dispatch.background("discover",()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            try{
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                dispatch.registerJobs(ready::countDown);dispatch.registerBatches(ready::countDown);
                dispatch.jobs(0);dispatch.batches();assertThat(ready.await(500,TimeUnit.MILLISECONDS)).isTrue();
            }finally{release.countDown();}
        }
    }
}
