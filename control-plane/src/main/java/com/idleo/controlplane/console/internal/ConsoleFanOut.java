package com.idleo.controlplane.console.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Asks every module at the same time, each within a limit (ADR 0052): one that's slow costs its own part, nothing else. */
@Component
class ConsoleFanOut {

    private static final Logger log = LoggerFactory.getLogger(ConsoleFanOut.class);

    /** What came back by name, in the order asked, and the names that didn't answer in time. */
    record Answers<T>(Map<String, T> answered, List<String> unanswered) {}

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    <T> Answers<T> ask(Map<String, Callable<T>> questions, Duration limit) {
        var asked = new LinkedHashMap<String, Future<T>>();
        questions.forEach((name, question) -> asked.put(name, threads.submit(question)));
        long deadline = System.nanoTime() + limit.toNanos();
        var answered = new LinkedHashMap<String, T>();
        var unanswered = new ArrayList<String>();
        asked.forEach((name, answer) -> {
            try {
                answered.put(name, answer.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException | ExecutionException failed) {
                answer.cancel(true);
                unanswered.add(name);
                log.warn(
                        "The console's {} didn't answer: {}",
                        name,
                        failed.getClass().getSimpleName());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                answer.cancel(true);
                unanswered.add(name);
            }
        });
        return new Answers<>(answered, List.copyOf(unanswered));
    }
}
