package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.PersonalDataAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Invoices for the admin console (ADR 0039): find one by number or renter, download it, and issue a credit note
 * against it with a reason, audited. A credit note is a document only; money goes back through a refund.
 */
@RestController
@RequestMapping("/admin/v1/invoices")
@ConditionalOnWebApplication
class AdminInvoicesController {

    /** The list, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("invoices", AdminPermission.INVOICES_READ, true)
            .from("billing.invoices")
            .sort("issuedAt", AdminListing.Type.TIME, "issued_at")
            .sort("number", AdminListing.Type.TEXT, "number")
            .sort("total", AdminListing.Type.NUMBER, "total_paise")
            .sortedBy("issuedAt", true)
            .filter("number", "number = cast(:number as text)")
            .filter("user", "user_id = cast(:user as uuid)")
            .filter("kind", "kind = cast(:kind as text)")
            .filter("tagged", "user_id in (:tagged)")
            .csv("id", "id")
            .csv("number", "number")
            .csv("kind", "kind")
            .csv("userId", "user_id")
            .csv("totalPaise", "total_paise")
            .csv("issuedAt", "issued_at")
            .build();

    private final Invoices invoices;
    private final JdbcClient jdbc;
    private final AdminTagged tagged;
    private final InvoicePdf pdf;
    private final Approvals approvals;
    private final PersonalDataAccess accessLog;

    AdminInvoicesController(
            Invoices invoices,
            InvoicePdf pdf,
            Approvals approvals,
            PersonalDataAccess accessLog,
            JdbcClient jdbc,
            AdminTagged tagged) {
        this.jdbc = jdbc;
        this.tagged = tagged;
        this.invoices = invoices;
        this.pdf = pdf;
        this.approvals = approvals;
        this.accessLog = accessLog;
    }

    record AdminInvoiceList(
            java.util.List<AdminInvoice> invoices, @Nullable String nextCursor) {}

    record AdminInvoice(InvoiceController.InvoiceSummary invoice, UUID userId, long version) {}

    /** A credit note for {@code amountPaise} of the invoice, at most what's left of it, and why (5 to 500 characters). */
    record CreditNoteRequest(
            @Positive long amountPaise,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    @RequiresPermission(AdminPermission.INVOICES_READ)
    @GetMapping
    AdminInvoiceList search(
            @RequestParam(required = false) @Nullable String number,
            @RequestParam(required = false) @Nullable UUID userId,
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "number",
                        number == null ? null : number.strip().toUpperCase(java.util.Locale.ROOT),
                        "user",
                        userId,
                        "kind",
                        kind,
                        "tagged",
                        tagged.subjects("user", tag)),
                cursor,
                limit,
                (rs, n) -> invoices.mapped(rs));
        return new AdminInvoiceList(
                page.rows().stream()
                        .map(invoice -> new AdminInvoice(
                                InvoiceController.summary(invoice), invoice.userId(), version(invoice)))
                        .toList(),
                page.nextCursor());
    }

    @RequiresPermission(AdminPermission.INVOICES_READ)
    @GetMapping("/{id}")
    JsonNode get(@PathVariable UUID id) {
        return found(id).document();
    }

    /** The document as issued, the buyer's name and details whole: it can't be masked, so each download is logged. */
    @RequiresPermission(AdminPermission.INVOICES_PDF)
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "The document as a PDF",
            content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string")))
    ResponseEntity<byte[]> pdf(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        var invoice = found(id);
        accessLog.read(admin, invoice.userId(), "invoice_pdf", id.toString(), "downloaded invoice " + invoice.number());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + invoice.number() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.render(invoice.document()));
    }

    @RequiresPermission(value = AdminPermission.INVOICES_CREDIT_NOTE, record = "invoice")
    @PostMapping("/{id}/credit-notes")
    ResponseEntity<Approvals.AdminApproval> creditNote(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody CreditNoteRequest request) {
        var invoice = found(id);
        return approvals.submit(
                admin,
                BillingGatedActions.CREDIT_NOTE,
                "invoice",
                id.toString(),
                Map.of("id", id, "amountPaise", request.amountPaise()),
                List.of(new Approvals.ApprovalChange(
                        "credit note against " + invoice.number(), 0, request.amountPaise())),
                request.reason());
    }

    /** An invoice never changes: what a credit note can still take from it does, so its version counts them. */
    private long version(Invoices.Invoice invoice) {
        return 1 + invoices.creditNotes(invoice.id());
    }

    private Invoices.Invoice found(UUID id) {
        return invoices.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no invoice " + id + "."));
    }
}
