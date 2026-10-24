package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.FinancialYear;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.billing.HostPayouts;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.FreshSignIn;
import com.idleo.controlplane.identity.OpenToHeldAccounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A host's payouts (ADR 0040), for Hosting, Earnings:
 *
 * <ul>
 *   <li>{@code GET /v1/payouts/summary}: what's pending (each session with the day it becomes available, or held by a
 *       report), available, owed back, on its way and paid; the next payday; whether they can be paid and if not why;
 *       and this financial year's gross and tax deducted.
 *   <li>{@code GET /v1/payouts?cursor=…&limit=…}: their payouts, newest first, each with its status and, for one that
 *       came back, why.
 *   <li>{@code GET /v1/payouts/statement?year=2026-27&format=pdf|csv}: the year's statement.
 *   <li>{@code POST /v1/payouts/forfeit}: gives up every unpaid earning in writing, to delete the account without a
 *       payout method; only after a sign-in in the last 10 minutes, never while a payout is on its way.
 * </ul>
 */
@RestController
class OwnerPayoutsController {

    private final HostPayouts ledger;
    private final HostOwners owners;
    private final PayoutService payouts;
    private final PayoutRuns runs;
    private final PayoutMethods methods;
    private final Pans pans;
    private final Statements statements;
    private final PayoutsProperties properties;
    private final FreshSignIn fresh;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final IdGenerator ids;
    private final Clock clock;

    OwnerPayoutsController(
            HostPayouts ledger,
            HostOwners owners,
            PayoutService payouts,
            PayoutRuns runs,
            PayoutMethods methods,
            Pans pans,
            Statements statements,
            PayoutsProperties properties,
            FreshSignIn fresh,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            AuditTrail audit,
            IdGenerator ids,
            Clock clock) {
        this.ledger = ledger;
        this.owners = owners;
        this.payouts = payouts;
        this.runs = runs;
        this.methods = methods;
        this.pans = pans;
        this.statements = statements;
        this.properties = properties;
        this.fresh = fresh;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * One session's earnings on one machine, not available yet.
     *
     * @param availableAt when its dispute window ends; null while a report about it is open
     */
    record PendingEarning(
            UUID hostId,
            String machineName,
            UUID sessionId,
            long amountPaise,
            @Nullable Instant availableAt) {}

    /**
     * @param pendingPaise in the dispute window, or held by a report
     * @param availablePaise past it: the next payout takes it
     * @param owedPaise earnings refunded to a renter after they were paid out, taken from the next earnings
     * @param payablePaise what the next payout would pay before tax: available less owed
     * @param inFlightPaise on its way to the UPI ID now
     * @param paidPaise reached the UPI ID, in all
     * @param nextPayoutAt the next weekly run
     * @param minimumPaise the least a weekly payout pays
     * @param blockedBy why the next payout can't be made: no-pan, pan-in-review, no-upi, upi-in-review, upi-suspect,
     *     on-hold; null when it can
     * @param holdUntil when a hold after a change of UPI ID ends
     * @param financialYear this year, {@code 2026-27}
     * @param yearGrossPaise paid out this financial year before tax
     * @param yearTdsPaise tax deducted at source this financial year
     * @param statementYears the years a statement can be downloaded for, newest first
     */
    record PayoutSummary(
            long pendingPaise,
            long availablePaise,
            long owedPaise,
            long payablePaise,
            long inFlightPaise,
            long paidPaise,
            List<PendingEarning> pending,
            Instant nextPayoutAt,
            long minimumPaise,
            @Nullable String blockedBy,
            @Nullable Instant holdUntil,
            String financialYear,
            long yearGrossPaise,
            long yearTdsPaise,
            List<String> statementYears) {}

    /**
     * One payout.
     *
     * @param kind weekly, retry or final (before the account was deleted)
     * @param status created (on its way to RazorpayX), queued, pending, processing, processed, failed, rejected,
     *     cancelled or reversed
     * @param why for one that came back, in plain words
     */
    record PayoutView(
            UUID id,
            String kind,
            long grossPaise,
            long tdsPaise,
            String tdsSection,
            long feePaise,
            long netPaise,
            String status,
            @Nullable String why,
            @Nullable String utr,
            Instant createdAt,
            @Nullable Instant processedAt,
            @Nullable Instant endedAt) {}

    record PayoutPage(List<PayoutView> payouts, @Nullable String nextCursor) {}

    record Forfeit(
            @NotBlank @Size(min = 10, max = 500) @NoContactDetails
            String statement) {}

    record Forfeited(long amountPaise) {}

    @OpenToHeldAccounts
    @GetMapping("/v1/payouts/summary")
    PayoutSummary summary(@AuthenticationPrincipal CurrentUser user) {
        var position = ledger.position(user.id());
        var names = new HashMap<UUID, String>();
        owners.ownedBy(user.id()).forEach(host -> names.put(host.id(), host.name()));
        var now = clock.instant();
        var year = FinancialYear.of(now);
        var totals = jdbc.sql("""
                        select coalesce(sum(gross_paise), 0), coalesce(sum(tds_paise), 0) from payouts.payouts
                        where user_id = :user and financial_year = :year and status not in (:returned)
                        """)
                .param("user", user.id())
                .param("year", year.name())
                .param("returned", TaxDeduction.RETURNED)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
        var inUse = methods.inUse(user.id()).orElse(null);
        return new PayoutSummary(
                position.pendingPaise(),
                position.availablePaise(),
                position.owedPaise(),
                position.payablePaise(),
                position.inFlightPaise(),
                position.paidPaise(),
                position.pending().stream()
                        .map(session -> new PendingEarning(
                                session.hostId(),
                                names.getOrDefault(session.hostId(), "Removed machine"),
                                session.sessionId(),
                                session.amountPaise(),
                                session.availableAt()))
                        .toList(),
                runs.nextPayday(),
                properties.minimumPaise(),
                blockedBy(user.id(), inUse, now),
                inUse == null ? null : inUse.holdUntil(),
                year.name(),
                totals[0],
                totals[1],
                statements.years(user.id(), now));
    }

    private @Nullable String blockedBy(UUID userId, PayoutMethods.@Nullable Method inUse, Instant now) {
        var pan = pans.find(userId).orElse(null);
        if (pan == null || pan.state().equals("rejected")) {
            return "no-pan";
        }
        if (!pan.verified()) {
            return "pan-in-review";
        }
        if (inUse == null) {
            return methods.inReview(userId).isPresent() ? "upi-in-review" : "no-upi";
        }
        if (inUse.state().equals("suspect")) {
            return "upi-suspect";
        }
        return inUse.payable(now) ? null : "on-hold";
    }

    @OpenToHeldAccounts
    @GetMapping("/v1/payouts")
    PayoutPage list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var rows = payouts.ofUser(user.id(), AdminPages.after(cursor), size + 1);
        var shown = rows.size() > size ? rows.subList(0, size) : rows;
        return new PayoutPage(
                shown.stream().map(OwnerPayoutsController::view).toList(),
                rows.size() > size ? AdminPages.cursorAfter(shown.getLast().id()) : null);
    }

