package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    private final Invoices invoices;
    private final InvoicePdf pdf;
    private final AuditTrail audit;

    AdminInvoicesController(Invoices invoices, InvoicePdf pdf, AuditTrail audit) {
        this.invoices = invoices;
        this.pdf = pdf;
        this.audit = audit;
    }

    record AdminInvoiceList(java.util.List<AdminInvoice> invoices) {}

    record AdminInvoice(InvoiceController.InvoiceSummary invoice, UUID userId) {}

    /** A credit note for {@code amountPaise} of the invoice, at most what's left of it, and why (5 to 500 characters). */
    record CreditNoteRequest(
            @Positive long amountPaise,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    @GetMapping
    AdminInvoiceList search(
            @RequestParam(required = false) @Nullable String number,
            @RequestParam(required = false) @Nullable UUID userId) {
        return new AdminInvoiceList(invoices.search(number, userId, 50).stream()
                .map(invoice -> new AdminInvoice(InvoiceController.summary(invoice), invoice.userId()))
                .toList());
    }

    @GetMapping("/{id}")
    JsonNode get(@PathVariable UUID id) {
        return found(id).document();
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "The document as a PDF",
            content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string")))
    ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        var invoice = found(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + invoice.number() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.render(invoice.document()));
    }

    @PostMapping("/{id}/credit-notes")
    AdminInvoice creditNote(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody CreditNoteRequest request) {
        found(id);
        try {
            var note = invoices.creditNoteByAdmin(
                    id, Paise.of(request.amountPaise()), request.reason(), audit.admin(admin));
            return new AdminInvoice(InvoiceController.summary(note), note.userId());
        } catch (IllegalArgumentException refused) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, refused.getMessage());
        }
    }

    private Invoices.Invoice found(UUID id) {
        return invoices.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no invoice " + id + "."));
    }
}
