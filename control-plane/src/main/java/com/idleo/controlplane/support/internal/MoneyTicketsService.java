package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.billing.MoneyTickets;
import com.idleo.controlplane.identity.UserDirectory;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@link MoneyTickets}: the person's own money ticket, answered at their verified address, through {@link Tickets#open}. */
@Service
class MoneyTicketsService implements MoneyTickets {

    private final Tickets tickets;
    private final UserDirectory users;

    MoneyTicketsService(Tickets tickets, UserDirectory users) {
        this.tickets = tickets;
        this.users = users;
    }

    @Override
    public Opened open(UUID userId, String message) {
        var address = users.verifiedEmail(userId)
                .orElseThrow(() -> new IllegalStateException("a signed-in person has a verified address"));
        // Written in the app by a signed-in person, so it isn't a machine's: no hidden field, and no time to wait.
        var ticket = tickets.open(userId, new Tickets.New("money", message, address, null, null, null, Long.MAX_VALUE));
        return new Opened(ticket.id(), ticket.number());
    }
}
