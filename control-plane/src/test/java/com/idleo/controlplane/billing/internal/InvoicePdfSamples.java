package com.idleo.controlplane.billing.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a sample of each kind of document as a PDF, for the product owner to look at (web/e2e/screenshots.ts lays them
 * out in the contact sheet): {@code ./mvnw test -Dtest=InvoicePdfSamples -Didleo.pdfs.out=<folder>}. It does nothing without
 * the property. Every figure and name is invented.
 */
@EnabledIfSystemProperty(named = "idleo.pdfs.out", matches = ".+")
class InvoicePdfSamples {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void writesATaxInvoiceAReceiptAndACreditNote() throws Exception {
        var folder = Path.of(System.getProperty("idleo.pdfs.out"));
        Files.createDirectories(folder);
        var pdf = new InvoicePdf();
        picture(folder.resolve("tax-invoice.pdf"), pdf.render(document("Tax invoice", "IDL/2026-27/000042", true)));
        picture(folder.resolve("receipt.pdf"), pdf.render(document("Receipt", "RCT/2026-27/000042", false)));
        var note = document("Credit note", "CN/2026-27/000007", true);
        note.putObject("creditNote").put("against", "IDL/2026-27/000042").put("reason", "A refund after a report.");
        note.remove("payment");
        picture(folder.resolve("credit-note.pdf"), pdf.render(note));
    }

    /** The document's first page as a picture, 110 dots an inch, beside it. */
    private static void picture(Path pdf, byte[] bytes) throws Exception {
        Files.write(pdf, bytes);
        try (var read = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            var image = new org.apache.pdfbox.rendering.PDFRenderer(read).renderImageWithDPI(0, 110);
            javax.imageio.ImageIO.write(
                    image,
                    "png",
                    pdf.resolveSibling(pdf.getFileName().toString().replace(".pdf", ".png"))
                            .toFile());
        }
    }

    private ObjectNode document(String title, String number, boolean registered) {
        var root = json.createObjectNode();
        root.put("title", title);
        root.put("number", number);
        root.put("issuedAt", "2026-10-27T10:30:00Z");
        if (!registered) {
            root.put("notice", "This is not a tax invoice.");
        }
        var seller = root.putObject("seller");
        seller.put("name", "IdleO (development placeholder)");
        seller.put("address", "Not a real address\nDevelopment only");
        if (registered) {
            seller.put("gstin", "29AAAAA0000A1Z5");
        }
        seller.put("stateCode", "29");
        seller.put("state", "Karnataka");
        var buyer = root.putObject("buyer");
        buyer.put("accountId", "0199f0a2-7c1e-7d3a-9b2f-3c4d5e6f7a8b");
        if (registered) {
            buyer.put("businessName", "Asha Rao Labs");
            buyer.put("gstin", "27BBBBB1111B1Z6");
        }
        root.put("placeOfSupply", "27 Maharashtra");
        var line = root.putArray("lines").addObject();
        line.put("description", "IdleO credit top-up");
        line.put("sac", "998439");
        line.put("gstPercent", 18);
        var totals = root.putObject("totals");
        totals.put("taxablePaise", 10_000);
        totals.put("cgstPaise", 0);
        totals.put("sgstPaise", 0);
        totals.put("igstPaise", 1_800);
        totals.put("totalPaise", 11_800);
        root.putObject("payment").put("razorpayPaymentId", "pay_TestSample0001").put("method", "upi");
        root.put("closedLoop", "IdleO credit is spent only on IdleO; it can't be withdrawn as cash or transferred.");
        return root;
    }
}
