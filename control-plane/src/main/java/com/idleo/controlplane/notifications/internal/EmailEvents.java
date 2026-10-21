package com.idleo.controlplane.notifications.internal;

import java.util.List;
import java.util.Map;

/**
 * What the email provider tells us about mail it handled (ADR 0036): one implementation per provider, Resend's first
 * ({@link ResendEmailEvents}). It checks the call is the provider's, by its signature, before reading a byte of it, and
 * says only what we act on: a hard bounce or a spam complaint, for which addresses.
 */
interface EmailEvents {

    enum Kind {
        /** The receiving server refused it for good: the address can't be delivered to. */
        HARD_BOUNCE,
        /** Refused for now (a full mailbox, a server down): nothing to do, the provider retries. */
        SOFT_BOUNCE,
        /** The person marked it as spam. */
        COMPLAINT,
        /** Anything else the provider sends: delivered, opened and so on. Ignored. */
        OTHER
    }

    /** @param id the provider's id for this event, the same when it delivers the event again */
    record Event(String id, Kind kind, List<String> addresses) {}

    /** The call wasn't signed by the provider, or was signed too long ago to be trusted. */
    class Unsigned extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Unsigned(String message) {
            super(message);
        }
    }

    /**
     * @param headers the request's headers, names in lower case
     * @param body the raw body, exactly as received: the signature covers its bytes
     * @throws Unsigned unless the provider signed it, recently
     * @throws IllegalArgumentException when it's signed but isn't an event we can read
     */
    Event read(Map<String, String> headers, byte[] body);
}
