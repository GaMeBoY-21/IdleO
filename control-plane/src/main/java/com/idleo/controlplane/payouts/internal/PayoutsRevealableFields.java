package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.identity.RevealableFields;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** What payouts keeps that can be revealed: a host's PAN, by their user id, and a UPI ID, by its method's id. */
@Component
class PayoutsRevealableFields implements RevealableFields {

    private final Pans pans;
    private final PayoutMethods methods;

    PayoutsRevealableFields(Pans pans, PayoutMethods methods) {
        this.pans = pans;
        this.methods = methods;
    }

    @Override
    public Map<String, Function<String, Optional<Revealed>>> fields() {
        return Map.of("pan", this::pan, "upi", this::upi);
    }

    private Optional<Revealed> pan(String id) {
        return uuid(id).flatMap(user -> pans.full(user).map(pan -> new Revealed(user, pan)));
    }

    private Optional<Revealed> upi(String id) {
        return uuid(id).flatMap(methods::find)
                .filter(method -> method.vpa() != null)
                .map(method -> new Revealed(method.userId(), method.vpa()));
    }

    private static Optional<UUID> uuid(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
