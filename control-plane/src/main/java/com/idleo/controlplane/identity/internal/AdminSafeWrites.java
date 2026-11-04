package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AdminRecords;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.WebUtils;

/**
 * Safe admin writes (ADR 0051), by the interceptor, for every endpoint alike. Every admin POST takes an {@code
 * Idempotency-Key}, through {@link IdempotentRequests} (one implementation), and a repeat answers as the first did. A
 * write that changes a record takes an {@code If-Match} with the version it read: none is 428, a stale one 412, and the
 * console then shows what changed. A repeat of a finished request is answered before the version is looked at, since its
 * own success changed the version.
 *
 * <p>An admin's own access (a sign-in, a step-up, their passkeys) takes no key: each carries a code or a challenge that
 * works once, and its answer can hold a session's token, which is never kept.
 */
@Component
@ConditionalOnWebApplication
class AdminSafeWrites {

    private static final String CLAIM = AdminSafeWrites.class.getName() + ".claim";
    private static final String ANSWER = AdminSafeWrites.class.getName() + ".answer";

    private final IdempotentRequests idempotency;
    private final Map<String, Function<Map<String, String>, OptionalLong>> records;

    AdminSafeWrites(IdempotentRequests idempotency, List<AdminRecords> modules) {
        this.idempotency = idempotency;
        var all = new HashMap<String, Function<Map<String, String>, OptionalLong>>();
        modules.forEach(module -> module.versions().forEach((name, read) -> {
            if (all.put(name, read) != null) {
                throw new IllegalStateException(name + " is versioned by two modules");
            }
        }));
        this.records = Map.copyOf(all);
    }

    private record Claimed(UUID userId, String key, boolean shownOnce) {}

    boolean knows(String record) {
        return records.containsKey(record);
    }

    /** The record's version now, by the endpoint's path variables. */
    OptionalLong version(String record, Map<String, String> pathVariables) {
        var read = records.get(record);
        if (read == null) {
            throw new IllegalStateException("no module versions the record " + record);
        }
        return read.apply(pathVariables);
    }

    /** Whether the request goes on to its handler: false when this already answered it. */
    boolean before(HttpServletRequest request, HttpServletResponse response, RequiresPermission declared) {
        if (!"POST".equals(request.getMethod()) || declared.value() == AdminPermission.SELF) {
            return true;
        }
        var user = ((CurrentUser)
                        SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .id();
        var claim = idempotency.claim(
                ApiKeyController.IDEMPOTENCY_TABLE,
                user,
                request.getHeader(IdempotentRequests.HEADER),
                fingerprint(request),
                declared.shownOnce());
        return switch (claim) {
            case IdempotentRequests.Claim.Replay replay -> {
                send(replay, response);
                yield false;
            }
            case IdempotentRequests.Claim.Proceed proceed -> {
                try {
                    checkVersion(request, declared);
                } catch (ApiProblem refused) {
                    idempotency.release(ApiKeyController.IDEMPOTENCY_TABLE, user, proceed.key());
                    throw refused;
                }
                request.setAttribute(CLAIM, new Claimed(user, proceed.key(), declared.shownOnce()));
                yield true;
            }
        };
    }

    /** Keeps what a claimed request answered, or gives its key back if it failed. */
    void after(HttpServletRequest request, HttpServletResponse response) {
        if (!(request.getAttribute(CLAIM) instanceof Claimed claimed)) {
            return;
        }
        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            idempotency.finish(
                    ApiKeyController.IDEMPOTENCY_TABLE,
                    claimed.userId(),
                    claimed.key(),
                    status,
                    response.getHeader(HttpHeaders.LOCATION),
                    claimed.shownOnce() || !(request.getAttribute(ANSWER) instanceof String answer) ? null : answer);
        } else {
            idempotency.release(ApiKeyController.IDEMPOTENCY_TABLE, claimed.userId(), claimed.key());
        }
    }

    /** The masked JSON this request is answering with, for a repeat to be given. */
    static void answered(String body) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ANSWER, body, RequestAttributes.SCOPE_REQUEST);
        }
    }

    private void checkVersion(HttpServletRequest request, RequiresPermission declared) {
        if (declared.record().isBlank()) {
            return;
        }
        var ifMatch = request.getHeader(HttpHeaders.IF_MATCH);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiProblem.of(Problem.VERSION_REQUIRED, "Send If-Match with the record's version as you read it.");
        }
        var current = version(declared.record(), pathVariables(request));
        if (current.isEmpty()) {
            // There's no such record: the handler says so in its own words.
            return;
        }
        var sent = ifMatch.strip().replace("\"", "");
        if (!sent.equals(String.valueOf(current.getAsLong()))) {
            throw ApiProblem.of(
                    Problem.VERSION_STALE,
                    "This record changed since you read it. Read it again and decide on what it says now.",
                    "currentVersion",
                    current.getAsLong());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        var variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables == null ? Map.of() : (Map<String, String>) variables;
    }

    /** What makes this request this one: where it's going and what it says. */
    private static byte[] fingerprint(HttpServletRequest request) {
        var readable = WebUtils.getNativeRequest(request, AdminRequestBodyFilter.ReadableBody.class);
        if (readable == null) {
            throw new IllegalStateException("an admin POST arrived without its readable body");
        }
        try {
            var where = (request.getMethod() + " " + request.getRequestURI() + "\n").getBytes(StandardCharsets.UTF_8);
            var body = readable.body();
            var all = new byte[where.length + body.length];
            System.arraycopy(where, 0, all, 0, where.length);
            System.arraycopy(body, 0, all, where.length, body.length);
            return all;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void send(IdempotentRequests.Claim.Replay replay, HttpServletResponse response) {
        var stored = replay.response();
        response.setStatus(stored.getStatusCode().value());
        var location = stored.getHeaders().getLocation();
        if (location != null) {
            response.setHeader(HttpHeaders.LOCATION, location.toString());
        }
        var body = stored.getBody();
        if (body == null) {
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
