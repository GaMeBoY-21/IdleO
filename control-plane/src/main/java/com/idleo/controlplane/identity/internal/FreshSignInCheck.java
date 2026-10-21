package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.FreshSignIn;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
class FreshSignInCheck implements FreshSignIn {

    private final Clock clock;

    FreshSignInCheck(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean within(Authentication authentication, Duration window) {
        if (!(authentication instanceof IdentityAuthenticationToken token)
                || token.key().isPresent()) {
            return false;
        }
        var now = Instant.now(clock);
        return token.authTime()
                .filter(signedIn -> !signedIn.isAfter(now.plusSeconds(60)))
                .filter(signedIn -> !signedIn.isBefore(now.minus(window)))
                .isPresent();
    }
}
