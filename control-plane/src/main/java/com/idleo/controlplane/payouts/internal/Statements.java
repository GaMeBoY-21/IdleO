package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.FinancialYear;
import com.idleo.controlplane.Rupees;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A host's yearly payout statement (ADR 0040), for Hosting, Earnings: every payout of a financial year that didn't come
 * back, with its gross, the tax deducted at source and the section it was deducted under, any fee, and the net paid;
 * then the year's totals. As CSV, and as a PDF drawn with PDFBox and the embedded Plus Jakarta Sans (as invoices are).
 * The PAN shows only by its last four characters.
 */
@Component
class Statements {

    private static final float MARGIN = 48;
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(FinancialYear.INDIA);

    private final JdbcClient jdbc;
    private final Pans pans;

    Statements(JdbcClient jdbc, Pans pans) {
        this.jdbc = jdbc;
        this.pans = pans;
    }

    /** One payout of the year. {@code status} is processed, or on its way. */
    record Line(
            UUID payoutId,
            Instant createdAt,
            @Nullable Instant processedAt,
            String status,
            long grossPaise,
            long tdsPaise,
            String tdsSection,
            long feePaise,
            long netPaise) {}

    record Statement(
            FinancialYear year,
            @Nullable String panLast4,
            @Nullable String panName,
            List<Line> lines,
            long grossPaise,
            long tdsPaise,
            long feePaise,
            long netPaise) {}

