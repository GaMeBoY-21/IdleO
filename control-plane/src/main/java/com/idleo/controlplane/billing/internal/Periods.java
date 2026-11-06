package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AfterCommit;
import com.idleo.controlplane.billing.MonthCloseCheck;
import com.idleo.controlplane.identity.AuditTrail;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closing a month (ADR 0055). Months are India's, as the financial year is. Once the checklist holds (every {@link
 * MonthCloseCheck}), the Owner closes a month with another admin's approval; from then the database refuses any ledger
 * entry dated in it (V78's trigger), whatever the application does. A correction goes into an open month, as an
 * adjustment that names the closed one. Reopening takes the Owner, an approval and a reason; it's audited and pages,
 * through {@code billing.periods.reopened}.
 */
@Component
class Periods {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final ObjectProvider<MonthCloseCheck> checks;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final Clock clock;
    private final Counter reopened;

    Periods(
            JdbcClient jdbc,
            ObjectProvider<MonthCloseCheck> checks,
            PlatformTransactionManager transactionManager,
            AuditTrail audit,
            Clock clock,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.checks = checks;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.clock = clock;
        this.reopened = Counter.builder("billing.periods.reopened")
                .description("Closed months reopened by the Owner")
                .register(registry);
    }

    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** A checklist item for a month, as it stands now. */
    record Item(String name, boolean done, String detail) {}

    /** The month now, in India's time. */
    YearMonth current() {
        return YearMonth.from(clock.instant().atZone(INDIA));
    }

    boolean closed(YearMonth month) {
        return jdbc.sql("select state from billing.periods where month = :month")
                .param("month", month.atDay(1))
                .query(String.class)
                .optional()
                .map("closed"::equals)
                .orElse(false);
    }

    /** The month's checklist, in order: what holds and what's left. */
    List<Item> checklist(YearMonth month) {
        return checks.orderedStream()
                .sorted(Comparator.comparingInt(MonthCloseCheck::order))
                .map(check -> {
                    var result = check.check(month);
                    return new Item(check.name(), result.done(), result.detail());
                })
                .toList();
    }

    /** Whether {@code month} may be closed now, and if not, why: in words for the Owner. */
    void checkClosable(YearMonth month) {
        if (!month.isBefore(current())) {
            throw new Refused(month + " isn't over yet: a month is closed after it ends");
        }
        if (closed(month)) {
            throw new Refused(month + " is already closed");
        }
        var left = checklist(month).stream().filter(item -> !item.done()).toList();
        if (!left.isEmpty()) {
            throw new Refused("the checklist isn't done: "
                    + String.join(
                            "; ",
                            left.stream()
                                    .map(item -> item.name() + " (" + item.detail() + ")")
                                    .toList()));
        }
    }

    /** Closes {@code month}, as approved: once, so running the same approval again changes nothing. */
    void close(UUID approvalId, YearMonth month, String reason, AuditTrail.Actor actor) {
        if (alreadyRan(approvalId)) {
            return;
        }
        checkClosable(month);
        change(approvalId, month, "closed", reason, actor);
    }

    /** Reopens a closed {@code month}, as approved: audited, and it pages. */
    void reopen(UUID approvalId, YearMonth month, String reason, AuditTrail.Actor actor) {
        if (alreadyRan(approvalId)) {
            return;
        }
        if (!closed(month)) {
            throw new Refused(month + " isn't closed");
        }
        change(approvalId, month, "open", reason, actor);
        AfterCommit.run(reopened::increment);
    }

    private boolean alreadyRan(UUID approvalId) {
        return jdbc.sql("select count(*) from billing.period_events where approval_id = :approval")
                        .param("approval", approvalId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void change(UUID approvalId, YearMonth month, String state, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> {
            var now = Timestamp.from(clock.instant());
            LocalDate first = month.atDay(1);
            jdbc.sql("""
                            insert into billing.periods (month, state, version, updated_at)
                            values (:month, :state, 1, :now)
                            on conflict (month) do update
                                set state = excluded.state, version = billing.periods.version + 1,
                                    updated_at = excluded.updated_at
                            """)
                    .param("month", first)
                    .param("state", state)
                    .param("now", now)
                    .update();
            String action = "closed".equals(state) ? "closed" : "reopened";
            jdbc.sql("""
                            insert into billing.period_events (id, month, action, reason, actor, approval_id, created_at)
                            values (:id, :month, :action, :reason, :actor, :approval, :now)
                            """)
                    .param(
                            "id",
                            UUID.nameUUIDFromBytes(
                                    ("period:" + approvalId).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .param("month", first)
                    .param("action", action)
                    .param("reason", reason.strip())
                    .param("actor", actor.label())
                    .param("approval", approvalId)
                    .param("now", now)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "closed".equals(state) ? "period.close" : "period.reopen",
                    "period",
                    month.toString(),
                    reason.strip(),
                    null,
                    Map.of("month", month.toString())));
        });
    }

    /** A month's events, oldest first: who closed or reopened it, when and why. */
    record Event(String action, String reason, String actor, java.time.Instant at) {}

    List<Event> events(YearMonth month) {
        return jdbc.sql("""
                        select action, reason, actor, created_at from billing.period_events
                        where month = :month order by created_at, id
                        """)
                .param("month", month.atDay(1))
                .query((rs, n) -> new Event(
                        rs.getString("action"),
                        rs.getString("reason"),
                        rs.getString("actor"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** Whether {@code month} is closed, for the console's list: every month that has ever been closed. */
    List<YearMonth> everClosed() {
        return jdbc.sql("select month from billing.periods order by month desc")
                .query((rs, n) -> YearMonth.from(Objects.requireNonNull(rs.getObject("month", LocalDate.class))))
                .list();
    }
}
