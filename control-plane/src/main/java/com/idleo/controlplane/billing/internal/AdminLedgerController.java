package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ledger explorer (ADR 0055): the chart of accounts with each account's balance at any moment, for the platform or
 * one owner; every entry, filtered and exported through the list engine (ADR 0052); one transaction with every leg and
 * the record it came from; and the trial balance. It only reads.
 */
@RestController
@RequestMapping("/admin/v1/ledger")
@ConditionalOnWebApplication
class AdminLedgerController {

    private final LedgerExplorer explorer;
    private final JdbcClient jdbc;
    private final Clock clock;

    AdminLedgerController(LedgerExplorer explorer, JdbcClient jdbc, Clock clock) {
        this.explorer = explorer;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * One account at a moment: its type, its normal side ({@code credit} for liabilities and income), what it holds,
     * its debits and credits up to then, and its balance on its normal side.
     */
    record AdminLedgerAccount(
            String account,
            String type,
            boolean owned,
            String normalSide,
            String description,
            long debitsPaise,
            long creditsPaise,
            long balancePaise) {}

    record AdminChartOfAccounts(Instant at, @Nullable UUID owner, List<AdminLedgerAccount> accounts) {}

    /** The trial balance at a moment: each account's net in the debit or the credit column, and the two totals. */
    record AdminTrialBalance(
            Instant at, List<AdminTrialBalanceRow> accounts, long debitsPaise, long creditsPaise, boolean balanced) {}

    record AdminTrialBalanceRow(String account, String type, long debitPaise, long creditPaise) {}

    /** One ledger entry: its transaction, account and owner, amount, direction, and what caused it. */
    record AdminLedgerEntry(
            UUID id,
            UUID txnId,
            String account,
            @Nullable UUID ownerId,
            long amountPaise,
            String direction,
            String refType,
            UUID refId,
            Instant createdAt) {}

    record AdminLedgerPage(
            List<AdminLedgerEntry> entries, @Nullable String nextCursor) {}

    /** Where a transaction came from: its cause, and the console page that holds it, when there is one. */
    record AdminLedgerSource(
            String refType,
            UUID refId,
            @Nullable String page,
            @Nullable String pageId) {}

    record AdminLedgerLeg(
            UUID id,
            String account,
            @Nullable UUID ownerId,
            long amountPaise,
            String direction,
            @Nullable UUID lotId) {}

    record AdminLedgerTransaction(UUID txnId, AdminLedgerSource source, Instant createdAt, List<AdminLedgerLeg> legs) {}

    /**
     * Every account and its balance at {@code at} (an ISO instant; now when not given), for the platform, or for one
     * {@code owner}: then only the accounts a renter or a host owns.
     */
    @RequiresPermission(AdminPermission.LEDGER_READ)
    @GetMapping("/accounts")
    AdminChartOfAccounts accounts(
            @RequestParam(required = false) @Nullable String at, @RequestParam(required = false) @Nullable UUID owner) {
        var moment = moment(at);
        return new AdminChartOfAccounts(
                moment,
                owner,
                explorer.balances(moment, owner).stream()
                        .map(balance -> new AdminLedgerAccount(
                                balance.account().dbValue,
                                balance.account().type.dbValue(),
                                balance.account().owned,
                                balance.account().raisedByCredits ? "credit" : "debit",
                                balance.account().description,
                                balance.debitsPaise(),
                                balance.creditsPaise(),
                                balance.balancePaise()))
                        .toList());
    }

    /** The trial balance at {@code at} (an ISO instant; now when not given). */
    @RequiresPermission(AdminPermission.LEDGER_READ)
    @GetMapping("/trial-balance")
    AdminTrialBalance trialBalance(@RequestParam(required = false) @Nullable String at) {
        var trial = explorer.trialBalance(moment(at));
        return new AdminTrialBalance(
                trial.at(),
                trial.accounts().stream()
                        .map(balance -> {
                            long net = balance.debitsPaise() - balance.creditsPaise();
                            return new AdminTrialBalanceRow(
                                    balance.account().dbValue,
                                    balance.account().type.dbValue(),
                                    Math.max(net, 0),
                                    Math.max(-net, 0));
                        })
                        .toList(),
                trial.debitsPaise(),
                trial.creditsPaise(),
                trial.balanced());
    }

    /** One transaction: every leg, and the record it came from. */
    @RequiresPermission(AdminPermission.LEDGER_READ)
    @GetMapping("/transactions/{id}")
    AdminLedgerTransaction transaction(@PathVariable UUID id) {
        var found = explorer.transaction(id)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no ledger transaction " + id + "."));
        var source = found.source();
        return new AdminLedgerTransaction(
                found.txnId(),
                new AdminLedgerSource(source.refType(), source.refId(), source.page(), source.pageId()),
                found.createdAt(),
                found.legs().stream()
                        .map(leg -> new AdminLedgerLeg(
                                leg.id(),
                                leg.account().dbValue,
                                leg.ownerId(),
                                leg.amountPaise(),
                                leg.direction(),
                                leg.lotId()))
                        .toList());
    }

    /**
     * Ledger entries, newest first: one transaction's ({@code txn}), or one account's, such as a renter's wallet
     * ({@code account=renter_wallet&owner=<user>}), optionally of one cause ({@code refType}) and between two moments
     * ({@code from}, {@code to}: ISO instants, {@code to} not included).
     */
    @RequiresPermission(AdminPermission.LEDGER_READ)
    @AdminList("ledger")
    @GetMapping
    AdminLedgerPage ledger(
            @RequestParam(required = false) @Nullable UUID txn,
            @RequestParam(required = false) @Nullable String account,
            @RequestParam(required = false) @Nullable UUID owner,
            @RequestParam(required = false) @Nullable String refType,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (txn == null && account == null) {
            throw ApiProblem.invalid("account", "Give a transaction, or an account and, for a wallet, its owner.");
        }
        String accountValue = null;
        if (account != null) {
            try {
                accountValue = Account.fromDb(account.strip()).dbValue;
            } catch (IllegalArgumentException e) {
                throw ApiProblem.invalid("account", "isn't one of the ledger's accounts.");
            }
        }
        var page = LEDGER.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "txn",
                        txn,
                        "account",
                        accountValue,
                        "owner",
                        owner,
                        "refType",
                        refType,
                        "from",
                        instant("from", from),
                        "to",
                        instant("to", to)),
                cursor,
                limit,
                (rs, n) -> new AdminLedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("txn_id", UUID.class),
                        rs.getString("account"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getLong("amount_paise"),
                        rs.getString("direction"),
                        rs.getString("ref_type"),
                        rs.getObject("ref_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()));
        return new AdminLedgerPage(page.rows(), page.nextCursor());
    }

    /** The ledger's entries, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LEDGER = AdminListing.of("ledger", AdminPermission.LEDGER_READ, true)
            .noBulk("Its rows are money, and money is never bulk.")
            .from("billing.ledger_entries")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sort("amount", AdminListing.Type.NUMBER, "amount_paise")
            .sortedBy("createdAt", true)
            .filter("txn", "txn_id = cast(:txn as uuid)")
            .filter("account", "account = cast(:account as text)")
            .filter("owner", "owner_id = cast(:owner as uuid)")
            .filter("refType", "ref_type = cast(:refType as text)")
            .filter("from", "created_at >= cast(:from as timestamptz)")
            .filter("to", "created_at < cast(:to as timestamptz)")
            .csv("id", "id")
            .csv("txnId", "txn_id")
            .csv("account", "account")
            .csv("ownerId", "owner_id")
            .csv("amountPaise", "amount_paise")
            .csv("direction", "direction")
            .csv("refType", "ref_type")
            .csv("refId", "ref_id")
            .csv("createdAt", "created_at")
            .build();

    private Instant moment(@Nullable String at) {
        var parsed = instant("at", at);
        return parsed == null ? clock.instant() : Instant.parse(parsed);
    }

    /** An ISO instant from the query, checked and kept as text for the list engine's parameters. */
    private static @Nullable String instant(String name, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip()).toString();
        } catch (DateTimeParseException e) {
            throw ApiProblem.invalid(name, "is a moment such as 2026-10-31T18:30:00Z.");
        }
    }
}
