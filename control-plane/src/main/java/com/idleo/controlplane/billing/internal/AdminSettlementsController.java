package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
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
 * Razorpay's settlements for the console (ADR 0055): each settlement and whether it matches, the items it carried and
 * how each matches the ledger, fetching a day now, and resolving an item that doesn't match.
 */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnWebApplication
class AdminSettlementsController {

    private final RazorpaySettlements settlements;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    AdminSettlementsController(RazorpaySettlements settlements, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.settlements = settlements;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * A settlement: what it moved to the bank, Razorpay's fees and their tax, its UTR, whether it matches ({@code
     * matched}, {@code partly} or {@code unmatched}), and the ledger transaction that posted it once processed.
     */
    record AdminRazorpaySettlement(
            String id,
            long amountPaise,
            long feesPaise,
            long taxPaise,
            String status,
            @Nullable String utr,
            Instant settledAt,
            String state,
            @Nullable UUID txnId) {}

    record AdminSettlementPage(
            List<AdminRazorpaySettlement> settlements,
            @Nullable String nextCursor) {}

    /**
     * One item a settlement carried, or a payment of ours no settlement carried: what Razorpay says, what we have, how
     * they match, and how it was resolved.
     */
    record AdminSettlementItem(
            UUID id,
            @Nullable String settlementId,
            String entityId,
            String type,
            long creditPaise,
            long debitPaise,
            long feePaise,
            long taxPaise,
            @Nullable Long ourPaise,
            String match,
            String state,
            @Nullable String resolution,
            @Nullable UUID adjustmentId,
            @Nullable String reason,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt,
            Instant foundAt,
            long version) {}

    record AdminSettlementItemPage(
            List<AdminSettlementItem> items, @Nullable String nextCursor) {}

    /** Fetching a day (YYYY-MM-DD in India; today when not given) now, with why. */
    record AdminSettlementFetch(
            @Nullable String day,
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    record AdminSettlementFetched(String day, int settlements, int items, int open) {}

    /**
     * Resolving an item: as a {@code known_difference}, or by an approved {@code adjustment} whose reference names the
     * item; with why.
     */
    record AdminSettlementResolve(
            @NotBlank @Schema(allowableValues = {"known_difference", "adjustment"})
            String resolution,

            @Nullable UUID adjustmentId,
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    @RequiresPermission(AdminPermission.RECON_READ)
    @AdminList("settlements")
    @GetMapping("/settlements")
    AdminSettlementPage list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page =
                LIST.page(jdbc, AdminListing.Asked.of(sort, "state", state), cursor, limit, (rs, n) -> settlement(rs));
        return new AdminSettlementPage(page.rows(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.RECON_READ)
    @GetMapping("/settlements/{id}")
    AdminRazorpaySettlement one(@PathVariable String id) {
        return jdbc.sql("select * from billing.settlements where id = :id")
                .param("id", id)
                .query((rs, n) -> settlement(rs))
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no settlement " + id + "."));
    }

    /** The items, open ones first by default: one settlement's ({@code settlement}), or by state and match. */
    @RequiresPermission(AdminPermission.RECON_READ)
    @AdminList("settlement-items")
    @GetMapping("/settlement-items")
    AdminSettlementItemPage items(
            @RequestParam(required = false) @Nullable String settlement,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String match,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = ITEMS.page(
                jdbc,
                AdminListing.Asked.of(sort, "settlement", settlement, "state", state, "match", match),
                cursor,
                limit,
                (rs, n) -> item(rs));
        return new AdminSettlementItemPage(page.rows(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.RECON_READ)
    @GetMapping("/settlement-items/{id}")
    AdminSettlementItem item(@PathVariable UUID id) {
        return jdbc.sql("select * from billing.settlement_items where id = :id")
                .param("id", id)
                .query((rs, n) -> item(rs))
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no settlement item " + id + "."));
    }

    /** Fetches a day's settlements now, as the nightly run does, audited. */
    @RequiresPermission(AdminPermission.RECONCILIATION_RUN)
    @PostMapping("/settlements/fetch")
    AdminSettlementFetched fetch(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminSettlementFetch request) {
        LocalDate day;
        try {
            day = request.day() == null || request.day().isBlank()
                    ? null
                    : LocalDate.parse(request.day().strip());
        } catch (DateTimeParseException e) {
            throw ApiProblem.invalid("day", "is a date such as 2026-10-31.");
        }
        try {
            var fetched = settlements.fetch(
                    day == null ? LocalDate.ofInstant(clock.instant(), Periods.INDIA) : day,
                    audit.admin(admin),
                    request.reason());
            return new AdminSettlementFetched(
                    fetched.day().toString(), fetched.settlements(), fetched.items(), fetched.open());
        } catch (RazorpaySettlements.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        } catch (RazorpayClient.Unreachable e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "Razorpay couldn't be reached; try again in a minute.");
        }
    }

    /** Resolves an item that doesn't match: a known difference with why, or the approved adjustment that put it right. */
    @RequiresPermission(value = AdminPermission.RECON_RESOLVE, record = "settlement_item")
    @PostMapping("/settlement-items/{id}/resolve")
    AdminSettlementItem resolve(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminSettlementResolve request) {
        try {
            switch (request.resolution()) {
                case "known_difference" -> settlements.known(id, request.reason(), audit.admin(admin));
                case "adjustment" -> {
                    if (request.adjustmentId() == null) {
                        throw ApiProblem.invalid("adjustmentId", "names the adjustment that put it right.");
                    }
                    settlements.adjusted(id, request.adjustmentId(), request.reason(), audit.admin(admin));
                }
                default -> throw ApiProblem.invalid("resolution", "is known_difference or adjustment.");
            }
        } catch (RazorpaySettlements.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        }
        return item(id);
    }

    /** The settlements, newest first, their filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("settlements", AdminPermission.RECON_READ, false)
            .noBulk("Its rows are money, and money is never bulk.")
            .from("billing.settlements")
            .id("id", AdminListing.Type.TEXT)
            .sort("settledAt", AdminListing.Type.TIME, "settled_at")
            .sort("amount", AdminListing.Type.NUMBER, "amount_paise")
            .sortedBy("settledAt", true)
            .filter("state", "state = cast(:state as text)")
            .csv("id", "id")
            .csv("amountPaise", "amount_paise")
            .csv("feesPaise", "fees_paise")
            .csv("taxPaise", "tax_paise")
            .csv("status", "status")
            .csv("utr", "utr")
            .csv("settledAt", "settled_at")
            .csv("state", "state")
            .csv("txnId", "txn_id")
            .build();

    /** The settlement items, oldest first, their filters, orders and CSV (ADR 0052). */
    static final AdminListing ITEMS = AdminListing.of("settlement-items", AdminPermission.RECON_READ, false)
            .noBulk("Its rows are money, and money is never bulk.")
            .from("billing.settlement_items")
            .sort("foundAt", AdminListing.Type.UUID, "id")
            .sortedBy("foundAt", false)
            .filter("settlement", "settlement_id = cast(:settlement as text)")
            .filter("state", "state = cast(:state as text)")
            .filter("match", "match = cast(:match as text)")
            .csv("id", "id")
            .csv("settlementId", "settlement_id")
            .csv("entityId", "entity_id")
            .csv("type", "type")
            .csv("creditPaise", "credit_paise")
            .csv("debitPaise", "debit_paise")
            .csv("feePaise", "fee_paise")
            .csv("taxPaise", "tax_paise")
            .csv("ourPaise", "our_paise")
            .csv("match", "match")
            .csv("state", "state")
            .csv("resolution", "resolution")
            .csv("adjustmentId", "adjustment_id")
            .csv("reason", "reason")
            .build();

    private static AdminRazorpaySettlement settlement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdminRazorpaySettlement(
                rs.getString("id"),
                rs.getLong("amount_paise"),
                rs.getLong("fees_paise"),
                rs.getLong("tax_paise"),
                rs.getString("status"),
                rs.getString("utr"),
                rs.getTimestamp("settled_at").toInstant(),
                rs.getString("state"),
                rs.getObject("txn_id", UUID.class));
    }

    private static AdminSettlementItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
        var resolvedAt = rs.getTimestamp("resolved_at");
        return new AdminSettlementItem(
                rs.getObject("id", UUID.class),
                rs.getString("settlement_id"),
                rs.getString("entity_id"),
                rs.getString("type"),
                rs.getLong("credit_paise"),
                rs.getLong("debit_paise"),
                rs.getLong("fee_paise"),
                rs.getLong("tax_paise"),
                rs.getObject("our_paise", Long.class),
                rs.getString("match"),
                rs.getString("state"),
                rs.getString("resolution"),
                rs.getObject("adjustment_id", UUID.class),
                rs.getString("reason"),
                rs.getString("resolved_by"),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                rs.getTimestamp("found_at").toInstant(),
                rs.getLong("version"));
    }
}
