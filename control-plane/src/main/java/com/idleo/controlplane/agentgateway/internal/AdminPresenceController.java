package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.RequiresPermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A host page's presence history (ADR 0056): its changes over the 90 days kept, and how often it reconnected. */
@RestController
@ConditionalOnWebApplication
class AdminPresenceController {

    private static final Duration KEPT = Duration.ofDays(90);
    private static final int SHOWN = 500;

    private final PresenceHistory history;
    private final Clock clock;

    AdminPresenceController(PresenceHistory history, Clock clock) {
        this.history = history;
        this.clock = clock;
    }

    /** One change: {@code online} (a stream opened), {@code offline} or {@code unknown} (presence couldn't be read). */
    record AdminPresenceChange(String state, Instant at) {}

    /** How many streams the host opened on a day in India: one is a day without a reconnect. */
    record AdminPresenceDay(LocalDate day, long opened) {}

    /** @param changes newest first, at most 500 */
    record AdminPresenceHistory(List<AdminPresenceChange> changes, List<AdminPresenceDay> days) {}

    @RequiresPermission(AdminPermission.HOSTS_READ)
    @GetMapping("/admin/v1/hosts/{id}/presence")
    AdminPresenceHistory presence(@PathVariable UUID id) {
        var from = clock.instant().minus(KEPT);
        return new AdminPresenceHistory(
                history.changes(id, from, SHOWN).stream()
                        .map(change -> new AdminPresenceChange(change.state().db(), change.at()))
                        .toList(),
                history.reconnects(id, from).stream()
                        .map(day -> new AdminPresenceDay(day.day(), day.opened()))
                        .toList());
    }
}
