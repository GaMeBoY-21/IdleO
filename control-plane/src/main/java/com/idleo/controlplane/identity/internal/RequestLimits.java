package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The renter's request limit (technical design "Limits", ADR 0025): {@code limits.requests-per-minute} for each
 * signed-in person, counted in Valkey in fixed one-minute windows, and the same per address for calls that need no
 * sign-in. Starting a CLI sign-in is limited further, {@code limits.cli-logins-per-hour} per address. Over a limit,
 * 429 {@code rate-limited} with {@code Retry-After}, the seconds left in the window.
 *
 * <p>A session's event stream is one request however long it stays open, and its logs count once: the read that
 * opens them, without a cursor; the reads that follow, with one, are free.
 *
 * <p>Valkey holds only what may expire, and is never required (CLAUDE.md): when it can't be reached the request
 * goes ahead, and a warning is logged.
 */
class RequestLimits implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestLimits.class);
    private static final Pattern LOGS = Pattern.compile("/v1/sessions/[^/]+/logs");
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
        var path = request.getRequestURI();
        if (LOGS.matcher(path).matches() && request.getParameter("cursor") != null) {
            return true;
        }
        var now = Instant.now(clock);
        var who = who(request);
        check("requests:" + who, MINUTE, limits.requestsPerMinute(), now);
        if (request.getMethod().equals("POST") && path.equals("/v1/cli-logins")) {
            check("cli-logins:ip:" + request.getRemoteAddr(), HOUR, limits.cliLoginsPerHour(), now);
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
