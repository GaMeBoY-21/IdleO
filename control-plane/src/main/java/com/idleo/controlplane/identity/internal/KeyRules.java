package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.web.method.HandlerMethod;

/**
 * What an API key may not do (ADR 0025), in one place, decided by the handler a request reached after routing, never
 * by how its path is spelt. Anything that makes a credential, or lets one in, or moves an owner's machine or money,
 * needs a browser's sign-in: its handler is {@link BrowserSignInOnly}. A key may list keys and revoke any of its
 * owner's, since revoking only ever takes access away; creating one always goes through the browser, so a stolen key
 * can never make another. A read key makes GET requests only, and may still revoke itself ({@link
 * ReadKeyMayRevokeItself}).
 *
 * <p>It fails closed: a key's request that reaches no handler method (an unknown path, a static resource), so there is
 * nothing to read the rules from, may only read.
 */
final class KeyRules {

    static final String BROWSER_ONLY = "Sign in to the web app with your browser to do this; an API key can't.";
    static final String READ_ONLY = "This API key can only read. Use a full key, or the web app.";

    private KeyRules() {}

    /**
     * Why a request made with this key is refused, or empty when it may go ahead. {@code handler} is the handler method
     * the request was routed to, or null when it reached none; {@code pathVariables} are the route's, decoded.
     */
    static Optional<IdentityAuthenticationToken.Denial> refusal(
            String method,
            @Nullable HandlerMethod handler,
            Map<String, String> pathVariables,
            IdentityAuthenticationToken.KeyCredential key) {
        var reads = method.equals("GET") || method.equals("HEAD");
        if (handler == null) {
            return reads ? Optional.empty() : Optional.of(browserOnly());
        }
        if (handler.hasMethodAnnotation(BrowserSignInOnly.class)) {
            return Optional.of(browserOnly());
        }
        if (key.readOnly() && !reads && !revokesItself(handler, pathVariables, key)) {
            return Optional.of(new IdentityAuthenticationToken.Denial(Problem.API_KEY_READ_ONLY, READ_ONLY));
        }
        return Optional.empty();
    }

    private static IdentityAuthenticationToken.Denial browserOnly() {
        return new IdentityAuthenticationToken.Denial(Problem.BROWSER_SIGN_IN_REQUIRED, BROWSER_ONLY);
    }

    private static boolean revokesItself(
            HandlerMethod handler, Map<String, String> pathVariables, IdentityAuthenticationToken.KeyCredential key) {
        var revocation = handler.getMethodAnnotation(ReadKeyMayRevokeItself.class);
        if (revocation == null) {
            return false;
        }
        var id = pathVariables.get(revocation.value());
        return id != null && id.equalsIgnoreCase(key.keyId().toString());
    }
}
