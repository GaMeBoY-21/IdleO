package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.TaskScheduler;

/**
 * One scheduled job that hangs never stops the others (SchedulingConfiguration): the lapsed-lease sweep, offer expiry
 * and metering keep running while, say, a call to a stalled service waits out its timeout. With every fixed-delay job
 * on one thread, a single stuck job once held them all for five and a half minutes.
 */
@ControlPlaneTest
@ExtendWith(OutputCaptureExtension.class)
class SchedulingTests {

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
