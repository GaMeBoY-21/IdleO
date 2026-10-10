package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.identity.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sessions a host ran, for its owner (ADR 0023): what ran, when, for how long, what the host earned and how it
 * ended. Never who it ran for, what it read or its parameters: the renter stays private.
 */
@RestController
class HostSessionsController {

    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 50;

    private final HostOwners owners;
    private final HostSessionRows rows;
    private final TemplateCatalog catalog;

    HostSessionsController(HostOwners owners, HostSessionRows rows, TemplateCatalog catalog) {
        this.owners = owners;
        this.rows = rows;
        this.catalog = catalog;
    }

    /**
     * One attempt that ran on the host.
     *
     * @param serviceName what ran ("Fine-tune a model")
     * @param runningLabel what it was doing ("Fine-tuning")
     * @param minutes how long it ran on this host, a started minute counting whole
     * @param earnedPaise what the session paid this host, less what was given back, from the ledger
     * @param givenBackPaise what was taken back for lost time
     * @param outcome {@code running}, {@code completed}, {@code stopped} (the renter stopped it), {@code failed} or
     *     {@code lost} (the host went away; its minutes after the last checkpoint are given back)
     */
    record HostSession(
            UUID id,
            String serviceName,
            String runningLabel,
            Instant startedAt,
            @Nullable Instant endedAt,
            int minutes,
            long earnedPaise,
            long givenBackPaise,
            String outcome) {}

    record HostSessionPage(
            List<HostSession> sessions, @Nullable String nextCursor) {}

    @GetMapping("/v1/hosts/{hostId}/sessions")
    HostSessionPage list(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID hostId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        if (!owners.owns(user.id(), hostId)) {
            throw ApiProblem.of(Problem.NOT_FOUND, "No such host.");
        }
        var page = rows.page(hostId, cursor, Cursors.limit(limit, MAX_PAGE));
        return new HostSessionPage(
                page.rows().stream()
                        .map(row -> {
                            var service = catalog.serviceOf(row.templateId());
                            return new HostSession(
                                    row.sessionId(),
                                    service.map(com.idleo.controlplane.sessions.Service::name)
                                            .orElse(row.templateId()),
                                    service.map(com.idleo.controlplane.sessions.Service::runningLabel)
                                            .orElse("Running"),
                                    row.startedAt(),
                                    row.endedAt(),
                                    row.minutes(),
                                    row.earned().netPaise(),
                                    row.earned().givenBackPaise(),
                                    row.outcome().name().toLowerCase(java.util.Locale.ROOT));
                        })
                        .toList(),
                page.nextCursor());
    }
}
