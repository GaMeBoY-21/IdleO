package com.idleo.controlplane.identity;

import java.time.Duration;
import org.springframework.security.core.Authentication;

/**
 * Whether a request comes from a recent sign-in (ADR 0037): its ID token's {@code auth_time} is within the window on
 * the control plane's clock. An API key, or a token without {@code auth_time}, never is.
 */
public interface FreshSignIn {

    boolean within(Authentication authentication, Duration window);
}
