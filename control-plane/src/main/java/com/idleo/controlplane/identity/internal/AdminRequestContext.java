package com.idleo.controlplane.identity.internal;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Where the current act comes from, for the audit log (ADR 0051): this request's id, its admin session, its address and
 * browser. Empty for work no request started, a scheduled job or an operator command.
 */
final class AdminRequestContext {

    private static final String REQUEST_ID = AdminRequestContext.class.getName() + ".requestId";
    private static final String SESSION_ID = AdminRequestContext.class.getName() + ".sessionId";
    private static final int USER_AGENT_LIMIT = 500;

    private AdminRequestContext() {}

    private static @Nullable ServletRequestAttributes current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes
                : null;
    }

    /** This request's id, the same however often it's asked. */
    static @Nullable String requestId() {
        var attributes = current();
        if (attributes == null) {
            return null;
        }
        var id = attributes.getAttribute(REQUEST_ID, RequestAttributes.SCOPE_REQUEST);
        if (id instanceof String known) {
            return known;
        }
        var made = UUID.randomUUID().toString();
        attributes.setAttribute(REQUEST_ID, made, RequestAttributes.SCOPE_REQUEST);
        return made;
    }

    static void adminSession(UUID sessionId) {
        var attributes = current();
        if (attributes != null) {
            attributes.setAttribute(SESSION_ID, sessionId, RequestAttributes.SCOPE_REQUEST);
        }
    }

    static @Nullable UUID adminSession() {
        var attributes = current();
        return attributes != null
                        && attributes.getAttribute(SESSION_ID, RequestAttributes.SCOPE_REQUEST) instanceof UUID id
                ? id
                : null;
    }

    static @Nullable String address() {
        var attributes = current();
        return attributes == null ? null : attributes.getRequest().getRemoteAddr();
    }

    static @Nullable String userAgent() {
        var attributes = current();
        var agent = attributes == null ? null : attributes.getRequest().getHeader("User-Agent");
        return agent == null || agent.length() <= USER_AGENT_LIMIT ? agent : agent.substring(0, USER_AGENT_LIMIT);
    }
}
