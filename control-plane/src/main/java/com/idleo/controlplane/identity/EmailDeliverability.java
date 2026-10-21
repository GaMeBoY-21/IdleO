package com.idleo.controlplane.identity;

/**
 * A port the identity module declares and the notifications module implements (ADR 0036): whether email to an address
 * hard-bounced, so {@code GET /v1/me} can say "We couldn't email you: check your address". Notifications already
 * depends on identity, so this keeps the dependency one way, as {@link UnreadNotifications} does.
 */
public interface EmailDeliverability {

    /** True once the provider said mail to this address can't be delivered; false for any other address. */
    boolean undeliverable(String address);
}
