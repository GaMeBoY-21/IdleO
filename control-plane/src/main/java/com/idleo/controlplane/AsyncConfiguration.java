package com.idleo.controlplane;

import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Event listeners that do slow work (matching a session to a host) run off the publishing thread, on a virtual
 * thread each (ADR 0015). Never in an operator command.
 *
 * <p>The executor is declared here, by the name {@code @Async} looks for, because the scheduler's thread pool
 * (SchedulingConfiguration) is an executor too: without this, Spring Boot steps its own aside and every async
 * listener queues behind the scheduled jobs on those few threads (SchedulingTests).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@Profile("!ops")
class AsyncConfiguration {

    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    SimpleAsyncTaskExecutor applicationTaskExecutor(SimpleAsyncTaskExecutorBuilder builder) {
        return builder.build();
    }
}
