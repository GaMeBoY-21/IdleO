package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.PdfStyle;
import com.idleo.controlplane.Rupees;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * An invoice, receipt or credit note as a PDF (ADR 0039), made on the server from the document as it was issued, with
 * PDFBox (pinned in the pom) and the web app's own faces, Inter and Archivo, embedded from the jar ({@link PdfStyle}): a
 * white page, dark text, colour only for accents. Nothing is fetched while it renders, and the same document always makes
 * the same pages. Amounts are rupees with two decimals ({@link Rupees}).
 */
@Component
class InvoicePdf {

    private static final float MARGIN = 48;
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(Invoices.INDIA);

    byte[] render(JsonNode document) {
        try (var pdf = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var regular = PdfStyle.font(pdf, "Inter-Regular.ttf");
            var bold = PdfStyle.font(pdf, "Inter-Bold.ttf");
            var display = PdfStyle.font(pdf, "Archivo-Display.ttf");
            var page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            var info = pdf.getDocumentInformation();
            info.setTitle(text(document, "title") + " " + text(document, "number"));
            info.setProducer("IdleO");
            // Fixed, so the same document makes the same bytes.
            var issued = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            issued.setTimeInMillis(Instant.parse(text(document, "issuedAt")).toEpochMilli());
            info.setCreationDate(issued);
            info.setModificationDate(issued);
            try (var page1 = new PDPageContentStream(pdf, page)) {
                var y = PdfStyle.letterhead(page1, display, page.getMediaBox().getHeight() - MARGIN, MARGIN);
                var writer = new Writer(page1, y);
                writer.line(display, 18, text(document, "title").toUpperCase(Locale.ENGLISH));
                if (document.hasNonNull("notice")) {
                    writer.line(bold, 11, text(document, "notice"));
                }
                writer.gap(6);
                writer.line(regular, 10, "Number " + text(document, "number"));
                writer.line(regular, 10, "Issued " + DAY.format(Instant.parse(text(document, "issuedAt"))));
                if (document.hasNonNull("periodStart")) {
                    writer.line(
                            regular,
                            10,
                            "For " + DAY.format(Instant.parse(text(document, "periodStart"))) + " to "
                                    + DAY.format(Instant.parse(text(document, "periodEnd"))
                                            .minusSeconds(1)));
                }
                if (document.path("creditNote").hasNonNull("against")) {
                    writer.line(regular, 10, "Against " + text(document.path("creditNote"), "against"));
                }
                writer.gap(10);
                var seller = document.path("seller");
                writer.label(bold, 11, "From");
                writer.line(regular, 10, text(seller, "name"));
                for (var part : text(seller, "address").split("\n")) {
                    if (!part.isBlank()) {
                        writer.line(regular, 10, part.strip());
                    }
                }
                if (seller.hasNonNull("gstin")) {
                    writer.line(regular, 10, "GSTIN " + text(seller, "gstin"));
                }
                writer.line(regular, 10, "State " + text(seller, "stateCode") + " " + text(seller, "state"));
                writer.gap(8);
                var buyer = document.path("buyer");
                writer.label(bold, 11, "To");
                if (buyer.hasNonNull("businessName")) {
                    writer.line(regular, 10, text(buyer, "businessName"));
                }
                writer.line(regular, 10, "IdleO account " + text(buyer, "accountId"));
                if (buyer.hasNonNull("gstin")) {
                    writer.line(regular, 10, "GSTIN " + text(buyer, "gstin"));
                }
                writer.line(regular, 10, "Place of supply " + text(document, "placeOfSupply"));
                writer.gap(12);
                for (var line : document.path("lines")) {
                    writer.line(bold, 10, text(line, "description"));
                    if (!text(line, "sac").isBlank()) {
                        writer.line(regular, 10, "SAC " + text(line, "sac"));
                    }
                }
                writer.gap(8);
                var totals = document.path("totals");
                writer.amount(
                        regular, "Taxable value", totals.path("taxablePaise").asLong());
                var rate = document.path("lines").path(0).path("gstPercent").asInt();
                if (totals.path("cgstPaise").asLong() > 0
                        || totals.path("sgstPaise").asLong() > 0) {
                    writer.amount(
                            regular,
                            "CGST at " + rate / 2.0 + "%",
                            totals.path("cgstPaise").asLong());
                    writer.amount(
                            regular,
                            "SGST at " + rate / 2.0 + "%",
                            totals.path("sgstPaise").asLong());
                }
                if (totals.path("igstPaise").asLong() > 0) {
                    writer.amount(
                            regular,
                            "IGST at " + rate + "%",
                            totals.path("igstPaise").asLong());
                }
                writer.amount(bold, "Total", totals.path("totalPaise").asLong());
                writer.gap(10);
                var payment = document.path("payment");
                if (payment.hasNonNull("razorpayPaymentId")) {
                    writer.line(
                            regular,
                            9,
                            "Paid through Razorpay, payment " + text(payment, "razorpayPaymentId")
                                    + (payment.hasNonNull("method") ? " (" + text(payment, "method") + ")" : ""));
                }
                if (document.path("creditNote").hasNonNull("reason")) {
                    writer.line(regular, 9, "Reason: " + text(document.path("creditNote"), "reason"));
                }
                writer.line(regular, 9, text(document, "closedLoop"));
            }
            // A document id of its own number, never a random one, so the same document makes the same bytes.
            var id = java.security.MessageDigest.getInstance("MD5")
                    .digest(text(document, "number").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var ids = new org.apache.pdfbox.cos.COSArray();
            ids.add(new org.apache.pdfbox.cos.COSString(id));
            ids.add(new org.apache.pdfbox.cos.COSString(id));
            pdf.getDocument().getTrailer().setItem(org.apache.pdfbox.cos.COSName.ID, ids);
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("an invoice couldn't be rendered", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isNull() || value.isMissingNode() ? "" : value.asString();
    }

    /** Writes lines down the page, and amounts right-aligned. */
    private static final class Writer {
        private final PDPageContentStream stream;
        private float y;

        Writer(PDPageContentStream stream, float y) {
            this.stream = stream;
            this.y = y;
        }

        void gap(float points) {
            y -= points;
        }

        /** A small heading in the accent colour; the text after it is the ink again. */
        void label(PDType0Font font, float size, String text) throws IOException {
            stream.setNonStrokingColor(PdfStyle.ACCENT);
            line(font, size, text);
            stream.setNonStrokingColor(PdfStyle.INK);
        }

        void line(PDType0Font font, float size, String text) throws IOException {
            for (var piece : wrap(font, size, text, PDRectangle.A4.getWidth() - 2 * MARGIN)) {
                y -= size * 1.45f;
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(MARGIN, y);
                stream.showText(piece);
                stream.endText();
            }
        }

        void amount(PDType0Font font, String label, long paise) throws IOException {
            y -= 15;
            var money = Rupees.format(paise);
            stream.beginText();
            stream.setFont(font, 10);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(label);
            stream.endText();
            float width = font.getStringWidth(money) / 1000 * 10;
            stream.beginText();
            stream.setFont(font, 10);
            stream.newLineAtOffset(PDRectangle.A4.getWidth() - MARGIN - width, y);
            stream.showText(money);
            stream.endText();
        }

        private static List<String> wrap(PDType0Font font, float size, String text, float width) throws IOException {
            var lines = new ArrayList<String>();
            var current = new StringBuilder();
            for (var word : text.split(" ")) {
                var tried = current.isEmpty() ? word : current + " " + word;
                if (font.getStringWidth(tried) / 1000 * size > width && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(tried);
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }
    }
}
