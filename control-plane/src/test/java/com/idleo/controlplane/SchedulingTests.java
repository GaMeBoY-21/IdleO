package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * One scheduled job that hangs never stops the others (SchedulingConfiguration): the lapsed-lease sweep, offer expiry
 * and metering keep running while, say, a call to a stalled service waits out its timeout. With every fixed-delay job
 * on one thread, a single stuck job once held them all for five and a half minutes.
 */
@ControlPlaneTest
@ExtendWith(OutputCaptureExtension.class)
@Import(SchedulingTests.AsyncProbe.class)
class SchedulingTests {

    /** Where an {@code @Async} method runs, as the booking listeners do. */
    @TestConfiguration(proxyBeanMethods = false)
    @Component
    static class AsyncProbe {

        @Async
        public CompletableFuture<Thread> where() {
            return CompletableFuture.completedFuture(Thread.currentThread());
        }
    }

    @Autowired
    AsyncProbe probe;

    @Autowired
    TaskScheduler scheduler;

    @Test
    void aStuckFixedDelayJobLeavesTheOthersRunning() throws Exception {
        var stuck = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var others = new CountDownLatch(3);
        var hanging = scheduler.scheduleWithFixedDelay(
                () -> {
                    stuck.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                Duration.ofMillis(10));
        var ticking = scheduler.scheduleWithFixedDelay(others::countDown, Duration.ofMillis(50));
        try {
            assertThat(stuck.await(5, TimeUnit.SECONDS))
                    .as("the stuck job started")
                    .isTrue();
            assertThat(others.await(5, TimeUnit.SECONDS))
                    .as("another fixed-delay job ran three times while the first was stuck")
                    .isTrue();
        } finally {
            release.countDown();
            hanging.cancel(true);
            ticking.cancel(true);
        }
    }

    @Test
    void asyncListenersNeverWaitForTheSchedulersThreads() throws Exception {
        // Every one of the scheduler's threads stuck at once (spring.task.scheduling.pool.size).
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(8);
        var stuck = new ArrayList<ScheduledFuture<?>>();
        for (int i = 0; i < 8; i++) {
            stuck.add(scheduler.scheduleWithFixedDelay(
                    () -> {
                        started.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    Duration.ofSeconds(1)));
        }
        try {
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("all eight scheduler threads busy")
                    .isTrue();
            var thread = probe.where().get(5, TimeUnit.SECONDS);
            assertThat(thread.isVirtual()).as("an async listener's thread").isTrue();
            assertThat(thread.getName()).doesNotStartWith("scheduling-");
        } finally {
            release.countDown();
            stuck.forEach(job -> job.cancel(true));
        }
    }

    @Test
    void aRunLongerThanTheLimitIsLoggedWithTheJobsName(CapturedOutput output) {
        Runnable job = new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public String toString() {
                return "the-slow-job";
            }
        };
        SchedulingConfiguration.timed(job, Duration.ofMillis(10)).run();
        SchedulingConfiguration.timed(() -> {}, Duration.ofSeconds(10)).run();

        assertThat(output.getOut()).containsOnlyOnce("Scheduled job the-slow-job took");
    }
}