    Statement of(UUID userId, FinancialYear year) {
        var lines = jdbc.sql("""
                        select id, created_at, processed_at, status, gross_paise, tds_paise, tds_section, fee_paise,
                               net_paise
                        from payouts.payouts
                        where user_id = :user and financial_year = :year and status not in (:returned)
                        order by created_at, id
                        """)
                .param("user", userId)
                .param("year", year.name())
                .param("returned", TaxDeduction.RETURNED)
                .query((rs, n) -> new Line(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("processed_at")),
                        rs.getString("status").equals("processed") ? "processed" : "on its way",
                        rs.getLong("gross_paise"),
                        rs.getLong("tds_paise"),
                        rs.getString("tds_section"),
                        rs.getLong("fee_paise"),
                        rs.getLong("net_paise")))
                .list();
        var pan = pans.find(userId).orElse(null);
        return new Statement(
                year,
                pan == null ? null : pan.last4(),
                pan == null ? null : pan.name(),
                lines,
                lines.stream().mapToLong(Line::grossPaise).sum(),
                lines.stream().mapToLong(Line::tdsPaise).sum(),
                lines.stream().mapToLong(Line::feePaise).sum(),
                lines.stream().mapToLong(Line::netPaise).sum());
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Amounts in paise and in rupees, so a spreadsheet adds them exactly. */
    byte[] csv(Statement statement) {
        var out = new StringBuilder();
        out.append("payout,created,processed,status,gross_paise,tds_paise,tds_section,fee_paise,net_paise,gross,tds,"
                + "net\n");
        for (var line : statement.lines()) {
            out.append(line.payoutId())
                    .append(',')
                    .append(line.createdAt())
                    .append(',')
                    .append(line.processedAt() == null ? "" : line.processedAt().toString())
                    .append(',')
                    .append(line.status())
                    .append(',')
                    .append(line.grossPaise())
                    .append(',')
                    .append(line.tdsPaise())
                    .append(',')
                    .append(line.tdsSection())
                    .append(',')
                    .append(line.feePaise())
                    .append(',')
                    .append(line.netPaise())
                    .append(',')
                    .append(quote(Rupees.format(line.grossPaise())))
                    .append(',')
                    .append(quote(Rupees.format(line.tdsPaise())))
                    .append(',')
                    .append(quote(Rupees.format(line.netPaise())))
                    .append('\n');
        }
        out.append("total,,,,")
                .append(statement.grossPaise())
                .append(',')
                .append(statement.tdsPaise())
                .append(",,")
                .append(statement.feePaise())
                .append(',')
                .append(statement.netPaise())
                .append(',')
                .append(quote(Rupees.format(statement.grossPaise())))
                .append(',')
                .append(quote(Rupees.format(statement.tdsPaise())))
                .append(',')
                .append(quote(Rupees.format(statement.netPaise())))
                .append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    byte[] pdf(Statement statement) {
        try (var pdf = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var regular = font(pdf, "/fonts/PlusJakartaSans-Regular.ttf");
            var bold = font(pdf, "/fonts/PlusJakartaSans-Bold.ttf");
            var info = pdf.getDocumentInformation();
            info.setTitle("IdleO payout statement " + statement.year().name());
            info.setProducer("IdleO");
            var page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            var stream = new PDPageContentStream(pdf, page);
            float y = page.getMediaBox().getHeight() - MARGIN;
            try {
                y = text(stream, bold, 20, y, "Payout statement");
                y = text(
                        stream,
                        regular,
                        10,
                        y,
                        "Financial year " + statement.year().name() + " (1 April to 31 March)");
                if (statement.panLast4() != null) {
                    y = text(
                            stream,
                            regular,
                            10,
                            y,
                            Objects.requireNonNull(statement.panName()) + ", PAN ending " + statement.panLast4());
                }
                y -= 10;
                y = amount(stream, bold, y, "Gross paid out", statement.grossPaise());
                y = amount(stream, regular, y, "Tax deducted at source", statement.tdsPaise());
                if (statement.feePaise() > 0) {
                    y = amount(stream, regular, y, "Payout fees", statement.feePaise());
                }
                y = amount(stream, bold, y, "Net paid to your UPI ID", statement.netPaise());
                y -= 14;
                y = text(stream, bold, 11, y, "Payouts");
                if (statement.lines().isEmpty()) {
                    y = text(stream, regular, 10, y, "No payouts in this year.");
                }
                for (var line : statement.lines()) {
                    if (y < MARGIN + 40) {
                        stream.close();
                        page = new PDPage(PDRectangle.A4);
                        pdf.addPage(page);
                        stream = new PDPageContentStream(pdf, page);
                        y = page.getMediaBox().getHeight() - MARGIN;
                    }
                    y = text(
                            stream,
                            regular,
                            9,
                            y,
                            DAY.format(line.createdAt()) + "  " + line.status() + "  gross "
                                    + Rupees.format(line.grossPaise()) + ", tax " + Rupees.format(line.tdsPaise())
                                    + " (section " + line.tdsSection() + "), net " + Rupees.format(line.netPaise()));
                }
                y -= 14;
                text(
                        stream,
                        regular,
                        8,
                        y,
                        "Tax is deducted under the section shown and paid to the government in your PAN's name.");
            } finally {
                stream.close();
            }
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("a payout statement couldn't be drawn", e);
        }
    }

    private static float text(PDPageContentStream stream, PDType0Font font, float size, float y, String text)
            throws IOException {
        float next = y - size * 1.45f;
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(MARGIN, next);
        stream.showText(text);
        stream.endText();
        return next;
    }

    private static float amount(PDPageContentStream stream, PDType0Font font, float y, String label, long paise)
            throws IOException {
        float next = y - 15;
        var money = Rupees.format(paise);
        stream.beginText();
        stream.setFont(font, 10);
        stream.newLineAtOffset(MARGIN, next);
        stream.showText(label);
        stream.endText();
        float width = font.getStringWidth(money) / 1000 * 10;
        stream.beginText();
        stream.setFont(font, 10);
        stream.newLineAtOffset(PDRectangle.A4.getWidth() - MARGIN - width, next);
        stream.showText(money);
        stream.endText();
        return next;
    }

    private static PDType0Font font(PDDocument pdf, String resource) throws IOException {
        try (InputStream in = Statements.class.getResourceAsStream(resource)) {
            return PDType0Font.load(pdf, Objects.requireNonNull(in, resource));
        }
    }

    /** Every financial year the person was paid in, newest first, and the current one. */
    List<String> years(UUID userId, Instant now) {
        var years = new ArrayList<String>(
                jdbc.sql("""
                        select distinct financial_year from payouts.payouts where user_id = :user
                        order by financial_year desc
                        """).param("user", userId).query(String.class).list());
        var current = FinancialYear.of(now).name();
        if (!years.contains(current)) {
            years.addFirst(current);
        }
        return years;
    }
}
