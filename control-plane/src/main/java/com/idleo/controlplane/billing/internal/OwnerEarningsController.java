package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.billing.EarningDetails;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.identity.CurrentUser;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a person has earned across every machine they own (ADR 0023), read only from the ledger's {@code host_payable}
 * entries, so it always matches the ledger and a lost-time giveback is a negative entry. Weeks run Monday to Sunday and
 * days end at midnight, in India (IST). Nothing is paid out yet: payouts start with the paid pilot.
 */
@RestController
class OwnerEarningsController {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String GIVEN_BACK = "Given back for lost time";

    private final HostOwners owners;
    private final OwnerEarningsQueries queries;
    private final EarningDetails details;
    private final Clock clock;

    OwnerEarningsController(HostOwners owners, OwnerEarningsQueries queries, EarningDetails details, Clock clock) {
        this.owners = owners;
        this.queries = queries;
        this.details = details;
        this.clock = clock;
    }

    /** One day of the bar chart: the IST calendar date and what was earned on it, less what was given back. */
    record EarningsDay(LocalDate date, long earnedPaise) {}

    /** One machine's total, less what was given back; {@code removed} ones are kept for the total's sake. */
    record EarningsMachine(UUID hostId, String name, boolean removed, long earnedPaise) {}

    /**
     * One ledger entry.
     *
     * @param amountPaise what it added to the host's earnings; negative for time given back
     * @param label what to call it: the service's name, or {@code Given back for lost time}
     * @param serviceName the service, when the session's template is still in the catalog
     * @param minutes the minutes it was for (given back, for a giveback); 0 when not known
     * @param kind {@code session_earning} or {@code lost_minutes_refunded}
     * @param sessionId the session it came from, which carries no one's identity
     */
    record EarningsEntry(
            UUID id,
            UUID hostId,
            String machineName,
            String kind,
            String label,
            @Nullable String serviceName,
            int minutes,
            long amountPaise,
            UUID sessionId,
            Instant createdAt) {}

    record EarningsPage(
            List<EarningsEntry> entries, @Nullable String nextCursor) {}

    /**
     * @param totalPaise earned in all
     * @param weekStartsAt this week's Monday, midnight IST
     * @param weekPaise earned since then
     * @param minutesRentedThisWeek minutes paid for since then, less minutes given back
     * @param days the last 7 days, oldest first, today last
     * @param payoutsStart when payouts begin: {@code paid-pilot}
     */
    record OwnerEarnings(
            long totalPaise,
            Instant weekStartsAt,
            long weekPaise,
            long minutesRentedThisWeek,
            List<EarningsDay> days,
            List<EarningsMachine> machines,
            EarningsPage history,
            String payoutsStart) {}

    @GetMapping("/v1/earnings")
    OwnerEarnings get(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + LedgerPages.DEFAULT_PAGE) int limit) {
        if (limit < 1 || limit > LedgerPages.MAX_PAGE) {
            throw ApiProblem.invalid("limit", "must be from 1 to " + LedgerPages.MAX_PAGE + ".");
        }
        var machines = owners.ownedBy(user.id());
        var hostIds = machines.stream().map(HostOwners.OwnedHost::id).toList();
        var now = Instant.now(clock);
        var today = now.atZone(IST).toLocalDate();
        var weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var weekStartsAt = weekStart.atStartOfDay(IST).toInstant();
        var firstDay = today.minusDays(6);
        var names = new java.util.HashMap<UUID, String>();
        machines.forEach(machine -> names.put(machine.id(), machine.name()));
        if (hostIds.isEmpty()) {
            return new OwnerEarnings(
                    0,
                    weekStartsAt,
                    0,
                    0,
                    days(firstDay, Map.of()),
                    List.of(),
                    new EarningsPage(List.of(), null),
                    "paid-pilot");
        }
        var totals = queries.totals(hostIds);
        var page = history(hostIds, names, cursor, limit);
        return new OwnerEarnings(
                totals.values().stream().mapToLong(Long::longValue).sum(),
                weekStartsAt,
                queries.between(
                        hostIds,
                        weekStartsAt,
                        weekStartsAt.atZone(IST).plusDays(7).toInstant()),
                minutesRented(hostIds, weekStartsAt),
                days(
                        firstDay,
                        queries.perDay(hostIds, firstDay.atStartOfDay(IST).toInstant(), IST.getId())),
                machines.stream()
                        .filter(machine -> !machine.removed() || totals.containsKey(machine.id()))
                        .map(machine -> new EarningsMachine(
                                machine.id(), machine.name(), machine.removed(), totals.getOrDefault(machine.id(), 0L)))
                        .toList(),
                page,
                "paid-pilot");
    }

    private static List<EarningsDay> days(LocalDate first, Map<LocalDate, Long> earned) {
        var days = new ArrayList<EarningsDay>();
        for (int i = 0; i < 7; i++) {
            var date = first.plusDays(i);
            days.add(new EarningsDay(date, earned.getOrDefault(date, 0L)));
        }
        return days;
    }

    private long minutesRented(List<UUID> hostIds, Instant weekStartsAt) {
        var entries = queries.entriesBetween(
                hostIds, weekStartsAt, weekStartsAt.atZone(IST).plusDays(7).toInstant());
        var found = details.of(entries.stream()
                .map(entry -> new EarningDetails.Key(entry.txnId(), entry.hostId()))
                .distinct()
                .toList());
        long minutes = 0;
        for (var entry : entries) {
            var detail = found.get(new EarningDetails.Key(entry.txnId(), entry.hostId()));
            if (detail != null) {
                minutes += entry.amountPaise() >= 0 ? detail.minutes() : -detail.minutes();
            }
        }
        return Math.max(0, minutes);
    }

    private EarningsPage history(List<UUID> hostIds, Map<UUID, String> names, @Nullable String cursor, int limit) {
        var after = cursor == null ? null : LedgerPages.Cursor.decode(cursor);
        var rows = queries.page(hostIds, after, limit + 1);
        var shown = rows.size() > limit ? rows.subList(0, limit) : rows;
        var found = details.of(shown.stream()
                .map(entry -> new EarningDetails.Key(entry.txnId(), entry.hostId()))
                .distinct()
                .toList());
        var entries = shown.stream()
                .map(entry -> {
                    var detail = found.get(new EarningDetails.Key(entry.txnId(), entry.hostId()));
                    boolean givenBack = entry.refType().equals(SessionChargeService.REFUND_REF);
                    var service = detail == null ? null : detail.serviceName();
                    return new EarningsEntry(
                            entry.id(),
                            entry.hostId(),
                            names.getOrDefault(entry.hostId(), "Removed machine"),
                            HostEarningsController.kind(entry.refType()),
                            givenBack ? GIVEN_BACK : service == null ? "Session" : service,
                            service,
                            detail == null ? 0 : detail.minutes(),
                            entry.amountPaise(),
                            entry.refId(),
                            entry.createdAt());
                })
                .toList();
        var last = shown.isEmpty() ? null : shown.getLast();
        return new EarningsPage(
                entries, rows.size() > limit ? new LedgerPages.Cursor(last.createdAt(), last.id()).encode() : null);
    }
}
