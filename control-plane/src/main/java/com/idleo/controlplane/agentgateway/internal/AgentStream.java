package com.idleo.controlplane.agentgateway.internal;

import com.google.protobuf.Duration;
import com.idleo.controlplane.hosts.HostListings;
import gpu.agent.v1.AgentGateway.Ack;
import gpu.agent.v1.AgentGateway.AgentPolicy;
import gpu.agent.v1.AgentGateway.OpenStreamRequest;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.AgentGateway.Welcome;
import gpu.agent.v1.Telemetry.Heartbeat;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One agent's OpenStream (ADR 0011). The first message must be Hello within the hello timeout; the
 * reply is Welcome. Heartbeats then keep the host's presence alive. A newer stream for the same host
 * replaces this one. Every outbound call is synchronized: the gRPC thread, the hello timer and a
 * replacing stream can all touch it.
 */
final class AgentStream implements StreamObserver<OpenStreamRequest> {

    private static final Logger log = LoggerFactory.getLogger(AgentStream.class);

    private enum State {
        AWAITING_HELLO,
        OPEN,
        CLOSED
    }

    private final UUID hostId;
    private final UUID connectionId;
    private final ServerCallStreamObserver<OpenStreamResponse> responses;
    private final AgentStreamServices services;
    private final Instant connectedAt;

    private State state = State.AWAITING_HELLO;
    private @Nullable ScheduledFuture<?> helloTimer;
    private Set<String> gpuUuids = Set.of();
    private String agentVersion = "";
    private boolean updateRequired;
    private @Nullable Instant lastSeenRecordedAt;

    AgentStream(
            UUID hostId,
            UUID connectionId,
            ServerCallStreamObserver<OpenStreamResponse> responses,
            AgentStreamServices services) {
        this.hostId = hostId;
        this.connectionId = connectionId;
        this.responses = responses;
        this.services = services;
        this.connectedAt = Instant.now(services.clock());
    }

    UUID hostId() {
        return hostId;
    }

    synchronized void start() {
        var timeout = services.properties().helloTimeout();
        helloTimer = services.scheduler()
                .schedule(
                        () -> close(
                                Status.DEADLINE_EXCEEDED.withDescription(
                                        "No Hello within " + timeout.toSeconds() + " seconds."),
                                "hello_timeout"),
                        timeout.toMillis(),
                        TimeUnit.MILLISECONDS);
    }

