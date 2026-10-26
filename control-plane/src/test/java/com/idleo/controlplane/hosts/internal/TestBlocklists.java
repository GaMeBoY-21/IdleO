package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.TestReleaseKeys;
import java.nio.charset.StandardCharsets;
import org.springframework.context.ApplicationContext;

/**
 * The abuse blocklist in the tests' shared context (ADR 0041): the feed only ever takes a list as new as the one it holds,
 * so every test class publishes the same first list, byte for byte, and only {@link BlocklistDeliveryTests} goes past it.
 */
public final class TestBlocklists {

    /** The first list the tests publish. */
    public static final String FIRST = "2026-10-25T09:30:00Z";

    private TestBlocklists() {}

    /** A list of {@code version} naming nothing. */
    static byte[] list(String version) {
        return "{\"version\":\"%s\",\"names\":[],\"hashes\":[],\"pools\":[]}"
                .formatted(version)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** A list is cached: the first one, unless a newer one already is. */
    public static void ensureOne(ApplicationContext context) {
        var file = list(FIRST);
        context.getBean(AgentReleaseFeed.class)
                .acceptBlocklist(file, TestReleaseKeys.get().sign(file));
    }
}
