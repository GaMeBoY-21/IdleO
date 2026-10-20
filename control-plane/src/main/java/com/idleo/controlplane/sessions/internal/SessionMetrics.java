package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Sessions' meters (ADR 0036), each counted once its change has committed:
 *
 * <ul>
 *   <li>{@code sessions.started}: sessions that reached minute 0;
 *   <li>{@code sessions.resumed}: later attempts that ran, after a lost host;
 *   <li>{@code sessions.ended}: sessions that reached a final state, by {@code state} and {@code reason}, the code
 *       its ending is told by (api/endings.json): succeeded is {@code state=COMPLETED}, failed by code is
 *       {@code state=FAILED} by {@code reason}, stopped is the reason a renter, an operator or credit gave;
 *   <li>{@code sessions.time-to-gpu}: from booking to a host accepting its first offer;
 *   <li>{@code sessions.time-to-first-log}: from booking to the first log chunk of its first attempt.
 * </ul>
 */
@Component
class SessionMetrics {

    /** A reason is a code; anything else (never expected) is counted as {@code other}, so it can't add series. */
    private static final Pattern CODE = Pattern.compile("[a-z][a-z_]{0,39}");

    private final MeterRegistry registry;
    private final Timer timeToGpu;
    private final Timer timeToFirstLog;

    SessionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.timeToGpu = Timer.builder("sessions.time-to-gpu")
                .description("From booking to a host accepting the session's first offer")
                .register(registry);
        this.timeToFirstLog = Timer.builder("sessions.time-to-first-log")
                .description("From booking to the first log chunk of the session's first attempt")
                .register(registry);
    }

    void ended(SessionState state, String reason) {
        var code = CODE.matcher(reason).matches() ? reason : "other";
        AfterCommit.run(() -> registry.counter("sessions.ended", "state", state.name(), "reason", code)
                .increment());
    }

    void started() {
        AfterCommit.run(() -> registry.counter("sessions.started").increment());
    }

    void resumed() {
        AfterCommit.run(() -> registry.counter("sessions.resumed").increment());
    }

    void gpuFound(Duration sinceBooking) {
        AfterCommit.run(() -> timeToGpu.record(sinceBooking));
    }

    void firstLog(Duration sinceBooking) {
        AfterCommit.run(() -> timeToFirstLog.record(sinceBooking));
    }
}
