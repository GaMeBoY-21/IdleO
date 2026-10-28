package com.idleo.controlplane;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * How IdleO's printed documents look (ADR 0039, ADR 0043): a white page with dark text and colour only for accents,
 * set in the web app's own faces, embedded from the jar so nothing is fetched while one renders: Inter for text
 * (Regular and Bold) and Archivo Display for the wordmark and the title. The accent is the brand's ion-deep, which
 * prints as a dark colour; volt, which is invisible on white, is never used.
 */
public final class PdfStyle {

    /** Dark text: the page's ink, #0C0A1F. */
    public static final Color INK = new Color(0x0C, 0x0A, 0x1F);
    /** The accent: ion-deep, #5B4BE8, for the rule under the letterhead and the small labels. */
    public static final Color ACCENT = new Color(0x5B, 0x4B, 0xE8);

    private static final float WORDMARK_SIZE = 22;

    private PdfStyle() {}

    /** One of the embedded faces: {@code Inter-Regular.ttf}, {@code Inter-Bold.ttf} or {@code Archivo-Display.ttf}. */
    public static PDType0Font font(PDDocument pdf, String file) throws IOException {
        var resource = "/fonts/" + file;
        try (InputStream in = PdfStyle.class.getResourceAsStream(resource)) {
            return PDType0Font.load(pdf, Objects.requireNonNull(in, resource), true);
        }
    }

    /**
     * The letterhead at the top of a page: the wordmark in the brand's face and an accent rule across the page under it.
     * Leaves the text colour at the ink, and returns the height to carry on writing from.
     */
    public static float letterhead(PDPageContentStream stream, PDType0Font display, float top, float margin)
            throws IOException {
        var y = top - WORDMARK_SIZE;
        stream.beginText();
        stream.setNonStrokingColor(INK);
        stream.setFont(display, WORDMARK_SIZE);
        stream.newLineAtOffset(margin, y);
        stream.showText("idleo");
        stream.endText();
        y -= 10;
        stream.setStrokingColor(ACCENT);
        stream.setLineWidth(1.5f);
        stream.moveTo(margin, y);
        stream.lineTo(PDRectangle.A4.getWidth() - margin, y);
        stream.stroke();
        return y - 10;
    }
}
