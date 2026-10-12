package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.Problem;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What an API key may not do (ADR 0025), in one place. Anything that makes a credential, or lets one in, or moves an
 * owner's machine or money, needs a browser's sign-in: creating a key, approving a CLI sign-in, adding, removing or
 * pricing a machine, issuing its enrolment code, and changing notification choices. A key may list keys and revoke
 * any of its owner's, since revoking only ever takes access away; creating one always goes through the browser, so a
 * stolen key can never make another. A read key makes GET requests only, and may still revoke itself.
 */
final class KeyRules {

    static final String BROWSER_ONLY = "Sign in to the web app with your browser to do this; an API key can't.";
    static final String READ_ONLY = "This API key can only read. Use a full key, or the web app.";

    private record Rule(String method, Pattern path) {}

    private static final String ID = "[0-9a-fA-F-]{36}";

    private static final List<Rule> BROWSER_ONLY_CALLS = List.of(
            new Rule("POST", Pattern.compile("/v1/api-keys")),
            new Rule("GET", Pattern.compile("/v1/cli-logins")),
            new Rule("POST", Pattern.compile("/v1/cli-logins/" + ID + "/approve")),
            new Rule("POST", Pattern.compile("/v1/hosts")),
            new Rule("DELETE", Pattern.compile("/v1/hosts/" + ID)),
            new Rule("PUT", Pattern.compile("/v1/hosts/" + ID + "/listing")),
            new Rule("POST", Pattern.compile("/v1/hosts/" + ID + "/enrolment-code")),
            new Rule("PUT", Pattern.compile("/v1/me/notifications")));

    private static final Pattern REVOKE = Pattern.compile("/v1/api-keys/(" + ID + ")");

    private KeyRules() {}

    /** Why a request made with this key is refused, or empty when it may go ahead. */
    static Optional<IdentityAuthenticationToken.Denial> refusal(
            String method, String path, IdentityAuthenticationToken.KeyCredential key) {
        if (BROWSER_ONLY_CALLS.stream()
                .anyMatch(rule -> rule.method().equals(method)
                        && rule.path().matcher(path).matches())) {
            return Optional.of(new IdentityAuthenticationToken.Denial(Problem.BROWSER_SIGN_IN_REQUIRED, BROWSER_ONLY));
        }
        if (key.readOnly() && !method.equals("GET") && !method.equals("HEAD") && !revokesItself(method, path, key)) {
            return Optional.of(new IdentityAuthenticationToken.Denial(Problem.API_KEY_READ_ONLY, READ_ONLY));
        }
        return Optional.empty();
    }

    private static boolean revokesItself(String method, String path, IdentityAuthenticationToken.KeyCredential key) {
        var matcher = REVOKE.matcher(path);
        return method.equals("DELETE")
                && matcher.matches()
                && matcher.group(1).equalsIgnoreCase(key.keyId().toString());
    }
}
