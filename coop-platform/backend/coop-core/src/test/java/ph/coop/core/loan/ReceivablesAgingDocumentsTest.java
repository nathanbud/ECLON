package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ph.coop.core.loan.ReceivablesAging.Account;
import ph.coop.core.loan.ReceivablesAging.Basis;
import ph.coop.core.loan.ReceivablesAging.Line;
import ph.coop.core.loan.ReceivablesAging.Spec;
import ph.coop.core.loan.ReceivablesAgingController.Response;

/** The Excel and PDF files of the aging, without a database: same figures as the lines, groups fold in Excel, the PDF repeats its header and counts its pages. */
class ReceivablesAgingDocumentsTest {

    static final LocalDate CUT = LocalDate.of(2026, 4, 24);
    static final ReceivablesAgingDocuments.Header HEADER = new ReceivablesAgingDocuments.Header("Sample Multi-Purpose Cooperative", "Main Office", "Somewhere Street");
    static final List<String[]> CHOICES = List.of(new String[] {"Cut-off date", "2026-04-24"}, new String[] {"Grouped by", "Member department > Aging bucket"});

    static Account acct(int client, String name, String amount, long daysLate, String dept, String interest) {
        return new Account(client, name, 12, 1, "Regular", client * 10, new BigDecimal(amount), new BigDecimal("10.00"), daysLate, LocalDate.of(2025, 1, 1),
            CUT.plusDays(100), CUT, Map.of("dept", dept), new BigDecimal(interest), BigDecimal.ZERO, new BigDecimal("1.50"));
    }

    static Response response(List<Account> accounts, List<String> groupBy, boolean accrual) {
        List<Line> lines = ReceivablesAging.build(accounts, new Spec(Basis.PAR, 30, groupBy, "name", false), CUT);
        return new Response(CUT, "PAR", 30, ReceivablesAging.BUCKETS, groupBy, lines, "Principal outstanding. A second sentence.", LocalDateTime.of(2026, 4, 24, 10, 30), "ana", accrual, accrual);
    }

    static final List<Account> BOOK = List.of(
        acct(1, "Cruz, Ana", "1000.00", 0, "Finance", "12.34"),
        acct(2, "Bautista, Ben", "2699050350.25", 45, "Finance", "0.00"),
        acct(3, "Abad, Carl", "3000.00", 400, "Admin", "99.99"));

    @Test
    void excelKeepsNumbersAndFoldsTheGroups() throws Exception {
        Response r = response(BOOK, List.of("dept", "aging"), true);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(ReceivablesAgingDocuments.xlsx(r, CHOICES, HEADER)))) {
            assertEquals(List.of("Aging", "Choices"), List.of(book.getSheetName(0), book.getSheetName(1)));
            XSSFSheet s = book.getSheetAt(0);
            assertEquals("Sample Multi-Purpose Cooperative", s.getRow(0).getCell(0).getStringCellValue());
            Row head = null;
            int headAt = -1;
            for (int i = 0; i < 10; i++) if (s.getRow(i) != null && "Group / account".equals(s.getRow(i).getCell(0).getStringCellValue())) { head = s.getRow(i); headAt = i; }
            assertTrue(head != null, "the header row is there");
            List<String> heads = new ArrayList<>();
            head.forEach((c) -> heads.add(c.getStringCellValue()));
            assertTrue(heads.containsAll(List.of("Outstanding", "Current", "Over 365 days", "At risk", "Interest due", "Penalty (est.)", "Total owed", "Days", "Last trans.")), heads.toString());

            // the lines in order: Admin, its bucket, its account, Finance, ..., the total; the groups' rows are outlined below them
            Row admin = s.getRow(headAt + 1);
            assertEquals("Admin", admin.getCell(0).getStringCellValue());
            assertEquals(0, admin.getOutlineLevel());
            assertEquals(1, s.getRow(headAt + 2).getOutlineLevel(), "the bucket inside Admin");
            assertEquals(2, s.getRow(headAt + 3).getOutlineLevel(), "the account inside the bucket");
            int out = heads.indexOf("Outstanding");
            int last = headAt + r.lines().size();
            Row total = s.getRow(last);
            assertEquals("All receivables", total.getCell(0).getStringCellValue());
            assertEquals(0, total.getOutlineLevel());
            assertEquals(2699054350.25, total.getCell(out).getNumericCellValue(), 0.001, "a number, not text");
            assertEquals(112.33, total.getCell(heads.indexOf("Interest due")).getNumericCellValue(), 0.001);
            assertEquals(new BigDecimal("2699054350.25").add(new BigDecimal("112.33")).add(new BigDecimal("4.50")).doubleValue(), total.getCell(heads.indexOf("Total owed")).getNumericCellValue(), 0.01);
            assertEquals("Grouped by", book.getSheetAt(1).getRow(2).getCell(0).getStringCellValue());
        }
    }

    @Test
    void excelWithoutTheAccruedInterestHasNoSuchColumns() throws Exception {
        Response r = response(BOOK, List.of(), false);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(ReceivablesAgingDocuments.xlsx(r, CHOICES, HEADER)))) {
            StringBuilder all = new StringBuilder();
            book.getSheetAt(0).forEach((row) -> row.forEach((c) -> all.append(c.toString()).append('|')));
            assertTrue(all.indexOf("Interest due") < 0);
            assertTrue(all.indexOf("Abad, Carl") >= 0);
        }
    }

    @Test
    void pdfHasTheTitleTheFiguresAndThePageCount() throws Exception {
        // enough accounts for several pages
        List<Account> many = new ArrayList<>();
        for (int i = 1; i <= 260; i++) many.add(acct(i, "Member " + i, "1000.00", i % 3 == 0 ? 45 : 0, i % 2 == 0 ? "Finance" : "Admin", "5.00"));
        byte[] pdf = ReceivablesAgingDocuments.pdf(response(many, List.of("dept"), true), CHOICES, HEADER);
        assertTrue(new String(pdf, 0, 5).startsWith("%PDF"));
        PdfReader reader = new PdfReader(pdf);
        int pages = reader.getNumberOfPages();
        assertTrue(pages >= 3, "pages: " + pages);
        assertEquals(842f, reader.getPageSizeWithRotation(1).getHeight() + 230f, 0.5f, "legal landscape is 1008 x 612");
        PdfTextExtractor text = new PdfTextExtractor(reader);
        String first = text.getTextFromPage(1);
        assertTrue(first.contains("AGING OF RECEIVABLES"), first);
        assertTrue(first.contains("Sample Multi") && first.contains("Purpose Cooperative"), "the cooperative heads the page");
        assertTrue(first.contains("260,000.00"), "the total outstanding in the summary");
        assertTrue(first.contains("Page 1 of"), "the page number (the total is drawn from a template the extractor does not read)");
        String last = text.getTextFromPage(pages);
        assertTrue(last.contains("Outstanding"), "the header repeats on every page");
        assertTrue(last.contains("All receivables"));
        assertTrue(last.contains("Page " + pages + " of"));
        reader.close();
    }

    @Test
    void pdfFitsBillionsByShrinkingTheType() throws Exception {
        byte[] pdf = ReceivablesAgingDocuments.pdf(response(BOOK, List.of("dept", "aging"), true), CHOICES, HEADER);
        PdfReader reader = new PdfReader(pdf);
        String page = new PdfTextExtractor(reader).getTextFromPage(1);
        assertTrue(page.contains("2,699,054,350.25"), "the largest figure prints whole");
        reader.close();
    }
}
