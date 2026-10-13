package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostPresence;
import com.idleo.controlplane.hosts.HostTelemetry;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hosts, for the admin console (ADR 0027): find them, read one in full, and activate, suspend or revoke its
 * certificates. Activating and suspending call the same service as {@code activate-host} and {@code suspend-host}.
 */
@RestController
@RequestMapping("/admin/v1/hosts")
@ConditionalOnWebApplication
class AdminHostsController {

    private final JdbcClient jdbc;
    private final HostRepository hosts;
    private final HostActivation activation;
    private final HostCertificateService certificates;
    private final HostPresence presence;
    private final HostTelemetry telemetry;
    private final AuditTrail audit;

    AdminHostsController(
            JdbcClient jdbc,
            HostRepository hosts,
            HostActivation activation,
            HostCertificateService certificates,
            HostPresence presence,
            HostTelemetry telemetry,
            AuditTrail audit) {
        this.jdbc = jdbc;
        this.hosts = hosts;
        this.activation = activation;
        this.certificates = certificates;
        this.presence = presence;
        this.telemetry = telemetry;
        this.audit = audit;
    }

    /** A host in a list: who owns it, its status, whether its agent is connected, and its GPUs' models. */
    record AdminHostSummary(
            UUID id,
            UUID ownerId,
            String name,
            String status,
            boolean online,
            List<String> gpuModels,
            Instant createdAt,
            @Nullable Instant lastSeenAt) {}

    record AdminHostPage(
            List<AdminHostSummary> hosts, @Nullable String nextCursor) {}

    /** One GPU the agent reported; {@code goneAt} is when it stopped reporting it. */
    record AdminHostGpu(
            String uuid,
            String model,
            int vramMb,
            @Nullable String driverVersion,
            Instant firstSeenAt,
            Instant lastReportedAt,
            @Nullable Instant goneAt) {}

    /** One certificate issued to the host's agent, by its serial in hex. */
    record AdminHostCertificate(
            String serial,
            Instant notBefore,
            Instant notAfter,
            @Nullable Instant revokedAt,
            Instant createdAt) {}

    /** One change of the host's status: from, to, why and by whom. */
    record AdminHostStatusChange(String from, String to, String reason, String changedBy, Instant at) {}

    /**
     * Everything about a host an admin needs: its listing, pause and agent, its GPUs, certificates and status history,
     * and its telemetry (the latest heartbeat and the last hour), which is never used for billing.
     */
    record AdminHost(
            UUID id,
            UUID ownerId,
            String name,
            String status,
            boolean online,
            @Nullable Instant connectedSince,
            @Nullable String agentVersion,
            @Nullable Integer pricePaisePerMin,
            int keepFreeGb,
            @Nullable Instant pausedAt,
            @Nullable Instant pausedUntil,
            Instant createdAt,
            @Nullable Instant lastSeenAt,
            @Nullable Instant removedAt,
            List<AdminHostGpu> gpus,
            List<AdminHostCertificate> certificates,
            List<AdminHostStatusChange> statusHistory,
            HostTelemetry.Telemetry telemetry) {}

