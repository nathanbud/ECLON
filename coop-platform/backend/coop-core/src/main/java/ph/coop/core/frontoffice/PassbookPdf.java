package ph.coop.core.frontoffice;

import com.lowagie.text.Document;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import ph.coop.core.frontoffice.PassbookRules.Layout;
import ph.coop.core.frontoffice.PassbookRules.Line;

/**
 * Draws passbook lines at their places on a PDF page the size of the open passbook, so the printer puts each one on its printed line. The PDF asks to
 * be printed at actual size (no fitting to the page). The test page marks every line and column edge, to line the layout up with a real passbook.
 */
final class PassbookPdf {

    private PassbookPdf() {}

    private static final float PT = 72f;

    private static BaseFont font() {
        try {
            return BaseFont.createFont(BaseFont.COURIER, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("The passbook font is missing.", e);
        }
    }

    private static Document open(Layout l, ByteArrayOutputStream out, PdfWriter[] writer) {
        Document doc = new Document(new Rectangle((float) l.pageWidth() * PT, (float) l.pageHeight() * PT), 0, 0, 0, 0);
        writer[0] = PdfWriter.getInstance(doc, out);
        writer[0].addViewerPreference(PdfName.PRINTSCALING, PdfName.NONE);
        doc.addTitle("Passbook");
        doc.open();
        return doc;
    }

    private static float y(Layout l, int line) {
        return (float) ((l.pageHeight() - PassbookRules.baseline(l, line)) * PT);
    }

    private static float x(Layout l, double inches) {
        return (float) ((inches + l.shiftX()) * PT);
    }

    private static void text(PdfContentByte cb, int align, String s, float x, float y) {
        if (s != null && !s.isEmpty()) cb.showTextAligned(align, s, x, y, 0);
    }

    /** The lines of one page, each on its own printed line; nothing else is printed (the passbook has its own headings). */
    static byte[] page(Layout l, List<Line> lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter[] w = new PdfWriter[1];
        Document doc = open(l, out, w);
        PdfContentByte cb = w[0].getDirectContent();
        cb.beginText();
        cb.setFontAndSize(font(), (float) l.fontSize());
        for (Line ln : lines) {
            float y = y(l, ln.line());
            text(cb, PdfContentByte.ALIGN_LEFT, ln.date(), x(l, l.dateX()), y);
            text(cb, PdfContentByte.ALIGN_LEFT, ln.reference(), x(l, l.referenceX()), y);
            text(cb, PdfContentByte.ALIGN_RIGHT, ln.deposit(), x(l, l.depositRight()), y);
            text(cb, PdfContentByte.ALIGN_RIGHT, ln.withdrawal(), x(l, l.withdrawalRight()), y);
            text(cb, PdfContentByte.ALIGN_RIGHT, ln.balance(), x(l, l.balanceRight()), y);
            text(cb, PdfContentByte.ALIGN_LEFT, ln.tc(), x(l, l.tcX()), y);
            text(cb, PdfContentByte.ALIGN_LEFT, ln.teller(), x(l, l.tellerX()), y);
        }
        cb.endText();
        doc.close();
        return out.toByteArray();
    }

    /** A test page: every line numbered with a sample row, the column edges, the fold, and the page edge, to compare with a real passbook. */
    static byte[] testPage(Layout l) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter[] w = new PdfWriter[1];
        Document doc = open(l, out, w);
        PdfContentByte cb = w[0].getDirectContent();
        float width = (float) l.pageWidth() * PT, height = (float) l.pageHeight() * PT;
        cb.setColorStroke(new Color(150, 150, 150));
        cb.setLineWidth(0.4f);
        cb.rectangle(1, 1, width - 2, height - 2);
        cb.stroke();
        // the fold, half way between its two lines
        if (l.foldAfter() > 0 && l.foldAfter() < l.linesPerPage()) {
            float fold = (y(l, l.foldAfter()) + y(l, l.foldAfter() + 1)) / 2 + (float) (l.fontSize() / 3);
            cb.setLineDash(3, 3, 0);
            cb.moveTo(0, fold);
            cb.lineTo(width, fold);
            cb.stroke();
            cb.setLineDash(0);
        }
        // a short tick at each column edge, just above line 1
        float top = y(l, 1) + (float) (l.lineHeight() * PT * 0.9);
        for (double edge : new double[] {l.dateX(), l.referenceX(), l.depositRight(), l.withdrawalRight(), l.balanceRight(), l.tcX(), l.tellerX()}) {
            cb.moveTo(x(l, edge), top);
            cb.lineTo(x(l, edge), top + 6);
        }
        cb.stroke();
        BaseFont f = font();
        cb.beginText();
        cb.setFontAndSize(f, (float) Math.min(l.fontSize(), 7));
        text(cb, PdfContentByte.ALIGN_LEFT, "DATE", x(l, l.dateX()), top + 8);
        text(cb, PdfContentByte.ALIGN_LEFT, "REF", x(l, l.referenceX()), top + 8);
        text(cb, PdfContentByte.ALIGN_RIGHT, "DEP", x(l, l.depositRight()), top + 8);
        text(cb, PdfContentByte.ALIGN_RIGHT, "WDL", x(l, l.withdrawalRight()), top + 8);
        text(cb, PdfContentByte.ALIGN_RIGHT, "BAL", x(l, l.balanceRight()), top + 8);
        text(cb, PdfContentByte.ALIGN_LEFT, "TC", x(l, l.tcX()), top + 8);
        text(cb, PdfContentByte.ALIGN_LEFT, "TN", x(l, l.tellerX()), top + 8);
        cb.setFontAndSize(f, (float) l.fontSize());
        for (int line = 1; line <= l.linesPerPage(); line++) {
            float y = y(l, line);
            text(cb, PdfContentByte.ALIGN_LEFT, PassbookRules.date(java.time.LocalDate.of(2026, 1, Math.min(line, 28))), x(l, l.dateX()), y);
            text(cb, PdfContentByte.ALIGN_LEFT, String.format("LINE %02d", line), x(l, l.referenceX()), y);
            text(cb, PdfContentByte.ALIGN_RIGHT, "1,234.56", x(l, l.depositRight()), y);
            text(cb, PdfContentByte.ALIGN_RIGHT, "999.99", x(l, l.withdrawalRight()), y);
            text(cb, PdfContentByte.ALIGN_RIGHT, "1,234,567.89", x(l, l.balanceRight()), y);
            text(cb, PdfContentByte.ALIGN_LEFT, "DEPR", x(l, l.tcX()), y);
            text(cb, PdfContentByte.ALIGN_LEFT, "12", x(l, l.tellerX()), y);
        }
        cb.endText();
        doc.close();
        return out.toByteArray();
    }
}