    @Override
    public void onNext(OpenStreamRequest request) {
        var body = request.getBodyCase();
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.AWAITING_HELLO && body != OpenStreamRequest.BodyCase.HELLO) {
                close(Status.INVALID_ARGUMENT.withDescription("The first message must be Hello."), "protocol_error");
                return;
            }
        }
        switch (body) {
            case HELLO -> onHello(request);
            case HEARTBEAT -> onHeartbeat(request.getHeartbeat());
            case OFFER_REPLY -> onOfferReply(request);
            case ACK -> onAck(request.getAck().getAckedMessageId());
            default -> services.metrics().unhandledMessage(body.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private void onHello(OpenStreamRequest request) {
        synchronized (this) {
            if (state != State.AWAITING_HELLO) {
                close(Status.INVALID_ARGUMENT.withDescription("Hello was already received."), "protocol_error");
                return;
            }
        }
        switch (AgentMessages.validate(request.getHello(), services.properties().allowDevelopmentHosts())) {
            case AgentMessages.HelloResult.Invalid invalid -> {
                log.warn("Invalid Hello from host {}: {}", hostId, invalid.reason());
                close(
                        Status.INVALID_ARGUMENT.withDescription("Invalid Hello: " + invalid.reason() + "."),
                        "invalid_hello");
            }
            case AgentMessages.HelloResult.Valid valid -> welcome(valid.hello());
        }
    }

    private void welcome(AgentMessages.ValidHello hello) {
        services.reports().recordHello(hostId, hello.hello());
        var properties = services.properties();
        // The owner's listing sets the temperature cap (ADR 0015); config is the default until they list.
        int tempCap = services.listings()
                .listing(hostId)
                .map(HostListings.Listing::gpuTempCapC)
                .orElse(properties.gpuTempCapC());
        synchronized (this) {
            if (state != State.AWAITING_HELLO) {
                return;
            }
            if (helloTimer != null) {
                helloTimer.cancel(false);
            }
            gpuUuids = hello.gpuUuids();
            agentVersion = hello.hello().agentVersion();
            updateRequired = hello.version().compareTo(services.minimumAgentVersion()) < 0;
            lastSeenRecordedAt = Instant.now(services.clock());
            state = State.OPEN;
        }
        var replaced = services.connected().register(this);
        if (replaced != null && replaced != this) {
            replaced.close(Status.ABORTED.withDescription("Replaced by a newer connection."), "replaced");
        }
        try {
            services.presence().register(snapshot(false, 0, List.of()));
        } catch (RuntimeException valkeyDown) {
            log.warn("Could not record presence for host {}: {}", hostId, valkeyDown.toString());
        }
        send(OpenStreamResponse.newBuilder()
                .setMessageId(services.ids().newId().toString())
                .setWelcome(Welcome.newBuilder()
                        .setPolicy(AgentPolicy.newBuilder()
                                .setHeartbeatInterval(Duration.newBuilder()
                                        .setSeconds(
                                                properties.heartbeatInterval().toSeconds()))
                                .setGpuTempCapC(tempCap)
                                .setMaxConcurrentSessions(properties.maxConcurrentSessions()))
                        .setMinimumAgentVersion(properties.minimumAgentVersion()))
                .build());
        // Anything queued while the agent was away (ADR 0015).
        var delivery = services.delivery();
        if (delivery != null) {
            try {
                delivery.deliverPending(hostId);
            } catch (RuntimeException e) {
                log.warn("Couldn't deliver host {}'s waiting commands after Welcome; the sweep will", hostId, e);
            }
        }
    }

    /**
     * An answer to an offer (ADR 0015): handed to the scheduler through the {@code OfferReplies} port, then
     * acked. If the scheduler can't take it now, there's no ack, and the agent resends.
     */
    private void onOfferReply(OpenStreamRequest request) {
        var reply = request.getOfferReply();
        UUID offerId;
        try {
            offerId = UUID.fromString(reply.getOfferId());
        } catch (IllegalArgumentException e) {
            services.metrics().unhandledMessage("invalid_offer_reply");
            return;
        }
        var reason = reply.getAccepted()
                ? ""
                : reply.getDeclineReason()
                        .name()
                        .replaceFirst("^OFFER_DECLINE_REASON_", "")
                        .toLowerCase(Locale.ROOT);
        var handler = services.offerReplies();
        if (handler == null) {
            services.metrics().unhandledMessage("offer_reply");
            return;
        }
        try {
            handler.onReply(hostId, offerId, reply.getAccepted(), reason);
        } catch (RuntimeException e) {
            log.warn("Couldn't record host {}'s reply to offer {}; it will resend", hostId, offerId, e);
            return;
        }
        send(OpenStreamResponse.newBuilder()
                .setMessageId(services.ids().newId().toString())
                .setAck(Ack.newBuilder().setAckedMessageId(request.getMessageId()))
                .build());
    }

    private void onAck(String ackedMessageId) {
        try {
            services.commands().acked(hostId, UUID.fromString(ackedMessageId));
        } catch (IllegalArgumentException e) {
            services.metrics().unhandledMessage("invalid_ack");
        }
    }

    /** Sends a queued command; false if the stream has closed. */
    synchronized boolean deliver(OpenStreamResponse message) {
        if (state != State.OPEN) {
            return false;
        }
        responses.onNext(message);
        return true;
    }

    private void onHeartbeat(Heartbeat heartbeat) {
        var problem = AgentMessages.invalidHeartbeat(heartbeat, gpuUuids());
        if (problem.isPresent()) {
            services.metrics().invalidHeartbeat(problem.get());
            return;
        }
        var readings = heartbeat.getGpusList().stream()
                .<Map<String, Object>>map(gpu -> {
                    var reading = new LinkedHashMap<String, Object>();
                    reading.put("uuid", gpu.getUuid());
                    reading.put("temp_c", gpu.getTempC());
                    reading.put("util_pct", gpu.getUtilPct());
                    reading.put("vram_used_mb", gpu.getVramUsedMb());
                    if (gpu.hasPowerW()) {
                        reading.put("power_w", gpu.getPowerW());
                    }
                    return reading;
                })
                .toList();
        try {
            if (!services.presence()
                    .refresh(snapshot(heartbeat.getOwnerActive(), heartbeat.getNetUpKbps(), readings))) {
                // Another connection, perhaps on another instance, owns the host now.
                close(Status.ABORTED.withDescription("Replaced by a newer connection."), "replaced");
                return;
            }
            services.metrics().heartbeat("recorded");
        } catch (RuntimeException valkeyDown) {
            services.metrics().heartbeat("not_recorded");
        }
        recordSeenAtMostOncePerMinute();
    }

    private void recordSeenAtMostOncePerMinute() {
        var now = Instant.now(services.clock());
        synchronized (this) {
            if (lastSeenRecordedAt != null && now.isBefore(lastSeenRecordedAt.plusSeconds(60))) {
                return;
            }
            lastSeenRecordedAt = now;
        }
        services.reports().recordSeen(hostId);
    }

    @Override
    public void onError(Throwable error) {
        var status = Status.fromThrowable(error);
        cleanUp(status.getCode() == Status.Code.CANCELLED ? "cancelled" : "client_error");
    }

    @Override
    public void onCompleted() {
        if (cleanUp("client_closed")) {
            synchronized (this) {
                responses.onCompleted();
            }
        }
    }

    /** Ends the stream with {@code status}, unless it already ended. */
    void close(Status status, String reason) {
        if (cleanUp(reason)) {
            synchronized (this) {
                responses.onError(status.asRuntimeException());
            }
        }
    }

    /** Marks the stream closed and removes its presence safely; false if it was already closed. */
    private boolean cleanUp(String reason) {
        synchronized (this) {
            if (state == State.CLOSED) {
                return false;
            }
            state = State.CLOSED;
            if (helloTimer != null) {
                helloTimer.cancel(false);
            }
        }
        services.connected().remove(this);
        try {
            // Compare-and-delete: a newer stream's presence is left alone.
            services.presence().remove(hostId, connectionId);
        } catch (RuntimeException valkeyDown) {
            log.warn("Could not remove presence for host {}: {}", hostId, valkeyDown.toString());
        }
        services.metrics().streamClosed(reason);
        return true;
    }

    private synchronized void send(OpenStreamResponse response) {
        if (state != State.CLOSED) {
            responses.onNext(response);
        }
    }

    private synchronized Set<String> gpuUuids() {
        return gpuUuids;
    }

    private synchronized Presence.Snapshot snapshot(
            boolean ownerActive, int netUpKbps, List<Map<String, Object>> readings) {
        return new Presence.Snapshot(
                hostId,
                connectionId,
                services.instanceId().value(),
                connectedAt,
                agentVersion,
                updateRequired,
                ownerActive,
                netUpKbps,
                services.json().writeValueAsString(readings),
                Instant.now(services.clock()));
    }
}
