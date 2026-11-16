package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The real loan 1221-00003212 of member 1-28711 as of 2026-04-24, read from the database: the figures printed on the old system's worksheet (pics/intwork.pdf), to the centavo,
 * and the PDF and Excel downloads with their audit and export-log rows.
 */
@Tag("realdata")
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.audit.seal.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoanWorksheetRealDataTest {

    @Autowired LoanWorksheetService service;
    @Autowired LoanWorksheetController controller;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate AS_OF = LocalDate.parse("2026-04-24");

    private static void same(String want, BigDecimal got, String what) {
        assertEquals(0, new BigDecimal(want).compareTo(got), what + ": wanted " + want + " but got " + got);
    }

    @Test
    void theHeaderAndEveryFigureMatchTheOldWorksheet() {
        var w = service.build(1, 28711, 21, 3212, AS_OF).orElseThrow();
        var h = w.header();
        assertEquals("0001-0028711-9", h.accountNo());
        assertEquals("Osting, Samson M.", h.borrower());
        assertEquals("1221-00003212", h.refNo());
        assertEquals("Salary Loan - Salary", h.loanType());
        assertEquals("Main Office", h.officeName());
        assertEquals("004-034-232-000", h.tin());
        assertEquals("8.00 Years", h.term());
        assertEquals("Annuity", h.amortization());
        assertEquals("Monthly", h.paymentMode());
        assertEquals(LocalDate.parse("2034-01-28"), h.maturity());
        assertEquals("Current", h.status());
        same("1129000.00", h.principal(), "principal");
        same("10", h.interestRate(), "interest rate");
        same("36", h.penaltyRate(), "penalty rate");

        var r = w.result();
        assertEquals(9, r.rows().size());
        same("23503.50", r.totals().principalCredit(), "principal paid");
        same("26342.45", r.totals().interestDebit(), "interest accrued");
        same("19532.86", r.totals().interestCredit(), "interest paid");
        same("6809.59", r.summary().interestDue(), "interest due");
        same("6809.59", r.summary().totalDue(), "total due");
        same("7898.72", r.summary().principalOver(), "principal paid ahead");
        assertEquals("ADVANCE PAYMENT", r.summary().status());
        // every interest balance down the page
        String[] balances = {"0.00", "4606.18", "-4330.38", "277.41", "3991.81", "8567.41", "-369.16", "4083.71", "6809.59"};
        for (int i = 0; i < balances.length; i++) same(balances[i], r.rows().get(i).interestBalance(), "interest balance of row " + (i + 1));
    }

    @Test
    void anUnknownLoanIsNotFound() {
        assertTrue(service.build(1, 28711, 21, 999999999, AS_OF).isEmpty());
    }

    @Test
    void theDownloadsAreRealFilesAndAreAuditedAndLogged() {
        var user = new TestingAuthenticationToken("itest-teller", "x", "ROLE_USER");
        var pdf = controller.pdf(1, 28711, 21, 3212, "2026-04-24", user);
        assertEquals("application/pdf", String.valueOf(pdf.getHeaders().getContentType()));
        assertTrue(new String(pdf.getBody(), 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
        assertTrue(pdf.getHeaders().getFirst("Content-Disposition").contains("loan-worksheet-1221-00003212-2026-04-24.pdf"));
        var xlsx = controller.xlsx(1, 28711, 21, 3212, "2026-04-24", user);
        assertEquals((byte) 'P', xlsx.getBody()[0], "an .xlsx file is a zip archive");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE username = 'itest-teller' AND action = 'loan.worksheet.pdf'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_data_export WHERE username = 'itest-teller' AND export_type = 'REPORT' AND purpose LIKE 'Loan interest and penalty worksheet%'", Integer.class));
    }

    @Test
    void badDatesAndLoansAreRefusedPlainly() {
        var user = new TestingAuthenticationToken("itest-teller", "x", "ROLE_USER");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> controller.pdf(1, 28711, 21, 3212, "next week", user)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> controller.pdf(1, 28711, 21, 3212, LocalDate.now().plusDays(1).toString(), user)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> controller.pdf(1, 28711, 21, 3212, "2025-01-01", user)).getStatusCode(), "before the release");
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> controller.pdf(1, 28711, 21, 999999999, "2026-04-24", user)).getStatusCode());
    }
}
