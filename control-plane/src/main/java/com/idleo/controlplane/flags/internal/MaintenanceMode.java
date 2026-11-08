package com.idleo.controlplane.flags.internal;

import com.idleo.controlplane.Flag;
import com.idleo.controlplane.Flags;
import com.idleo.controlplane.OpenDuringMaintenance;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Maintenance mode (ADR 0056): while {@link Flag#MAINTENANCE} is paused-on, every {@code /v1} write is 503 {@code
 * maintenance} with Retry-After, but the {@link OpenDuringMaintenance} handlers. Decided after routing, by the handler
 * the request reached (its mapping's pattern), so no spelling of a path gets past it; a write that reaches no handler
 * changes nothing and meets its 404. Reads go on as usual; {@code /admin/v1} and {@code /internal/v1} aren't the app's and aren't stopped, and
 * agents and metering speak gRPC, which never comes here.
 */
class MaintenanceMode implements HandlerInterceptor {

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    private final Flags flags;
    private final Clock clock;

    MaintenanceMode(Flags flags, Clock clock) {
        this.flags = flags;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.ERROR || READS.contains(request.getMethod())) {
            return true;
        }
        if (!(handler instanceof HandlerMethod method)
                || !(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern)
                || !pattern.startsWith("/v1/")
                || method.hasMethodAnnotation(OpenDuringMaintenance.class)) {
            return true;
        }
        flags.pause(Flag.MAINTENANCE).ifPresent(pause -> {
            throw Flags.refusal(pause, clock.instant());
        });
        return true;
    }
}