    static PayoutView view(PayoutService.Payout payout) {
        return new PayoutView(
                payout.id(),
                payout.kind(),
                payout.grossPaise(),
                payout.tdsPaise(),
                payout.tdsSection(),
                payout.feePaise(),
                payout.netPaise(),
                payout.status(),
                why(payout),
                payout.utr(),
                payout.createdAt(),
                payout.processedAt(),
                payout.endedAt());
    }

    /** The plain words for a payout that came back. */
    static @Nullable String why(PayoutService.Payout payout) {
        return switch (payout.status()) {
            case "reversed" ->
                "Your bank sent it back. It's in your earnings again: check your UPI ID before the next" + " payout.";
            case "failed", "rejected", "cancelled" ->
                "It didn't go through"
                        + (payout.statusDescription() == null ? "" : ": " + payout.statusDescription())
                        + ". It's back in your earnings, and the next payout tries again.";
            default -> null;
        };
    }

    @OpenToHeldAccounts
    @GetMapping(
            value = "/v1/payouts/statement",
            produces = {MediaType.APPLICATION_PDF_VALUE, "text/csv"})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "The year's statement, as a PDF or CSV",
            content = {
                @io.swagger.v3.oas.annotations.media.Content(
                        mediaType = MediaType.APPLICATION_PDF_VALUE,
                        schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string")),
                @io.swagger.v3.oas.annotations.media.Content(
                        mediaType = "text/csv",
                        schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string"))
            })
    ResponseEntity<byte[]> statement(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam String year,
            @RequestParam(defaultValue = "pdf") String format) {
        FinancialYear financialYear;
        try {
            financialYear = FinancialYear.parse(year);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("year", e.getMessage() + ".");
        }
        var statement = statements.of(user.id(), financialYear);
        var name = "idleo-payouts-" + financialYear.name();
        return switch (format) {
            case "pdf" -> file(statements.pdf(statement), MediaType.APPLICATION_PDF, name + ".pdf");
            case "csv" -> file(statements.csv(statement), new MediaType("text", "csv"), name + ".csv");
            default -> throw ApiProblem.invalid("format", "must be pdf or csv.");
        };
    }

    static ResponseEntity<byte[]> file(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString())
                .body(body);
    }

    /**
     * Gives up every unpaid earning in writing (ADR 0040): for deleting an account with no UPI ID payouts may go to.
     * The person's own words are kept with it, and it's audited.
     */
    @OpenToHeldAccounts
    @BrowserSignInOnly
    @PostMapping("/v1/payouts/forfeit")
    Forfeited forfeit(
            @AuthenticationPrincipal CurrentUser user,
            Authentication authentication,
            @Valid @RequestBody Forfeit request) {
        if (!fresh.within(authentication, PayoutDetailsController.FRESH)) {
            throw ApiProblem.of(
                    Problem.FRESH_SIGN_IN_REQUIRED,
                    "Sign in again: giving up earnings needs a sign-in in the last 10 minutes.");
        }
        if (payouts.open(user.id()).isPresent()) {
            throw ApiProblem.of(Problem.PAYOUT_IN_PROGRESS, "A payout is on its way to your UPI ID.");
        }
        var id = ids.newId();
        long amount = java.util.Objects.requireNonNull(transactions.execute(status -> {
            var given = ledger.forfeit(id, user.id()).value();
            if (given > 0) {
                jdbc.sql("""
                                insert into payouts.forfeits (id, user_id, amount_paise, statement, created_at)
                                values (:id, :user, :amount, :statement, :now)
                                """)
                        .param("id", id)
                        .param("user", user.id())
                        .param("amount", given)
                        .param("statement", request.statement().strip())
                        .param("now", Timestamp.from(clock.instant()))
                        .update();
                audit.record(new AuditTrail.Entry(
                        AuditTrail.Actor.owner(user.id()),
                        "earnings.forfeit",
                        "user",
                        user.id().toString(),
                        "gave up unpaid earnings in writing to delete the account",
                        null,
                        Map.of("amountPaise", given)));
            }
            return given;
        }));
        return new Forfeited(amount);
    }
}
