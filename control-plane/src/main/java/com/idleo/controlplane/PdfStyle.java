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
 * How IdleO's printed documents look (ADR 0039, ADR 0060): a white page with near-black text, set in the web app's own
 * face, embedded from the jar so nothing is fetched while one renders: Inter, Regular and Bold. They are light only, with
 * the web app's type and spacing, a hairline under the letterhead, and volt only on the brand mark (a volt ring on the
 * app's dark tile), because volt is invisible on white as text or a rule.
 */
public final class PdfStyle {

    /** Dark text: the page's ink, #0B0C0E. */
    public static final Color INK = new Color(0x0B, 0x0C, 0x0E);
    /** Small headings: the quieter ink, #3C4149. */
    public static final Color LABEL = new Color(0x3C, 0x41, 0x49);
    /** The rule under the letterhead: a hairline, #E4E5E7. */
    public static final Color HAIRLINE = new Color(0xE4, 0xE5, 0xE7);
    /** The brand mark's tile (the canvas, #08090A) and its ring (volt, #C8FF3D). */
    private static final Color TILE = new Color(0x08, 0x09, 0x0A);

    private static final Color VOLT = new Color(0xC8, 0xFF, 0x3D);

    private static final float MARK_SIZE = 22;
    private static final float WORDMARK_SIZE = 16;
    /** The tight tracking headings have (-0.02 em), in points of the wordmark's size. */
    private static final float TIGHT = -0.02f * WORDMARK_SIZE;

    private PdfStyle() {}

    /** One of the embedded faces: {@code Inter-Regular.ttf} or {@code Inter-Bold.ttf}. */
    public static PDType0Font font(PDDocument pdf, String file) throws IOException {
        var resource = "/fonts/" + file;
        try (InputStream in = PdfStyle.class.getResourceAsStream(resource)) {
            return PDType0Font.load(pdf, Objects.requireNonNull(in, resource), true);
        }
    }

    /**
     * The letterhead at the top of a page: the brand mark, the wordmark in bold and a hairline across the page under
     * them. Leaves the text colour at the ink, and returns the height to carry on writing from.
     */
    public static float letterhead(PDPageContentStream stream, PDType0Font bold, float top, float margin)
            throws IOException {
        var y = top - MARK_SIZE;
        brandMark(stream, margin, y, MARK_SIZE);
        stream.beginText();
        stream.setNonStrokingColor(INK);
        stream.setFont(bold, WORDMARK_SIZE);
        stream.setCharacterSpacing(TIGHT);
        stream.newLineAtOffset(margin + MARK_SIZE + 8, y + (MARK_SIZE - WORDMARK_SIZE * 0.72f) / 2);
        stream.showText("idleo");
        stream.setCharacterSpacing(0);
        stream.endText();
        y -= 12;
        stream.setStrokingColor(HAIRLINE);
        stream.setLineWidth(1f);
        stream.moveTo(margin, y);
        stream.lineTo(PDRectangle.A4.getWidth() - margin, y);
        stream.stroke();
        return y - 12;
    }

    /** The app's icon: a dark rounded tile with a volt ring open at the top (public/icon.svg), {@code size} points wide. */
    private static void brandMark(PDPageContentStream stream, float x, float y, float size) throws IOException {
        var corner = size * 350f / 1570f;
        var k = 0.5523f * corner;
        stream.setNonStrokingColor(TILE);
        stream.moveTo(x + corner, y);
        stream.lineTo(x + size - corner, y);
        stream.curveTo(x + size - corner + k, y, x + size, y + corner - k, x + size, y + corner);
        stream.lineTo(x + size, y + size - corner);
        stream.curveTo(x + size, y + size - corner + k, x + size - corner + k, y + size, x + size - corner, y + size);
        stream.lineTo(x + corner, y + size);
        stream.curveTo(x + corner - k, y + size, x, y + size - corner + k, x, y + size - corner);
        stream.lineTo(x, y + corner);
        stream.curveTo(x, y + corner - k, x + corner - k, y, x + corner, y);
        stream.fill();
        stream.setStrokingColor(VOLT);
        stream.setLineWidth(size * 230f / 1570f);
        stream.setLineCapStyle(1);
        // The ring: from 64 degrees clockwise the long way round to 116 degrees, a 308 degree arc.
        clockwiseArc(stream, x + size / 2, y + size / 2, size * 470f / 1570f, 64, -244);
        stream.stroke();
        stream.setLineCapStyle(0);
    }

    /** An arc as Bezier curves of at most 90 degrees each, running clockwise from one angle to a lower one. */
    private static void clockwiseArc(
            PDPageContentStream stream, float cx, float cy, float r, double fromDegrees, double toDegrees)
            throws IOException {
        var steps = (int) Math.ceil((fromDegrees - toDegrees) / 90);
        var step = Math.toRadians((fromDegrees - toDegrees) / steps);
        var angle = Math.toRadians(fromDegrees);
        stream.moveTo(cx + (float) (r * Math.cos(angle)), cy + (float) (r * Math.sin(angle)));
        for (var i = 0; i < steps; i++) {
            var next = angle - step;
            var h = 4.0 / 3.0 * Math.tan(step / 4);
            stream.curveTo(
                    cx + (float) (r * (Math.cos(angle) + h * Math.sin(angle))),
                    cy + (float) (r * (Math.sin(angle) - h * Math.cos(angle))),
                    cx + (float) (r * (Math.cos(next) - h * Math.sin(next))),
                    cy + (float) (r * (Math.sin(next) + h * Math.cos(next))),
                    cx + (float) (r * Math.cos(next)),
                    cy + (float) (r * Math.sin(next)));
            angle = next;
        }
    }
}
