package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.identity.RevealableFields;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** What support keeps that can be revealed: the address a ticket is answered at, by the ticket's id. */
@Component
class SupportRevealableFields implements RevealableFields {

    private final Tickets tickets;

    SupportRevealableFields(Tickets tickets) {
        this.tickets = tickets;
    }

    @Override
    public Map<String, Function<String, Optional<Revealed>>> fields() {
        return Map.of("reply_to", this::replyTo);
    }

    private Optional<Revealed> replyTo(String id) {
        UUID ticket;
        try {
            ticket = UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
        return tickets.find(ticket)
                .filter(found -> found.replyTo() != null)
                .map(found -> new Revealed(found.userId(), found.replyTo()));
    }
}
