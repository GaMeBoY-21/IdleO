package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A renter's invoices, receipts and credit notes (ADR 0039), from Credit, History: the list, each as JSON (the
 * document exactly as issued) and as a PDF. Only their own.
 */
@RestController
@RequestMapping("/v1/wallet/invoices")
class InvoiceController {

    private final Invoices invoices;
    private final InvoicePdf pdf;

    InvoiceController(Invoices invoices, InvoicePdf pdf) {
        this.invoices = invoices;
        this.pdf = pdf;
    }

    /**
     * One document: {@code kind} is {@code tax_invoice}, {@code receipt}, {@code usage_invoice} or {@code credit_note};
     * {@code topUpId} the top-up it's for, if any.
     */
    record InvoiceSummary(
            UUID id,
            String number,
            String kind,
            String title,
            @Nullable UUID topUpId,
            @Nullable UUID creditNoteFor,
            long taxablePaise,
            long taxPaise,
            long totalPaise,
            Instant issuedAt) {}

    record InvoiceList(List<InvoiceSummary> invoices) {}

    static InvoiceSummary summary(Invoices.Invoice invoice) {
        return new InvoiceSummary(
                invoice.id(),
                invoice.number(),
                invoice.kind().dbValue,
                invoice.document().path("title").asString(),
                invoice.topUpId(),
                invoice.creditNoteFor(),
                invoice.taxable().value(),
                invoice.cgst().plus(invoice.sgst()).plus(invoice.igst()).value(),
                invoice.total().value(),
                invoice.issuedAt());
    }

    @GetMapping
    InvoiceList list(@AuthenticationPrincipal CurrentUser user) {
        return new InvoiceList(invoices.ofUser(user.id()).stream()
                .map(InvoiceController::summary)
                .toList());
    }

    /** The document as issued: seller, buyer, lines, totals, all money in paise. */
    @GetMapping("/{id}")
    JsonNode get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return own(user, id).document();
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "The document as a PDF",
            content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string")))
    ResponseEntity<byte[]> pdf(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        var invoice = own(user, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + invoice.number() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.render(invoice.document()));
    }

    private Invoices.Invoice own(CurrentUser user, UUID id) {
        return invoices.find(id)
                .filter(invoice -> invoice.userId().equals(user.id()))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no invoice " + id + "."));
    }
}
