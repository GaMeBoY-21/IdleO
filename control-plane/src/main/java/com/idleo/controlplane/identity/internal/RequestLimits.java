package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The renter's request limit (technical design "Limits", ADR 0025): {@code limits.requests-per-minute} for each
 * signed-in person, counted in Valkey in fixed one-minute windows, and the same per address for calls that need no
 * sign-in. Starting a CLI sign-in is limited further, {@code limits.cli-logins-per-hour} per address, and asking to
 * link a host the same way, {@code limits.host-links-per-hour}. Over a limit,
 * 429 {@code rate-limited} with {@code Retry-After}, the seconds left in the window.
 *
 * <p>A session's event stream is one request however long it stays open. Every read of a session's logs counts, with
 * a cursor or without, against a limit of its own, {@code limits.log-reads-per-minute} (120), so following a live log
 * never uses up the person's other requests, and nothing is free. Real visits' vitals ({@code POST /v1/vitals}) count
 * per address against {@code limits.vitals-per-minute} (30), apart from everything else (ADR 0036).
 *
 * <p>Which call a request is comes from the route it reached ({@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}),
 * never from the spelling of its path, so a percent-encoded path is counted like the plain one.
 *
 * <p>Valkey holds only what may expire, and is never required (CLAUDE.md): when it can't be reached the request
 * goes ahead, and a warning is logged.
 */
class RequestLimits implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestLimits.class);
    private static final String LOGS = "/v1/sessions/{id}/logs";
    private static final String CLI_LOGINS = "/v1/cli-logins";
    private static final String HOST_LINKS = "/v1/host-links";
    private static final String VITALS = "/v1/vitals";
    private static final String SUPPORT_TICKETS = "/v1/support/tickets";
    private static final String SUPPORT_REPLIES = "/v1/support/tickets/{id}/messages";
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final RequestLimitsProperties limits;
    private final Clock clock;

    RequestLimits(StringRedisTemplate redis, RequestLimitsProperties limits, Clock clock) {
        this.redis = redis;
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // An event stream is dispatched again when it ends (Servlet async); it was counted when it opened.
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        var route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern
                ? pattern
                : "";
        var now = Instant.now(clock);
        var who = who(request);
        if (route.equals(LOGS)) {
            check("log-reads:" + who, MINUTE, limits.logReadsPerMinute(), now);
        } else if (route.equals(VITALS)) {
            check("vitals:ip:" + request.getRemoteAddr(), MINUTE, limits.vitalsPerMinute(), now);
        } else {
            check("requests:" + who, MINUTE, limits.requestsPerMinute(), now);
        }
        if (request.getMethod().equals("POST") && route.equals(CLI_LOGINS)) {
            check("cli-logins:ip:" + request.getRemoteAddr(), HOUR, limits.cliLoginsPerHour(), now);
        }
        if (request.getMethod().equals("POST") && (route.equals(SUPPORT_TICKETS) || route.equals(SUPPORT_REPLIES))) {
            check("support:" + who, HOUR, limits.supportMessagesPerHour(), now);
        }
        if (request.getMethod().equals("POST") && route.equals(HOST_LINKS)) {
            check("host-links:ip:" + request.getRemoteAddr(), HOUR, limits.hostLinksPerHour(), now);
        }
        return true;
    }

    private static String who(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return "user:" + user.id();
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** Counts this request in its window; over the limit, a 429 saying when the window ends. */
    private void check(String counter, Duration window, int limit, Instant now) {
        long windowStart = now.toEpochMilli() / window.toMillis();
        var key = "limits:" + counter + ":" + windowStart;
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, window.plusSeconds(5));
            }
        } catch (DataAccessException valkeyDown) {
            log.warn("Request limits not checked: Valkey can't be reached ({})", valkeyDown.getMessage());
            return;
        }
        if (count != null && count > limit) {
            var reset = Instant.ofEpochMilli((windowStart + 1) * window.toMillis());
            throw ApiProblem.of(
                            Problem.RATE_LIMITED,
                            "More than " + limit + " requests in " + (window.equals(MINUTE) ? "a minute" : "an hour")
                                    + ". Wait, then try again.")
                    .retryAfter(Duration.between(now, reset));
        }
    }
}
