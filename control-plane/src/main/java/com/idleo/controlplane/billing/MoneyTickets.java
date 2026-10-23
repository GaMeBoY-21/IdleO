package com.idleo.controlplane.billing;

import java.util.UUID;

/**
 * Opening a {@code money} ticket for a signed-in person (ADR 0039: "Refund unused credit" opens one): billing declares
 * it and the support module implements it, so billing never depends on support. Acknowledged by email to the person's
 * verified address, as every ticket is.
 */
public interface MoneyTickets {

    record Opened(UUID id, long number) {}

    /** Opens a money ticket with {@code message}, as the person themselves would from Help. */
    Opened open(UUID userId, String message);
}
