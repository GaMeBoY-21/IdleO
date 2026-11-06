package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closing months for the console (ADR 0055): each recent month with its checklist and history, and asking to close or
 * reopen one. Both go to another admin; closing is refused while the checklist isn't done.
 */
@RestController
@RequestMapping("/admin/v1/periods")
@ConditionalOnWebApplication
class AdminPeriodsController {

    private final Periods periods;
    private final Approvals approvals;

    AdminPeriodsController(Periods periods, Approvals approvals) {
        this.periods = periods;
        this.approvals = approvals;
    }

    record AdminPeriodCheck(String name, boolean done, String detail) {}

    record AdminPeriodEvent(String action, String reason, String actor, Instant at) {}

    /**
     * A month (YYYY-MM, India's time): open or closed, whether it's over, its checklist as it stands now, who closed or
     * reopened it and why, and its version for If-Match.
     */
    record AdminPeriod(
            String month,
            String state,
            boolean over,
            List<AdminPeriodCheck> checklist,
            List<AdminPeriodEvent> events,
            long version) {}

    record AdminPeriods(String current, List<AdminPeriod> months) {}

    /** The last {@code months} months, newest first (1 to 24; 6 when not given), with every closed month among them. */
    @RequiresPermission(AdminPermission.LEDGER_READ)
    @GetMapping
    AdminPeriods list(@RequestParam(required = false) @Nullable Integer months) {
        int count = months == null ? 6 : months;
        if (count < 1 || count > 24) {
            throw ApiProblem.invalid("months", "is 1 to 24.");
        }
        var current = periods.current();
        var list = new ArrayList<AdminPeriod>();
        for (int i = 0; i < count; i++) {
            list.add(period(current.minusMonths(i)));
        }
        return new AdminPeriods(current.toString(), list);
    }

    @RequiresPermission(AdminPermission.LEDGER_READ)
    @GetMapping("/{month}")
    AdminPeriod one(@PathVariable String month) {
        return period(month(month));
    }

    /** Asks to close a month that's over and whose checklist is done; another admin approves, with a factor. */
    @RequiresPermission(value = AdminPermission.PERIODS_CLOSE, record = "period")
    @PostMapping("/{month}/close")
    ResponseEntity<Approvals.AdminApproval> close(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String month,
            @Valid @RequestBody AdminReason body) {
        var asked = month(month);
        try {
            periods.checkClosable(asked);
        } catch (Periods.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        }
        return approvals.submit(
                admin,
                BillingGatedActions.PERIOD_CLOSE,
                "period",
                asked.toString(),
                Map.of("month", asked.toString()),
                List.of(new Approvals.ApprovalChange(asked + " in the ledger", "open", "closed")),
                body.reason());
    }

    /** Asks to reopen a closed month: the Owner, another admin's approval and a reason; it pages when it's done. */
    @RequiresPermission(value = AdminPermission.PERIODS_CLOSE, record = "period")
    @PostMapping("/{month}/reopen")
    ResponseEntity<Approvals.AdminApproval> reopen(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String month,
            @Valid @RequestBody AdminReason body) {
        var asked = month(month);
        if (!periods.closed(asked)) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, asked + " isn't closed.");
        }
        return approvals.submit(
                admin,
                BillingGatedActions.PERIOD_REOPEN,
                "period",
                asked.toString(),
                Map.of("month", asked.toString()),
                List.of(new Approvals.ApprovalChange(asked + " in the ledger", "closed", "open")),
                body.reason());
    }

    private AdminPeriod period(YearMonth month) {
        var events = periods.events(month);
        boolean closed = periods.closed(month);
        return new AdminPeriod(
                month.toString(),
                closed ? "closed" : "open",
                month.isBefore(periods.current()),
                periods.checklist(month).stream()
                        .map(item -> new AdminPeriodCheck(item.name(), item.done(), item.detail()))
                        .toList(),
                events.stream()
                        .map(event -> new AdminPeriodEvent(event.action(), event.reason(), event.actor(), event.at()))
                        .toList(),
                events.size());
    }

    private static YearMonth month(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiProblem.invalid("month", "is a month such as 2026-10.");
        }
    }
}
