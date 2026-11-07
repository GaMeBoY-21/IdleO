package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.AuditTrail;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the insider-risk alerts read (ADR 0051, ops/alerts/security.yaml): counters by kind, carrying no one's id. Who it
 * was and what they did is in the audit log and the access log, where each alert's runbook line points.
 */
@Component
class AdminInsiderSignals {

    /** More reveals than this by one admin in an hour is a burst. */
    static final int REVEAL_BURST = 20;

    /** What takes personal data out of the system, as the audit log names it. */
    private static final Set<String> EXPORTS =
            Set.of("tds.export", "tax.export", "tax.ca_pack", "list.export", "export.start");

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final int NIGHT_ENDS_AT_HOUR = 6;

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final Clock clock;

    AdminInsiderSignals(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private Counter signal(String kind) {
        return Counter.builder("admin.insider.signal")
                .tag("kind", kind)
                .description("Signals the insider-risk alerts read")
                .register(registry);
    }

    /** An admin signed in from a browser or an address they haven't used. */
    void newPlace() {
        signal("new_place").increment();
    }

    /** A gated action has no one else to approve it. */
    void soloApproval() {
        signal("solo_approval").increment();
    }

    /** One audited act: an admin's at night, or an export of people's data. */
    void observed(AuditTrail.Entry entry) {
        if (entry.actor().kind() != AuditTrail.Actor.Kind.ADMIN) {
            return;
        }
        if (Instant.now(clock).atZone(INDIA).getHour() < NIGHT_ENDS_AT_HOUR) {
            signal("off_hours").increment();
        }
        if (EXPORTS.contains(entry.action())) {
            signal("personal_data_export").increment();
        }
    }

    /** One admin read one more field whole: a signal each time they're past the hour's limit. */
    void revealed(UUID admin) {
        var count = jdbc.sql("""
                        select count(*) from identity.personal_data_access
                        where admin_id = :admin and field <> :view and field <> :search and at > :since
                        """)
                .param("admin", admin)
                .param("view", AccessLog.VIEW)
                .param("search", AccessLog.SEARCH)
                .param("since", Timestamp.from(Instant.now(clock).minus(Duration.ofHours(1))))
                .query(Long.class)
                .single();
        if (count > REVEAL_BURST) {
            signal("reveal_burst").increment();
        }
    }
}
