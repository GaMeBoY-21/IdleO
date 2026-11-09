package com.idleo.controlplane;

import java.time.Clock;

/**
 * The clock an ID token's times are checked against (ADR 0057). It's the one clock, except while the end-to-end suite
 * moves time: the sign-in server stamps its tokens with the real time, so they're checked against it, or every token
 * would look expired after a jump.
 */
public record IdTokenClock(Clock clock) {}
