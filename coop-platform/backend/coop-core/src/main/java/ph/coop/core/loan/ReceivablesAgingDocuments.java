package ph.coop.core.loan;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ph.coop.core.loan.ReceivablesAging.Line;
import ph.coop.core.loan.ReceivablesAgingController.Response;

/**
 * The aging of receivables as files, from exactly the lines the screen shows: an Excel workbook (numbers stay numbers; the groups fold with Excel's outline buttons; the
 * header row stays in view and can filter; a second sheet lists the choices) and a PDF on legal paper, landscape (the header repeats on every page, the type shrinks to fit
 * the columns, page x of y and the run at the foot). No database here.
 */
final class ReceivablesAgingDocuments {

    private ReceivablesAgingDocuments() {}

    /** What is printed above the title. */
    record Header(String coopName, String officeName, String address) {}

    /** One column: its heading, whether it is an amount (right aligned, two decimals) and how to read it from a line. */
    private record Col(String head, Kind kind, Function<Line, Object> value) {}

    private enum Kind { TEXT, COUNT, MONEY, PERCENT, DATE }

    /** The columns for this run, as on the screen: the amounts, then the accrued interest columns when they were worked out, then the account's days and dates. */
    private static List<Col> columns(Response r, boolean withDates) {
        List<Col> c = new ArrayList<>();
        c.add(new Col(r.groupBy().isEmpty() ? "Account" : "Group / account", Kind.TEXT, ReceivablesAgingDocuments::label));
        c.add(new Col("Accounts", Kind.COUNT, (l) -> l.kind().equals("account") ? null : l.accounts()));
        c.add(new Col("Outstanding", Kind.MONEY, Line::outstanding));
        for (int i = 0; i < r.buckets().size(); i++) {
            int b = i;
            c.add(new Col(r.buckets().get(i), Kind.MONEY, (l) -> l.buckets().get(b)));
        }
        boolean par = r.basis().equals("PAR");
        c.add(new Col(par ? "Past due" : "Matured", Kind.MONEY, (l) -> par ? l.pastDue() : l.outstanding().subtract(l.buckets().get(0))));
        c.add(new Col("At risk", Kind.MONEY, Line::atRisk));
        c.add(new Col("At risk %", Kind.PERCENT, (l) -> l.kind().equals("account") ? null : l.riskPct()));
        if (r.accrual()) {
            c.add(new Col("Interest due", Kind.MONEY, Line::interestDue));
            c.add(new Col("Paid ahead", Kind.MONEY, Line::interestAhead));
            if (r.penalty()) c.add(new Col("Penalty (est.)", Kind.MONEY, Line::penaltyDue));
            c.add(new Col("Total owed", Kind.MONEY, Line::totalDue));
        }
        if (withDates) {
            c.add(new Col("Days", Kind.COUNT, Line::days));
            c.add(new Col("Released", Kind.DATE, Line::released));
            c.add(new Col("Maturity", Kind.DATE, Line::maturity));
            c.add(new Col("Last trans.", Kind.DATE, Line::lastTrans));
        }
        return c;
    }

    private static String label(Line l) {
        return switch (l.kind()) {
            case "account" -> l.label() + "  ·  No. " + l.client() + " · " + l.detail();
            case "total" -> l.label();
            default -> l.label();
        };
    }

    private static boolean hasAccounts(Response r) {
        return r.lines().stream().anyMatch((l) -> l.kind().equals("account"));
    }

    private static String agedBy(Response r) {
        return r.basis().equals("PAR") ? "PAR @ " + r.parDays() + " days" : "Maturity @ " + r.parDays() + " days";
    }

    // ---- Excel ------------------------------------------------------------------------------------------------------------------

    static byte[] xlsx(Response r, List<String[]> choices, Header h) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var df = book.createDataFormat();
            XSSFFont bold = book.createFont();
            bold.setBold(true);
            XSSFFont titleFont = book.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            XSSFFont grey = book.createFont();
            grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            grey.setFontHeightInPoints((short) 9);

            CellStyle title = book.createCellStyle();
            title.setFont(titleFont);
            CellStyle note = book.createCellStyle();
            note.setFont(grey);
            CellStyle head = book.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            head.setWrapText(true);
            CellStyle headRight = book.createCellStyle();
            headRight.cloneStyleFrom(head);
            headRight.setAlignment(HorizontalAlignment.RIGHT);

