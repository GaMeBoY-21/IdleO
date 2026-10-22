package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ClientAccessDeniedException;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.OpenToHeldAccounts;
import com.idleo.controlplane.identity.OpenToWaitingUsers;
import com.idleo.controlplane.identity.RequiresAcceptedTerms;
import com.idleo.controlplane.identity.SecurityLog;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The rules that depend on which call a request is, checked once, after routing, on the handler the request actually
 * reached (ADR 0024, ADR 0025): a waiting user reaches only {@link OpenToWaitingUsers} handlers, a held account (suspended
 * or banned, ADR 0038) only {@link OpenToHeldAccounts} ones, a handler marked {@link RequiresAcceptedTerms} only a person who
 * has accepted the Terms in force, and a request made
 * with an API key is held to {@link KeyRules}. Deciding by the route, never by the spelling of the path, is what
 * makes a percent-encoded letter or a trailing slash meet the same answer as the plain path. Both fail closed: a
 * request that reaches no handler method is outside the waiting user's few calls, and is only a read for a key.
 *
 * <p>Who is calling, and whether their account is suspended, is decided before this, by Spring Security.
 */
class AccessRules implements HandlerInterceptor {

    private final NoticeAcceptances notices;
    private final URI webApp;
    private final SecurityLog security;

    AccessRules(NoticeAcceptances notices, URI webApp, SecurityLog security) {
        this.notices = notices;
        this.webApp = webApp;
        this.security = security;
    }

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
        if (token.isHeld() && (route == null || !route.hasMethodAnnotation(OpenToHeldAccounts.class))) {
            if (token.getPrincipal() instanceof CurrentUser user) {
                security.record(SecurityLog.Kind.SIGN_IN_REFUSED, user.id(), "suspended");
            }
            throw new ClientAccessDeniedException(Problem.ACCOUNT_SUSPENDED, SignInAuthenticationConverter.SUSPENDED);
        }
        token.key()
                .flatMap(key -> KeyRules.refusal(request.getMethod(), route, pathVariables(request), key))
                .ifPresent(denial -> {
                    throw new ClientAccessDeniedException(denial.problem(), denial.message());
                });
        // After the key's own rules, so a key asking for a browser-only call is told that first.
        if (route != null
                && route.hasMethodAnnotation(RequiresAcceptedTerms.class)
                && token.getPrincipal() instanceof CurrentUser user
                && !notices.standing(user.id()).accepted()) {
            throw new ClientAccessDeniedException(Problem.TERMS_NOT_ACCEPTED, termsMessage());
        }
        return true;
    }

    /** Where to accept: the app's own page for it, which asks for the Terms and the privacy notice together. */
    private String termsMessage() {
        var base = webApp.toString().replaceAll("/+$", "");
        return "Accept the Terms and the privacy notice before you create anything: sign in at " + base
                + "/agree/ in a browser and accept them there, then try again.";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        var variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}
