package com.idleo.controlplane.hosts.internal;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Linking a host by approval (ADR 0032). {@code hosts.link.web-app-url} is the web app, whose {@code /link/<id>/} page
 * the owner approves on (the prod profile refuses one that is plain HTTP or on this machine); {@code ttl} is how long a
 * link can be approved, and {@code poll-interval} how often the agent is asked to poll. {@code hosts.auto-activate}
 * is false during the invite-only pilot: an approved host is created pending, and an admin activates it. After the
 * pilot, a host whose self-test passed goes live by itself; the self-test comes later, so for now it only exists.
 */
@ConfigurationProperties("hosts")
record HostLinkProperties(
        @DefaultValue Link link, @DefaultValue("false") boolean autoActivate) {

    record Link(
            @NotNull URI webAppUrl,
            @DefaultValue("10m") Duration ttl,
            @DefaultValue("3s") Duration pollInterval) {}
}