            // per kind of line (account, group at level 0, deeper group, total) and kind of cell
            String[] lineKinds = {"account", "group0", "group", "total"};
            CellStyle[][] styles = new CellStyle[lineKinds.length][Kind.values().length];
            for (int k = 0; k < lineKinds.length; k++) {
                for (Kind kind : Kind.values()) {
                    CellStyle s = book.createCellStyle();
                    if (!lineKinds[k].equals("account")) s.setFont(bold);
                    if (lineKinds[k].equals("group0")) {
                        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
                        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                    }
                    if (lineKinds[k].equals("total")) {
                        s.setBorderTop(BorderStyle.DOUBLE);
                        s.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
                        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                    }
                    switch (kind) {
                        case MONEY -> s.setDataFormat(df.getFormat("#,##0.00;(#,##0.00);\"-\""));
                        case PERCENT -> s.setDataFormat(df.getFormat("0.00\"%\""));
                        case COUNT -> s.setDataFormat(df.getFormat("#,##0"));
                        case DATE -> s.setDataFormat(df.getFormat("yyyy-mm-dd"));
                        default -> { }
                    }
                    styles[k][kind.ordinal()] = s;
                }
            }

            XSSFSheet sheet = book.createSheet("Aging");
            List<Col> cols = columns(r, hasAccounts(r));
            int at = 0;
            cell(sheet.createRow(at++), 0, h.coopName(), title);
            if (!h.officeName().isBlank() || !h.address().isBlank()) cell(sheet.createRow(at++), 0, String.join(" · ", nonBlank(h.officeName(), h.address())), note);
            cell(sheet.createRow(at++), 0, "Aging of receivables as of " + r.cutOff() + " · " + agedBy(r), title);
            cell(sheet.createRow(at++), 0, "Run by " + r.ranBy() + " on " + r.ranAt().toString().replace('T', ' ') + ". The choices are on the sheet Choices.", note);
            at++;
            int headRow = at;
            Row hr = sheet.createRow(at++);
            hr.setHeightInPoints(30);
            for (int c = 0; c < cols.size(); c++) cell(hr, c, cols.get(c).head(), c == 0 || cols.get(c).kind() == Kind.DATE ? head : headRight);

            int first = at;
            List<int[]> outline = new ArrayList<>(); // [first child row, level] of each open group, closed when a line at or above its level comes
            for (Line l : r.lines()) {
                while (!outline.isEmpty() && outline.get(outline.size() - 1)[1] >= l.level() || !outline.isEmpty() && l.kind().equals("total")) {
                    int[] g = outline.remove(outline.size() - 1);
                    if (at - 1 >= g[0]) sheet.groupRow(g[0], at - 1);
                }
                Row row = sheet.createRow(at);
                int k = switch (l.kind()) { case "account" -> 0; case "total" -> 3; default -> l.level() == 0 ? 1 : 2; };
                for (int c = 0; c < cols.size(); c++) {
                    Col col = cols.get(c);
                    Cell cell = row.createCell(c);
                    cell.setCellStyle(styles[k][col.kind().ordinal()]);
                    Object v = col.value().apply(l);
                    if (c == 0) cell.setCellValue("  ".repeat(l.level()) + v);
                    else if (v instanceof BigDecimal b) cell.setCellValue(b.doubleValue());
                    else if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                    else if (v instanceof java.time.LocalDate d) cell.setCellValue(d);
                    else if (v != null) cell.setCellValue(v.toString());
                }
                if (l.kind().equals("group")) outline.add(new int[] {at + 1, l.level()});
                at++;
            }
            int last = at - 1;
            at++;
            for (String part : r.note().split("(?<=\\.) ")) cell(sheet.createRow(at++), 0, part, note);

