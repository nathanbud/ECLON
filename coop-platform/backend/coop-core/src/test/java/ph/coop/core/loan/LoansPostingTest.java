package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Reads real loans through the real code (nothing is written). Runs only when DB_PASSWORD is set. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class LoansPostingTest {

    /** The old data stops here, so arrears are measured from this day rather than today. */
    private static final LocalDate DATA_END = LocalDate.of(2026, 4, 24);

    @Autowired LoansController loans;
    @Autowired JdbcTemplate jdbc;

    /** A loan with something left to pay, a schedule and at least two principal movements in the ledger. */
    private Map<String, Object> someLoan() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.ClientIDLoan AS client, l.LoanSLT_CODE AS typ, l.LoanREF_NO AS ref FROM loan l
                WHERE l.LoanBR_CODE = 1 AND l.LoanTR_DATE BETWEEN '2026-02-01' AND '2026-02-28' AND l.LoanPAMT > 10000
                  AND (SELECT COUNT(*) FROM amortsched a WHERE a.BR_CODE = 1 AND a.CLIENTID = l.ClientIDLoan AND a.SLC_CODE = 12 AND a.SLT_CODE = l.LoanSLT_CODE AND a.REF_NO = l.LoanREF_NO) >= 4
                  AND (SELECT COUNT(*) FROM sldtl d WHERE d.SL_BRCODE = 1 AND d.SL_CLIENTID = l.ClientIDLoan AND d.SLC_CODE = 12 AND d.SLT_CODE = l.LoanSLT_CODE AND d.REF_NO = l.LoanREF_NO AND d.SLE_CODE = 11) >= 2
                  AND (SELECT SUM(d.AMT) FROM sldtl d WHERE d.SL_BRCODE = 1 AND d.SL_CLIENTID = l.ClientIDLoan AND d.SLC_CODE = 12 AND d.SLT_CODE = l.LoanSLT_CODE AND d.REF_NO = l.LoanREF_NO AND d.SLE_CODE = 11) > 0
                ORDER BY l.LoanREF_NO DESC LIMIT 1
                """);
        assumeTrue(!rows.isEmpty(), "no open loan from February 2026 with a schedule and payments");
        return rows.get(0);
    }

    @Test
    void outstandingIsThePlainSumOfThePrincipalEntries() {
        Map<String, Object> a = someLoan();
        int client = ((Number) a.get("client")).intValue(), typ = ((Number) a.get("typ")).intValue(), ref = ((Number) a.get("ref")).intValue();
        BigDecimal expected = jdbc.queryForObject("SELECT SUM(AMT) FROM sldtl WHERE SL_BRCODE = 1 AND SL_CLIENTID = ? AND SLC_CODE = 12 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11", BigDecimal.class, client, typ, ref);
        LoansController.Summary s = loans.loans(1, client, DATA_END, false);
        LoansController.Loan l = s.loans().stream().filter((x) -> x.typeCode() == typ && x.refNo() == ref).findFirst().orElseThrow();
        assertEquals(0, expected.compareTo(l.outstanding()));
        assertEquals(0, l.amount().subtract(l.outstanding()).compareTo(l.principalPaid()));
        assertTrue(l.interestPaid().signum() >= 0);
        assertEquals(0, s.loans().stream().map(LoansController.Loan::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(s.totalOutstanding()));
        assertTrue(s.loans().stream().allMatch((x) -> x.outstanding().signum() > 0), "paid loans are left out unless asked for");
        assertTrue(loans.loans(1, client, DATA_END, true).loans().size() >= s.loans().size());
    }

    @Test
    void theDetailShowsASchedulePaidAgainstThePrincipalPaidAndALedgerThatAddsUp() {
        Map<String, Object> a = someLoan();
        int client = ((Number) a.get("client")).intValue(), typ = ((Number) a.get("typ")).intValue(), ref = ((Number) a.get("ref")).intValue();
        LoansController.Detail d = loans.detail(1, client, typ, ref, DATA_END);

        // the schedule asks for the whole principal, and what is marked paid adds up to what was paid
        BigDecimal scheduled = d.schedule().stream().map(LoansController.ScheduleRow::principalDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(scheduled.subtract(d.loan().amount()).abs().compareTo(new BigDecimal("1.00")) <= 0, "scheduled principal is the loan amount");
        BigDecimal marked = d.schedule().stream().map(LoansController.ScheduleRow::principalPaid).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, d.loan().principalPaid().min(scheduled).compareTo(marked.min(scheduled)));
        assertTrue(d.schedule().stream().map(LoansController.ScheduleRow::state).allMatch((s) -> List.of("PAID", "PARTIAL", "PAST_DUE", "UPCOMING").contains(s)));

        // the ledger: principal balance after the last principal entry is the outstanding amount
        List<LoansController.Entry> principal = d.entries().stream().filter((e) -> e.event() == 11).toList();
        assertTrue(principal.size() >= 2);
        assertEquals(0, d.loan().outstanding().compareTo(principal.get(principal.size() - 1).principalBalance()));
        assertEquals(0, principal.get(0).debit().compareTo(d.loan().amount()), "the release is the first principal entry, a debit of the loan amount");
        assertFalse(d.entriesTruncated());
        assertTrue(d.entries().stream().allMatch((e) -> e.debit().signum() == 0 || e.credit().signum() == 0));
    }

    @Test
    void arrearsAreMeasuredFromTheGivenDate() {
        Map<String, Object> a = someLoan();
        int client = ((Number) a.get("client")).intValue();
        LoansController.Loan early = loans.loans(1, client, LocalDate.of(2026, 2, 12), false).loans().stream()
            .filter((x) -> x.refNo() == ((Number) a.get("ref")).intValue() && x.typeCode() == ((Number) a.get("typ")).intValue()).findFirst().orElseThrow();
        assertEquals(0, early.pastDuePrincipal().signum(), "nothing is due two days after the release");
        assertEquals(0, early.daysPastDue());
        assertTrue(early.nextDueDate() != null && early.nextDueDate().isAfter(LocalDate.of(2026, 2, 12)));
    }

    @Test
    void refusesUnknownLoans() {
        assertThrows(ResponseStatusException.class, () -> loans.detail(1, 1, 99, 99999999, DATA_END));
    }
}
