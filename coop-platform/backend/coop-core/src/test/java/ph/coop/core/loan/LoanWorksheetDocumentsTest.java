package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import net.sf.jasperreports.engine.JasperPrint;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ph.coop.core.loan.LoanWorksheetService.Header;
import ph.coop.core.loan.LoanWorksheetService.Worksheet;

/** The PDF and Excel files written from the sample worksheet. */
class LoanWorksheetDocumentsTest {

    static Worksheet sample() {
        Header h = new Header("BENGUET STATE UNIVERSITY AND COMMUNITY MULTIPURPOSE COOPERATIVE (BSUCMPC)", "Main Office", "BSU Compound, Balili, La Trinidad, Benguet", "004-034-232-000",
            "0001-0028711-9", "Osting, Samson M.", "1221-00003212", "Salary Loan - Salary", new java.math.BigDecimal("1129000.00"), "Monthly", new java.math.BigDecimal("10.00000"),
            new java.math.BigDecimal("36.00"), LocalDate.parse("2026-01-28"), "8.00 Years", "Annuity", "Monthly", LocalDate.parse("2034-01-28"), "Monthly", "Current", "", "na");
        return new Worksheet(h, LocalDate.parse("2026-04-24"), LoanWorksheetTest.sample());
    }

    @Test
    void theExcelFileHasRealNumbersAndTheSameTotals() throws Exception {
        byte[] bytes = new LoanWorksheetDocuments().xlsx(sample(), "tester", LocalDateTime.parse("2026-10-29T14:23:00"));
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = book.getSheetAt(0);
            double interestBalanceAtCutoff = -1, totalCredits = -1;
            for (org.apache.poi.ss.usermodel.Row r : s) {
                if (r.getCell(0) != null && r.getCell(0).getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC && r.getCell(10) != null) interestBalanceAtCutoff = r.getCell(10).getNumericCellValue();
                if (r.getCell(0) != null && r.getCell(0).getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && "Totals".equals(r.getCell(0).getStringCellValue())) totalCredits = r.getCell(9).getNumericCellValue();
            }
            assertEquals(6809.59, interestBalanceAtCutoff, 0.001, "the last dated row is the cutoff");
            assertEquals(19532.86, totalCredits, 0.001);
        }
    }

    @Test
    void thePdfIsMadeAndCanBeLookedAt() throws Exception {
        byte[] pdf = new LoanWorksheetDocuments().pdf(sample(), "tester", LocalDateTime.parse("2026-10-29T14:23:00"));
        assertTrue(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
        assertTrue(pdf.length > 5_000);
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "loan-worksheet-sample.pdf");
        Files.write(out, pdf);
    }
}
