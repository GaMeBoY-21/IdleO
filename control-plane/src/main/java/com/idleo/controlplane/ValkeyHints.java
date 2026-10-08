package com.idleo.controlplane;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one way the control plane sends hints between instances (ADR 0015, ADR 0019): after a change commits, a message
 * on a Valkey channel says something changed, and listeners act on it at once. A hint is only ever an optimisation:
 * every listener also sweeps on its own timer, so a lost hint, or Valkey being down, costs seconds, never a change.
 * Valkey stays out of readiness, and an instance starts whether or not it answers (ADR 0011).
 */
public final class ValkeyHints {

    private static final Logger log = LoggerFactory.getLogger(ValkeyHints.class);
    private static final Duration RETRY = Duration.ofSeconds(5);

    private ValkeyHints() {}

    /**
     * Runs {@code hint} once the current transaction commits, or now if there's none. Never inside the transaction:
     * a hint for a change that then rolls back would send listeners looking for nothing.
     */
    public static void afterCommit(Runnable hint) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    hint.run();
                }
            });
        } else {
            hint.run();
        }
    }

    /**
     * Starts {@code container}'s subscriptions in the background, retrying until Valkey answers, so Valkey being down
     * never stops an instance from starting. Register the result as a bean; the container must not start itself.
     */
    public static SmartLifecycle subscribeInBackground(RedisMessageListenerContainer container, String what) {
        container.setAutoStartup(false);
        return new SmartLifecycle() {
            private volatile boolean running;
            private volatile Thread subscriber;

            @Override
            public void start() {
                running = true;
                subscriber = Thread.ofVirtual().name(what + "-subscription").start(() -> {
                    while (running) {
                        try {
                            container.start();
                            return;
                        } catch (RuntimeException valkeyDown) {
                            log.warn(
                                    "Can't subscribe to {} yet ({}); the sweep works meanwhile",
                                    what,
                                    valkeyDown.toString());
                            try {
                                Thread.sleep(RETRY);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                });
            }

            @Override
            public void stop() {
                running = false;
                var thread = subscriber;
                if (thread != null) {
                    thread.interrupt();
                }
                container.stop();
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
