package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.PersonalDataMask;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.PersonalDataAccess;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A CSV of any list, exactly as it was filtered (ADR 0052). It needs {@code export.create} and a recent factor, and the
 * list's own read permission; one holding personal data waits for another admin (ADR 0051). Every value is masked, and
 * the file is streamed, up to {@value #MOST_ROWS} rows, and says how many were left out. Each one is logged and audited.
 */
@RestController
@ConditionalOnWebApplication
class AdminListExportController {

    static final String EXPORT = "list.export";
    static final int MOST_ROWS = 50_000;

    private final Map<String, AdminListing> listings;
    private final JdbcClient jdbc;
    private final AdminAccess access;
    private final AuditTrail audit;
    private final PersonalDataAccess accessLog;
    private final Approvals approvals;
    private final AdminTagged tagged;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate writing;

    AdminListExportController(
            List<AdminListing> listings,
            JdbcClient jdbc,
            AdminAccess access,
            AuditTrail audit,
            PersonalDataAccess accessLog,
            Approvals approvals,
            AdminTagged tagged,
            PlatformTransactionManager transactionManager) {
        this.listings =
                listings.stream().collect(Collectors.toUnmodifiableMap(AdminListing::name, Function.identity()));
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.accessLog = accessLog;
        this.approvals = approvals;
        this.tagged = tagged;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.writing = new TransactionTemplate(transactionManager);
    }

    /**
     * @param list the list's name: users, hosts, sessions, payments, refunds, invoices, payouts, tickets, reports, abuse,
     *     images, chargebacks, clawbacks, ratings or invites
     * @param filters the list's filters, as its screen gave them
     */
    record ExportRequest(
            @NotBlank @Size(max = 40) String list,
            @Nullable Map<String, String> filters,
            @Size(max = 60) @Nullable String sort,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    private AdminListing listing(String name, CurrentUser admin) {
        var listing = listings.get(name);
        if (listing == null) {
            throw ApiProblem.of(Problem.NOT_FOUND, "There's no such list.");
        }
        if (!access.permissions(admin.id()).contains(listing.permission())) {
            throw ApiProblem.of(
                    Problem.ADMIN_PERMISSION_REQUIRED,
                    "You need the " + listing.permission().key() + " permission.");
        }
        return listing;
    }

    /** What an export's filters mean: only those the list has, and a tag as the ids that carry it. */
    private AdminListing.Asked asked(
            AdminListing listing, @Nullable Map<String, String> filters, @Nullable String sort) {
        var given = new LinkedHashMap<String, @Nullable Object>();
        if (filters != null) {
            filters.forEach((name, value) -> {
                if (name.equals("tag")) {
                    given.put("tagged", tagged.subjects(listing.name().equals("hosts") ? "host" : "user", value));
                } else if (listing.filterNames().contains(name)) {
                    given.put(name, value);
                } else {
                    throw ApiProblem.invalid("filters", listing.name() + " can't be filtered by " + name + ".");
                }
            });
        }
        return new AdminListing.Asked(given, sort);
    }

    /** Writes the file. Row by row, so it holds none of it in memory; {@code out} gets each row as it's read. */
    private long write(AdminListing listing, AdminListing.Asked asked, OutputStream out) {
        var total = listing.count(jdbc, asked);
        var header = listing.header();
        line(out, header);
        readOnly.executeWithoutResult(status -> listing.each(jdbc, asked, MOST_ROWS, row -> {
            var masked = new ArrayList<@Nullable String>(row.size());
            for (int i = 0; i < row.size(); i++) {
                var value = row.get(i);
                masked.add(value == null ? null : PersonalDataMask.field(header.get(i), value));
            }
            line(out, masked);
        }));
        var leftOut = Math.max(0, total - MOST_ROWS);
        if (leftOut > 0) {
            line(out, List.of("# " + leftOut + " more rows were left out: a file holds at most " + MOST_ROWS));
        }
        return Math.min(total, MOST_ROWS);
    }

    private static void line(OutputStream out, List<@Nullable String> values) {
        try {
            out.write((values.stream().map(AdminListExportController::cell).collect(Collectors.joining(",")) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One CSV cell, quoted when it needs it, and never read by a spreadsheet as a formula. */
    static String cell(@Nullable String value) {
        if (value == null) {
            return "";
        }
        var safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return safe.matches("(?s).*[\",\n\r].*") ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    private void record(CurrentUser admin, AdminListing listing, long rows, String reason) {
        accessLog.exported(admin, listing.name(), reason);
        audit.record(new AuditTrail.Entry(
                audit.admin(admin),
                EXPORT,
                "list",
                listing.name(),
                reason,
                null,
                Map.of("rows", String.valueOf(rows))));
    }

    /**
     * One list's CSV. A list without personal data answers with the file. One with it answers 202 with the approval, and
     * its requester fetches the file once it's approved.
     */
    @RequiresPermission(value = AdminPermission.EXPORT_CREATE, shownOnce = true)
    @PostMapping("/admin/v1/exports")
    @ApiResponse(responseCode = "200", description = "The CSV", content = @Content(mediaType = "text/csv"))
    @ApiResponse(
            responseCode = "202",
            description = "It goes to another admin",
            content = @Content(schema = @Schema(implementation = Approvals.AdminApproval.class)))
    @Nullable
    ResponseEntity<Approvals.AdminApproval> export(
            @AuthenticationPrincipal CurrentUser admin,
            @Valid @RequestBody ExportRequest request,
            @Parameter(hidden = true) HttpServletResponse response)
            throws IOException {
        var listing = listing(request.list(), admin);
        var asked = asked(listing, request.filters(), request.sort());
        if (listing.personalData()) {
            return approvals.submit(
                    admin,
                    EXPORT,
                    null,
                    null,
                    request,
                    List.of(new Approvals.ApprovalChange(
                            "a file of the " + listing.name() + " list", null, "made once")),
                    request.reason());
        }
        var total = Math.min(listing.count(jdbc, asked), MOST_ROWS);
        writing.executeWithoutResult(status -> record(admin, listing, total, request.reason()));
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + listing.name() + ".csv\"");
        write(listing, asked, response.getOutputStream());
        return null;
    }

    /** The gated export: made when it's approved, as its requester asked for it. */
    @Bean
    Approvals.GatedAction exportList() {
        return Approvals.fileAction(EXPORT, AdminPermission.EXPORT_CREATE, Approvals.Cooling.MONEY, run -> {
            var name = run.params().get("list").asString();
            var listing = listings.get(name);
            var filters = new LinkedHashMap<String, String>();
            var given = run.params().get("filters");
            if (given != null && given.isObject()) {
                given.properties()
                        .forEach(entry ->
                                filters.put(entry.getKey(), entry.getValue().asString()));
            }
            var sort = run.params().get("sort");
            var bytes = new ByteArrayOutputStream();
            var rows = write(
                    listing, asked(listing, filters, sort == null || sort.isNull() ? null : sort.asString()), bytes);
            audit.record(new AuditTrail.Entry(
                    run.actor(), EXPORT, "list", name, run.reason(), null, Map.of("rows", String.valueOf(rows))));
            return new Approvals.File(bytes.toByteArray(), "text/csv", name + ".csv");
        });
    }
}
