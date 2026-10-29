package ph.coop.core.loan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import ph.coop.core.loan.LoanWorksheetService.Header;
import ph.coop.core.loan.LoanWorksheetService.Worksheet;

/**
 * Writes the worksheet as a fixed-layout PDF (JasperReports, template {@code reports/loan-worksheet.jrxml}, editable in Jaspersoft Studio) and as an Excel file (Apache POI:
 * real numbers and dates in separate columns, so it can be sorted, filtered and summed). Both come from the same {@link Worksheet}, so they always agree.
 */
@Component
public class LoanWorksheetDocuments {

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ENGLISH);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter WORDS = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMMM d, yyyy    hh:mm a", Locale.ENGLISH);

    private volatile JasperReport compiled;

    private JasperReport template() throws JRException, IOException {
        JasperReport r = compiled;
        if (r == null) {
            try (InputStream in = LoanWorksheetDocuments.class.getResourceAsStream("/reports/loan-worksheet.jrxml")) {
                r = JasperCompileManager.compileReport(in);
            }
            compiled = r;
        }
        return r;
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(v);
    }

    private static String percent(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString() + " % p. a.";
    }

    private static String date(LocalDate d, DateTimeFormatter f) {
        return d == null ? "" : d.format(f);
    }

    private static String line(String s) {
        return s == null ? "" : s;
    }

    /** The rows as the template's fields. */
    private static List<Map<String, ?>> rowMaps(List<LoanWorksheet.Row> rows) {
        List<Map<String, ?>> out = new ArrayList<>();
        for (LoanWorksheet.Row r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("date", date(r.date(), SHORT));
            m.put("tr", r.kind());
            m.put("ctl", r.controlNo());
            m.put("doc", r.docNo());
            m.put("pD", r.principalDebit());
            m.put("pC", r.principalCredit());
            m.put("pB", r.principalBalance());
            m.put("days", r.days());
            m.put("iD", r.interestDebit());
            m.put("iC", r.interestCredit());
            m.put("iB", r.interestBalance());
            m.put("sb", r.shouldBeBalance());
            m.put("dl", r.delayedPrincipal());
            m.put("pdays", r.penaltyDays());
            m.put("penD", r.penaltyDebit());
            m.put("penC", r.penaltyCredit());
            m.put("penB", r.penaltyBalance());
            m.put("cutoff", r.cutoff());
            out.add(m);
        }
        return out;
    }

    public byte[] pdf(Worksheet w, String generatedBy, LocalDateTime printedAt) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JasperExportManager.exportReportToPdfStream(filled(w, generatedBy, printedAt), out);
            return out.toByteArray();
        } catch (JRException | IOException e) {
            throw new IllegalStateException("The worksheet PDF could not be made: " + e.getMessage(), e);
        }
    }

    /** The laid-out pages, before they are written as a PDF. */
    JasperPrint filled(Worksheet w, String generatedBy, LocalDateTime printedAt) throws JRException, IOException {
        {
            Header h = w.header();
            LoanWorksheet.Totals t = w.result().totals();
            LoanWorksheet.Summary s = w.result().summary();
            Map<String, Object> p = new HashMap<>();
            p.put("coopName", h.coopName());
            p.put("officeName", h.officeName());
            p.put("address", h.address());
            p.put("tin", h.tin());
            p.put("asOfText", date(w.asOf(), WORDS));
            p.put("createdText", printedAt.format(STAMP).replace("AM", "am").replace("PM", "pm"));
            p.put("accountNo", h.accountNo());
            p.put("borrower", h.borrower());
            p.put("refNo", h.refNo());
            p.put("loanType", h.loanType());
            p.put("collector", h.collector());
            p.put("officer", h.accountOfficer());
            p.put("status", h.status());
            p.put("principalText", money(h.principal()));
            p.put("principalMode", h.principalMode());
            p.put("interestText", percent(h.interestRate()));
            p.put("penaltyText", percent(h.penaltyRate()));
            p.put("releasedText", date(h.released(), LONG));
            p.put("term", h.term());
            p.put("amortization", h.amortization());
            p.put("paymentMode", h.paymentMode());
            p.put("maturityText", date(h.maturity(), LONG));
            p.put("interestMode", h.interestMode());
            p.put("generatedBy", "Generated by: " + generatedBy + " on " + printedAt.format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a", Locale.ENGLISH)));
            p.put("sStatus", s.status());
            p.put("tPD", t.principalDebit());
            p.put("tPC", t.principalCredit());
            p.put("tID", t.interestDebit());
            p.put("tIC", t.interestCredit());
            p.put("tPenD", t.penaltyDebit());
            p.put("tPenC", t.penaltyCredit());
            p.put("sPenDue", s.penaltyDue());
            p.put("sIntDue", s.interestDue());
            p.put("sPrinDue", s.principalDue());
            p.put("sTotDue", s.totalDue());
            p.put("sPenOver", s.penaltyOver());
            p.put("sIntOver", s.interestOver());
            p.put("sPrinOver", s.principalOver());
            p.put("sTotOver", s.totalOver());
            return JasperFillManager.fillReport(template(), p, new JRMapCollectionDataSource(rowMaps(w.result().rows())));
        }
    }

    public byte[] xlsx(Worksheet w, String generatedBy, LocalDateTime printedAt) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Worksheet");
            DataFormat df = book.createDataFormat();
            Font bold = book.createFont();
            bold.setBold(true);
            CellStyle title = book.createCellStyle();
            Font big = book.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 13);
            title.setFont(big);
            CellStyle label = book.createCellStyle();
            label.setFont(bold);
            CellStyle head = book.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setWrapText(true);
            head.setBorderBottom(BorderStyle.THIN);
            CellStyle num = book.createCellStyle();
            num.setDataFormat(df.getFormat("#,##0.00;(#,##0.00)"));
            CellStyle numBold = book.createCellStyle();
            numBold.cloneStyleFrom(num);
            numBold.setFont(bold);
            numBold.setBorderTop(BorderStyle.THIN);
            CellStyle dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(df.getFormat("mm/dd/yy"));
            dateStyle.setAlignment(HorizontalAlignment.LEFT);
            CellStyle whole = book.createCellStyle();
            whole.setDataFormat(df.getFormat("#,##0"));

            Header h = w.header();
            int r = 0;
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(h.coopName());
            row.getCell(0).setCellStyle(title);
            for (String s : new String[] {h.officeName(), h.address(), "TIN: " + h.tin()}) sheet.createRow(r++).createCell(0).setCellValue(s);
            row = sheet.createRow(r++);
            row.createCell(0).setCellValue("LOAN INTEREST & PENALTY COMPUTATION WORKSHEET");
            row.getCell(0).setCellStyle(title);
            sheet.createRow(r++).createCell(0).setCellValue("As of " + date(w.asOf(), WORDS));
            r++;
            String[][] info = {
                {"Account", h.accountNo(), "Borrower", h.borrower()}, {"Ref. No.", h.refNo(), "Loan", h.loanType()}, {"Principal", money(h.principal()), "Principal mode", h.principalMode()},
                {"Interest", percent(h.interestRate()), "Penalty", percent(h.penaltyRate())}, {"Released", date(h.released(), LONG), "Term", h.term()},
                {"Based on", h.amortization(), "Payment mode", h.paymentMode()}, {"Maturity", date(h.maturity(), LONG), "Interest mode", h.interestMode()},
                {"SL Status", h.status(), "Collector", line(h.collector())}};
            for (String[] pair : info) {
                row = sheet.createRow(r++);
                row.createCell(0).setCellValue(pair[0]);
                row.getCell(0).setCellStyle(label);
                row.createCell(1).setCellValue(pair[1]);
                row.createCell(3).setCellValue(pair[2]);
                row.getCell(3).setCellStyle(label);
                row.createCell(4).setCellValue(pair[3]);
            }
            r++;
            String[] groups = {"", "", "", "", "PRINCIPAL", "", "", "ACCRUED INTEREST", "", "", "", "LOAN STATUS", "", "PENALTY", "", "", ""};
            String[] heads = {"Date", "Tr.", "Ctl. No.", "Doc. No.", "Debit", "Credit", "Balance", "Days", "Debit", "Credit", "Balance", "Should be balance", "Delayed principal",
                "Days", "Debit", "Credit", "Balance"};
            row = sheet.createRow(r++);
            for (int c = 0; c < groups.length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(groups[c]);
                cell.setCellStyle(head);
            }
            sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 4, 6));
            sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 7, 10));
            sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 11, 12));
            sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 13, 16));
            row = sheet.createRow(r++);
            for (int c = 0; c < heads.length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(heads[c]);
                cell.setCellStyle(head);
            }
            sheet.createFreezePane(0, r);
            for (LoanWorksheet.Row x : w.result().rows()) {
                row = sheet.createRow(r++);
                Cell d = row.createCell(0);
                d.setCellValue(java.sql.Date.valueOf(x.date()));
                d.setCellStyle(dateStyle);
                if (x.kind() != null) row.createCell(1).setCellValue(x.kind());
                if (x.controlNo() != null) row.createCell(2).setCellValue(x.controlNo());
                if (x.docNo() != null) row.createCell(3).setCellValue(x.docNo());
                BigDecimal[] values = {x.principalDebit(), x.principalCredit(), x.principalBalance(), null, x.interestDebit(), x.interestCredit(), x.interestBalance(), x.shouldBeBalance(),
                    x.delayedPrincipal(), null, x.penaltyDebit(), x.penaltyCredit(), x.penaltyBalance()};
                int[] cols = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
                for (int i = 0; i < cols.length; i++) {
                    Cell cell = row.createCell(cols[i]);
                    if (cols[i] == 7) { if (x.days() != null) { cell.setCellValue(x.days()); cell.setCellStyle(whole); } }
                    else if (cols[i] == 13) { if (x.penaltyDays() != null) { cell.setCellValue(x.penaltyDays()); cell.setCellStyle(whole); } }
                    else if (values[i] != null) { cell.setCellValue(values[i].doubleValue()); cell.setCellStyle(num); }
                }
            }
            LoanWorksheet.Totals t = w.result().totals();
            row = sheet.createRow(r++);
            row.createCell(0).setCellValue("Totals");
            row.getCell(0).setCellStyle(label);
            double[] tv = {t.principalDebit().doubleValue(), t.principalCredit().doubleValue(), 0, 0, t.interestDebit().doubleValue(), t.interestCredit().doubleValue(), 0, 0, 0, 0,
                t.penaltyDebit().doubleValue(), t.penaltyCredit().doubleValue()};
            int[] tc = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
            for (int i = 0; i < tc.length; i++) {
                if (i == 2 || i == 3 || i == 6 || i == 7 || i == 8 || i == 9) continue;
                Cell cell = row.createCell(tc[i]);
                cell.setCellValue(tv[i]);
                cell.setCellStyle(numBold);
            }
            r++;
            LoanWorksheet.Summary s = w.result().summary();
            row = sheet.createRow(r++);
            row.createCell(0).setCellValue("Amount collectible");
            row.getCell(0).setCellStyle(label);
            row.createCell(3).setCellValue("Over / advance payments");
            row.getCell(3).setCellStyle(label);
            Object[][] box = {{"Penalty", s.penaltyDue(), s.penaltyOver()}, {"Interest", s.interestDue(), s.interestOver()}, {"Principal", s.principalDue(), s.principalOver()},
                {"Total", s.totalDue(), s.totalOver()}};
            for (Object[] b : box) {
                row = sheet.createRow(r++);
                row.createCell(0).setCellValue((String) b[0]);
                Cell a = row.createCell(1);
                a.setCellValue(((BigDecimal) b[1]).doubleValue());
                a.setCellStyle(num);
                row.createCell(3).setCellValue((String) b[0]);
                Cell o = row.createCell(4);
                o.setCellValue(((BigDecimal) b[2]).doubleValue());
                o.setCellStyle(num);
            }
            row = sheet.createRow(r++);
            row.createCell(0).setCellValue("Account status");
            row.getCell(0).setCellStyle(label);
            row.createCell(1).setCellValue(s.status());
            r++;
            sheet.createRow(r++).createCell(0).setCellValue("Generated by " + generatedBy + " on " + printedAt.format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a", Locale.ENGLISH))
                + ". This is a system generated document.");
            int[] widths = {11, 6, 11, 12, 14, 14, 15, 7, 13, 13, 14, 15, 15, 7, 11, 11, 12};
            for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, widths[c] * 256);
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setFitToPage(true);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("The worksheet Excel file could not be made: " + e.getMessage(), e);
        }
    }
}