    /**
     * {@code query} is part of a host's name, or a whole host or owner id; {@code status} is pending, verified, active,
     * suspended or removed.
     */
    @GetMapping
    AdminHostPage search(
            @RequestParam(required = false) @Nullable String query,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        record Row(
                UUID id,
                UUID ownerId,
                String name,
                String status,
                List<String> models,
                Instant createdAt,
                @Nullable Instant lastSeenAt) {}
        var rows = jdbc.sql("""
                        select h.id, h.owner_user_id, h.name, h.status, h.created_at, h.last_seen_at,
                               coalesce((select array_agg(g.model order by g.model) from hosts.host_gpus g
                                         where g.host_id = h.id and g.gone_at is null), '{}') as models
                        from hosts.hosts h
                        where (cast(:query as text) is null
                               or h.name ilike '%' || cast(:query as text) || '%'
                               or h.id::text = lower(cast(:query as text))
                               or h.owner_user_id::text = lower(cast(:query as text)))
                          and (cast(:status as text) is null or h.status = cast(:status as text))
                          and (cast(:after as uuid) is null or h.id < cast(:after as uuid))
                        order by h.id desc
                        limit :limit
                        """)
                .param("query", blankToNull(query))
                .param("status", blankToNull(status))
                .param("after", AdminPages.after(cursor))
                .param("limit", size + 1)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        List.of((String[]) rs.getArray("models").getArray()),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_seen_at") == null
                                ? null
                                : rs.getTimestamp("last_seen_at").toInstant()))
                .list();
        var page = rows.size() > size ? rows.subList(0, size) : rows;
        var online = presence.online(page.stream().map(Row::id).toList());
        return new AdminHostPage(
                page.stream()
                        .map(row -> new AdminHostSummary(
                                row.id(),
                                row.ownerId(),
                                row.name(),
                                row.status(),
                                online.contains(row.id()),
                                row.models(),
                                row.createdAt(),
                                row.lastSeenAt()))
                        .toList(),
                rows.size() > size ? AdminPages.cursorAfter(page.getLast().id()) : null);
    }

    @GetMapping("/{id}")
    AdminHost get(@PathVariable UUID id) {
        return find(id);
    }

    /** Activates a host whose agent has enrolled, listed and reported a GPU. */
    @PostMapping("/{id}/activate")
    AdminHost activate(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        find(id);
        refusing(() -> activation.activate(id, request.reason(), audit.admin(admin)));
        return find(id);
    }

    /** Suspends a host: it gets no new work, and its agent is refused at its next connection. */
    @PostMapping("/{id}/suspend")
    AdminHost suspend(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        find(id);
        refusing(() -> activation.suspend(id, request.reason(), audit.admin(admin)));
        return find(id);
    }

    /** Revokes every certificate the host has: its agent is cut off until its owner enrols it again. */
    @PostMapping("/{id}/revoke-certificates")
    AdminHost revokeCertificates(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        find(id);
        refusing(() -> certificates.revokeAll(id, request.reason(), audit.admin(admin)));
        return find(id);
    }

    private AdminHost find(UUID id) {
        var host = hosts.findById(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such host."));
        var price =
                jdbc.sql("""
                        select price_paise_per_min from hosts.price_listings
                        where host_id = :id and workload_type = 'batch'
                        """).param("id", id).query(Integer.class).optional().orElse(null);
        var gpus = jdbc.sql("select * from hosts.host_gpus where host_id = :id order by first_seen_at, gpu_uuid")
                .param("id", id)
                .query((rs, n) -> new AdminHostGpu(
                        rs.getString("gpu_uuid"),
                        rs.getString("model"),
                        rs.getInt("vram_mb"),
                        rs.getString("driver_version"),
                        rs.getTimestamp("first_seen_at").toInstant(),
                        rs.getTimestamp("last_reported_at").toInstant(),
                        instant(rs.getTimestamp("gone_at"))))
                .list();
        var issued = jdbc.sql("select * from hosts.host_certificates where host_id = :id order by created_at desc")
                .param("id", id)
                .query((rs, n) -> new AdminHostCertificate(
                        rs.getBigDecimal("serial").toBigIntegerExact().toString(16),
                        rs.getTimestamp("not_before").toInstant(),
                        rs.getTimestamp("not_after").toInstant(),
                        instant(rs.getTimestamp("revoked_at")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        var history = jdbc.sql(
                        "select * from hosts.status_changes where host_id = :id order by created_at desc, id desc")
                .param("id", id)
                .query((rs, n) -> new AdminHostStatusChange(
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("reason"),
                        rs.getString("changed_by"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        var connection = presence.connections(List.of(id)).get(id);
        return new AdminHost(
                host.id(),
                host.ownerUserId(),
                host.name(),
                host.status(),
                connection != null,
                connection == null ? null : connection.since(),
                host.agentVersion(),
                price,
                host.keepFreeGb(),
                host.pausedAt(),
                host.pausedUntil(),
                host.createdAt(),
                host.lastSeenAt(),
                host.removedAt(),
                gpus,
                issued,
                history,
                telemetry.of(id));
    }

    private static void refusing(Supplier<?> action) {
        try {
            action.get();
        } catch (HostActivation.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        } catch (HostCertificateService.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        }
    }

    private static @Nullable Instant instant(java.sql.@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
