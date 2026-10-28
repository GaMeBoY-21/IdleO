package com.idleo.controlplane.payouts.internal;

import static org.mockito.Mockito.mock;

import com.idleo.controlplane.FinancialYear;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes a sample payout statement as a PDF, for the product owner to look at (web/e2e/screenshots.ts lays it out in the
 * contact sheet): {@code ./mvnw test -Dtest=StatementPdfSamples -Didleo.pdfs.out=<folder>}. It does nothing without the
 * property. Every figure and name is invented.
 */
@EnabledIfSystemProperty(named = "idleo.pdfs.out", matches = ".+")
class StatementPdfSamples {

    @Test
    void writesAStatement() throws Exception {
        var folder = Path.of(System.getProperty("idleo.pdfs.out"));
        Files.createDirectories(folder);
        var statements = new Statements(mock(JdbcClient.class), mock(Pans.class));
        var lines = List.of(
                new Statements.Line(
                        UUID.randomUUID(),
                        Instant.parse("2026-04-27T04:00:00Z"),
                        Instant.parse("2026-04-27T04:20:00Z"),
                        "processed",
                        21_240,
                        212,
                        "194O",
                        0,
                        21_028),
                new Statements.Line(
                        UUID.randomUUID(),
                        Instant.parse("2026-05-04T04:00:00Z"),
                        null,
                        "on its way",
                        18_700,
                        187,
                        "194O",
                        0,
                        18_513));
        var statement =
                new Statements.Statement(new FinancialYear(2026), "123F", "Asha Rao", lines, 39_940, 399, 0, 39_541);
        picture(folder.resolve("payout-statement.pdf"), statements.pdf(statement));
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
}
