package com.idleo.controlplane;

import java.util.Optional;
import java.util.UUID;

/**
 * Whose a record is (ADR 0052), asked of the module that owns it: what a note or a tag on a host, a session or a payment
 * says about a person, so it's in their export and goes when they do.
 */
public interface AdminSubject {

    /** user, host, session or payment. */
    String kind();

    /** The person this record is about; empty when there's no such record. */
    Optional<UUID> person(UUID id);

    static AdminSubject of(String kind, java.util.function.Function<UUID, Optional<UUID>> person) {
        return new AdminSubject() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public Optional<UUID> person(UUID id) {
                return person.apply(id);
            }
        };
    }
}
