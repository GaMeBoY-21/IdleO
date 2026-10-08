package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ValkeyHints;
import com.idleo.controlplane.sessions.SessionUpdates;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * "Session X changed", to every instance (ADR 0019), through the command queue's Valkey pattern ({@link
 * ValkeyHints}): published after commit on every transition, settlement, checkpoint and log advance, and heard by
 * each instance's open event streams. This instance's streams are woken directly too. Only a hint: every stream
 * re-reads on its own timer, so a lost hint, or Valkey being down, costs seconds.
 */
@Component
class SessionHints implements SessionUpdates, MessageListener {

    static final String CHANNEL = "sessions:changes";

    private static final Logger log = LoggerFactory.getLogger(SessionHints.class);

    private final StringRedisTemplate redis;
    private final List<Consumer<UUID>> listeners = new CopyOnWriteArrayList<>();

    SessionHints(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void changed(UUID sessionId) {
        ValkeyHints.afterCommit(() -> {
            wake(sessionId);
            try {
                redis.convertAndSend(CHANNEL, sessionId.toString());
            } catch (RuntimeException valkeyDown) {
                log.debug("Couldn't hint that session {} changed; streams re-read anyway", sessionId, valkeyDown);
            }
        });
    }

    /** Who hears about changes: this instance's event streams. */
    void listen(Consumer<UUID> listener) {
        listeners.add(listener);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            wake(UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            log.warn("Ignored a session hint that isn't a session id");
        }
    }

    private void wake(UUID sessionId) {
        listeners.forEach(listener -> listener.accept(sessionId));
    }
}