            sheet.setRowSumsBelow(false); // a group's subtotal is on the row above what it holds
            sheet.createFreezePane(1, headRow + 1);
            if (last >= first) sheet.setAutoFilter(new CellRangeAddress(headRow, last, 0, cols.size() - 1));
            sheet.setColumnWidth(0, 60 * 256);
            for (int c = 1; c < cols.size(); c++) sheet.setColumnWidth(c, (cols.get(c).kind() == Kind.MONEY ? 17 : cols.get(c).kind() == Kind.DATE ? 12 : 10) * 256);
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.LEGAL_PAPERSIZE);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setRepeatingRows(new CellRangeAddress(headRow, headRow, -1, -1));

            XSSFSheet ch = book.createSheet("Choices");
            int c = 0;
            cell(ch.createRow(c++), 0, "Aging of receivables: what was asked", title);
            for (String[] kv : choices) {
                Row row = ch.createRow(c++);
                cell(row, 0, kv[0], styles[1][Kind.TEXT.ordinal()]);
                cell(row, 1, kv[1], null);
            }
            c++;
            cell(ch.createRow(c++), 0, "Run by " + r.ranBy() + " on " + r.ranAt().toString().replace('T', ' '), note);
            ch.setColumnWidth(0, 36 * 256);
            ch.setColumnWidth(1, 90 * 256);

            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cell(Row row, int c, String text, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(text);
        if (style != null) cell.setCellStyle(style);
    }

    private static List<String> nonBlank(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p);
        return out;
    }

    // ---- PDF --------------------------------------------------------------------------------------------------------------------

    private static final float MARGIN = 28f, PAD = 1.8f, MAX_SIZE = 7.5f, MIN_SIZE = 5f, READABLE = 6.5f;
    private static final Color GROUP_FILL = new Color(0xEE, 0xEF, 0xF2), TOTAL_FILL = new Color(0xE3, 0xEC, 0xF8), HEAD_FILL = new Color(0xDD, 0xDF, 0xE4),
        MUTED = new Color(0x63, 0x63, 0x66), RISK = new Color(0xB4, 0x23, 0x18);

    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00;(#,##0.00)", DecimalFormatSymbols.getInstance(Locale.US)));
    private static final ThreadLocal<DecimalFormat> WHOLE = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)));

    private static String text(Col c, Object v) {
        if (v == null) return "";
        return switch (c.kind()) {
            case MONEY -> ((BigDecimal) v).signum() == 0 ? "-" : MONEY.get().format(v);
            case PERCENT -> ((BigDecimal) v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
            case COUNT -> WHOLE.get().format(((Number) v).longValue());
            default -> v.toString();
        };
    }

    static byte[] pdf(Response r, List<String[]> choices, Header h) {
        try {
            BaseFont regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
            BaseFont boldFace = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, false);
            Rectangle page = PageSize.LEGAL.rotate();
            float width = page.getWidth() - 2 * MARGIN;

            // the widest text of each column at one point, then the size that makes the whole row fit (the dates go first when even the smallest size is too wide)
            boolean withDates = hasAccounts(r);
            List<Col> cols = columns(r, withDates);
            float size = fit(r, cols, boldFace, width);
            if (size < READABLE && withDates) {
                cols = columns(r, false);
                size = fit(r, cols, boldFace, width);
            }
            size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
            float[] widths = widths(r, cols, boldFace, size);

            Font f = new Font(regular, size), fb = new Font(boldFace, size), fMuted = new Font(regular, size - 0.5f, Font.NORMAL, MUTED), fRisk = new Font(regular, size, Font.NORMAL, RISK);
            Font fRiskBold = new Font(boldFace, size, Font.NORMAL, RISK);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(page, MARGIN, MARGIN, MARGIN, MARGIN + 14);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            String foot = "Aging of receivables as of " + r.cutOff() + " · run by " + r.ranBy() + " on " + r.ranAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            writer.setPageEvent(new Footer(regular, foot));
            doc.addTitle("Aging of receivables as of " + r.cutOff());
            doc.addCreator("Coop Platform");
            doc.open();

            doc.add(new Paragraph(h.coopName(), new Font(boldFace, 13)));
            if (!h.officeName().isBlank() || !h.address().isBlank()) doc.add(new Paragraph(String.join(" · ", nonBlank(h.officeName(), h.address())), new Font(regular, 8, Font.NORMAL, MUTED)));
            Paragraph t = new Paragraph("AGING OF RECEIVABLES", new Font(boldFace, 12));
            t.setSpacingBefore(6);
            doc.add(t);
            StringBuilder asked = new StringBuilder();
            for (String[] kv : choices) {
                if (kv[0].equals("Show totals only") && kv[1].equals("No")) continue;
                if (asked.length() > 0) asked.append("   ·   ");
                asked.append(kv[0]).append(": ").append(kv[1]);
            }
            Paragraph a = new Paragraph(asked.toString(), new Font(regular, 7.5f, Font.NORMAL, MUTED));
            a.setSpacingAfter(6);
            doc.add(a);

            Line total = r.lines().get(r.lines().size() - 1);
            doc.add(summary(r, total, regular, boldFace));

            PdfPTable table = new PdfPTable(widths);
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            table.setComplete(false);
            for (int c = 0; c < cols.size(); c++) {
                PdfPCell hc = new PdfPCell(new Phrase(cols.get(c).head(), fb));
                hc.setBackgroundColor(HEAD_FILL);
                hc.setHorizontalAlignment(c == 0 || cols.get(c).kind() == Kind.DATE ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                hc.setVerticalAlignment(Element.ALIGN_BOTTOM);
                hc.setPadding(PAD);
                hc.setBorderWidth(0);
                hc.setBorderWidthBottom(0.75f);
                table.addCell(hc);
            }
            int riskCol = -1;
            for (int c = 0; c < cols.size(); c++) if (cols.get(c).head().equals("At risk")) riskCol = c;
            int count = 0;
            for (Line l : r.lines()) {
                boolean strong = !l.kind().equals("account");
                Color fill = l.kind().equals("total") ? TOTAL_FILL : l.kind().equals("group") && l.level() == 0 ? GROUP_FILL : null;
                for (int c = 0; c < cols.size(); c++) {
                    Col col = cols.get(c);
                    Object v = col.value().apply(l);
                    Phrase ph;
                    if (c == 0 && l.kind().equals("account")) {
                        ph = new Phrase();
                        ph.add(new Chunk(l.label(), f));
                        ph.add(new Chunk("   No. " + l.client() + " · " + l.detail(), fMuted));
                    } else {
                        boolean risky = c == riskCol && v instanceof BigDecimal b && b.signum() > 0;
                        ph = new Phrase(text(col, v), risky ? (strong ? fRiskBold : fRisk) : strong ? fb : f);
                    }
                    PdfPCell cell = new PdfPCell(ph);
                    cell.setHorizontalAlignment(c == 0 || col.kind() == Kind.DATE ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                    cell.setPadding(PAD);
                    if (c == 0) cell.setPaddingLeft(PAD + l.level() * size * 1.4f);
                    cell.setBorderWidth(0);
                    cell.setBorderWidthBottom(0.25f);
                    cell.setBorderColorBottom(new Color(0xD8, 0xD8, 0xDC));
                    if (l.kind().equals("total")) {
                        cell.setBorderWidthTop(1f);
                        cell.setBorderColorTop(Color.DARK_GRAY);
                    }
                    if (fill != null) cell.setBackgroundColor(fill);
                    cell.setNoWrap(c != 0);
                    table.addCell(cell);
                }
                if (++count % 400 == 0) doc.add(table); // hand finished rows to the page so a long run does not stay in memory
            }
            table.setComplete(true);
            doc.add(table);

            Paragraph n = new Paragraph(r.note(), new Font(regular, 7, Font.NORMAL, MUTED));
            n.setSpacingBefore(8);
            doc.add(n);
            if (withDates && cols.size() < columns(r, true).size()) {
                doc.add(new Paragraph("The days and dates of each account are left out of the PDF to fit the page; the Excel file has them.", new Font(regular, 7, Font.NORMAL, MUTED)));
            }
            doc.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (com.lowagie.text.DocumentException e) {
            throw new IllegalStateException("The PDF could not be made: " + e.getMessage(), e);
        }
    }

    /** The headline figures above the table, as boxes. */
    private static PdfPTable summary(Response r, Line total, BaseFont regular, BaseFont bold) {
        List<String[]> boxes = new ArrayList<>();
        boxes.add(new String[] {"Outstanding principal", MONEY.get().format(total.outstanding()), WHOLE.get().format(total.accounts()) + " accounts"});
        boolean par = r.basis().equals("PAR");
        boxes.add(new String[] {par ? "Past-due principal" : "Past maturity", MONEY.get().format(par ? total.pastDue() : total.outstanding().subtract(total.buckets().get(0))), ""});
        String risk = par ? "PAR " + r.parDays() : "Past maturity " + r.parDays() + "+ days";
        boxes.add(new String[] {"At risk (" + risk + ")", MONEY.get().format(total.atRisk()), total.riskPct().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "% of outstanding"});
        if (r.accrual()) {
            boxes.add(new String[] {"Accrued interest due", MONEY.get().format(total.interestDue()), "Paid ahead " + MONEY.get().format(total.interestAhead())});
            if (r.penalty()) boxes.add(new String[] {"Penalty due (estimate)", MONEY.get().format(total.penaltyDue()), "Not yet confirmed"});
            boxes.add(new String[] {"Total owed", MONEY.get().format(total.totalDue()), r.penalty() ? "Principal, interest, penalty" : "Principal and interest"});
        }
        for (int i = 0; i < r.buckets().size(); i++) {
            BigDecimal b = total.buckets().get(i);
            boxes.add(new String[] {r.buckets().get(i), MONEY.get().format(b), ReceivablesAging.pct(b, total.outstanding()).toPlainString() + "%"});
        }
        int per = Math.min(boxes.size(), 7);
        PdfPTable t = new PdfPTable(per);
        t.setWidthPercentage(100);
        t.setSpacingAfter(8);
        for (String[] b : boxes) {
            Phrase p = new Phrase();
            p.add(new Chunk(b[0] + "\n", new Font(regular, 6.5f, Font.NORMAL, MUTED)));
            p.add(new Chunk(b[1] + "\n", new Font(bold, 9)));
            p.add(new Chunk(b[2], new Font(regular, 6.5f, Font.NORMAL, MUTED)));
            PdfPCell c = new PdfPCell(p);
            c.setPadding(4);
            c.setBorderColor(new Color(0xD8, 0xD8, 0xDC));
            c.setBorderWidth(0.5f);
            t.addCell(c);
        }
        for (int i = boxes.size() % per; i > 0 && i < per; i++) {
            PdfPCell empty = new PdfPCell(new Phrase(""));
            empty.setBorder(Rectangle.NO_BORDER);
            t.addCell(empty);
        }
        return t;
    }

    /** Text widths of each column at one point (bold, the widest case), plus the padding. */
    private static float[] raw(Response r, List<Col> cols, BaseFont font) {
        float[] w = new float[cols.size()];
        for (int c = 0; c < cols.size(); c++) {
            Col col = cols.get(c);
            float m = c == 0 ? 0 : font.getWidthPoint(col.head(), 1);
            for (Line l : r.lines()) {
                String s = c == 0 ? label(l) : text(col, col.value().apply(l));
                float x = font.getWidthPoint(s, 1) + (c == 0 ? l.level() * 1.4f : 0);
                if (x > m) m = x;
            }
            // the first column wraps, so it needs no more than a fair share; the others never wrap
            w[c] = c == 0 ? Math.min(m, 46f) : m;
        }
        return w;
    }

    private static float fit(Response r, List<Col> cols, BaseFont font, float width) {
        float sum = 0;
        for (float x : raw(r, cols, font)) sum += x;
        return (width - cols.size() * 2 * PAD - 2) / sum;
    }

    private static float[] widths(Response r, List<Col> cols, BaseFont font, float size) {
        float[] w = raw(r, cols, font);
        for (int i = 0; i < w.length; i++) w[i] = w[i] * size + 2 * PAD + 1;
        return w;
    }

    /** Page x of y and the run at the foot of every page. */
    private static final class Footer extends PdfPageEventHelper {
        private final BaseFont font;
        private final String text;
        private PdfTemplate totalPages;

        Footer(BaseFont font, String text) {
            this.font = font;
            this.text = text;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalPages = writer.getDirectContent().createTemplate(40, 10);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float y = MARGIN - 6;
            cb.beginText();
            cb.setFontAndSize(font, 7);
            cb.setColorFill(MUTED);
            cb.showTextAligned(Element.ALIGN_LEFT, text, MARGIN, y, 0);
            String page = "Page " + writer.getPageNumber() + " of ";
            float right = document.getPageSize().getWidth() - MARGIN - 20;
            cb.showTextAligned(Element.ALIGN_RIGHT, page, right, y, 0);
            cb.endText();
            cb.addTemplate(totalPages, right, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            totalPages.beginText();
            totalPages.setFontAndSize(font, 7);
            totalPages.setColorFill(MUTED);
            totalPages.showText(String.valueOf(writer.getPageNumber() - 1));
            totalPages.endText();
        }
    }
}
