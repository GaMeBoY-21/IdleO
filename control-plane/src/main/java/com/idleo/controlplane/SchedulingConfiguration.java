package com.idleo.controlplane;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduled jobs run in the server, never in an operator command (the ops profile, ADR 0014).
 *
 * <p>They run on a pool of threads ({@code spring.task.scheduling.pool.size}), not the one thread Spring gives every
 * fixed-delay job when virtual threads are on: there, a single job waiting on a stalled service held the lapsed-lease
 * sweep, offer expiry and metering back with it, for minutes (SchedulingTests). Each job still never overlaps itself.
 * A run that takes longer than {@link #SLOW} is logged with the job's name, so one that hangs names itself.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!ops")
class SchedulingConfiguration {

    /** Far beyond any job's normal run (they take milliseconds), well inside the leases' two minutes. */
    static final Duration SLOW = Duration.ofSeconds(20);

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfiguration.class);

    @Bean
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        var scheduler = builder.build();
        scheduler.setTaskDecorator(job -> timed(job, SLOW));
        return scheduler;
    }

    /** {@code job}, logging a run that took longer than {@code slow}; a duration, so the JVM's monotonic time. */
    static Runnable timed(Runnable job, Duration slow) {
        return () -> {
            long started = System.nanoTime();
            try {
                job.run();
            } finally {
                var took = Duration.ofNanos(System.nanoTime() - started);
                if (took.compareTo(slow) > 0) {
                    log.warn(
                            "Scheduled job {} took {} ms, holding one of the scheduler's threads",
                            job,
                            took.toMillis());
                }
            }
        };
    }
}
