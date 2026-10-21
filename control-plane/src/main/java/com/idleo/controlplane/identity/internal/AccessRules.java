package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ClientAccessDeniedException;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.OpenToWaitingUsers;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The rules that depend on which call a request is, checked once, after routing, on the handler the request actually
 * reached (ADR 0024, ADR 0025): a waiting user reaches only {@link OpenToWaitingUsers} handlers, and a request made
 * with an API key is held to {@link KeyRules}. Deciding by the route, never by the spelling of the path, is what
 * makes a percent-encoded letter or a trailing slash meet the same answer as the plain path. Both fail closed: a
 * request that reaches no handler method is outside the waiting user's few calls, and is only a read for a key.
 *
 * <p>Who is calling, and whether their account is suspended, is decided before this, by Spring Security.
 */
class AccessRules implements HandlerInterceptor {

    static final String INVITE_REQUIRED = "Enter an invite code to use IdleO.";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // An error page renders a failure that has already been decided; it runs nothing of its own.
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof IdentityAuthenticationToken token)) {
            return true;
        }
        var route = handler instanceof HandlerMethod method ? method : null;
        if (token.isWaiting() && (route == null || !route.hasMethodAnnotation(OpenToWaitingUsers.class))) {
            throw new ClientAccessDeniedException(Problem.INVITE_REQUIRED, INVITE_REQUIRED);
        }
        token.key()
                .flatMap(key -> KeyRules.refusal(request.getMethod(), route, pathVariables(request), key))
                .ifPresent(denial -> {
                    throw new ClientAccessDeniedException(denial.problem(), denial.message());
                });
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        var variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}
