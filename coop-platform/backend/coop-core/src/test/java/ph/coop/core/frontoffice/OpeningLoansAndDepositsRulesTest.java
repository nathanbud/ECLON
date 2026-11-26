package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.frontoffice.OpeningBalanceRules.Line;
import ph.coop.core.frontoffice.OpeningBalanceRules.Terms;
import ph.coop.core.loan.LoanSchedule;
import ph.coop.core.member.SpreadsheetReader;

/** Opening balances of loans still being paid and time deposits still running: what such a line must give, the loan's remaining schedule, and reading them from a file. */
class OpeningLoansAndDepositsRulesTest {

    private static final LocalDate DAY = LocalDate.of(2027, 1, 1);
    private static final String WHY = "Opening balances as of 1 January 2027";

    private static Line gl(String account, String debit, String credit) {
        return new Line("GL", null, null, null, account, new BigDecimal(debit), new BigDecimal(credit), null);
    }

    private static Line loan(String owed, Terms t) {
        return new Line("LOAN", 7, 10, null, null, new BigDecimal(owed), BigDecimal.ZERO, null, t);
    }

    private static Line td(String principal, Terms t) {
        return new Line("TD", 8, 2, null, null, BigDecimal.ZERO, new BigDecimal(principal), null, t);
    }

    private static Terms loanTerms(Integer months, LocalDate nextDue) {
        return new Terms(null, null, months, nextDue, new BigDecimal("50000.00"), LocalDate.of(2026, 1, 15), null);
    }

    private static Terms tdTerms(String accrued) {
        return new Terms(null, new BigDecimal("5"), 360, LocalDate.of(2026, 10, 1), null, null, accrued == null ? null : new BigDecimal(accrued));
    }

    private static List<String> problems(Line... lines) {
        return OpeningBalanceRules.problems(List.of(lines), WHY, DAY, DAY);
    }

    @Test
    void aLoanIsADebitAndATimeDepositAndItsAccruedInterestAreCredits() {
        // loan 30,000 owed against cash; certificate 100,000 plus 1,250 accrued interest against cash and the interest already expensed
        assertEquals(List.of(), problems(loan("30000.00", loanTerms(12, LocalDate.of(2027, 1, 15))), gl("10101", "0", "30000.00"),
            gl("10102", "100000.00", "0"), gl("50105", "1250.00", "0"), td("100000.00", tdTerms("1250.00"))));
        // without the accrued interest's other side the batch is out by exactly that much
        List<String> p = problems(gl("10102", "100000.00", "0"), td("100000.00", tdTerms("1250.00")));
        assertEquals(1, p.size(), p.toString());
        assertTrue(p.get(0).contains("difference of 1250.00 on the credit side"), p.get(0));
    }

    @Test
    void aLoanNeedsItsRemainingTermAndANextPaymentAfterTheOpening() {
        assertTrue(String.join(" ", problems(loan("100.00", null), gl("10101", "0", "100.00"))).contains("months the loan still runs and the date its next payment is due"));
        String due = String.join(" ", problems(loan("100.00", loanTerms(12, DAY)), gl("10101", "0", "100.00")));
        assertTrue(due.contains("next payment must be due after the opening date"), due);
        String months = String.join(" ", problems(loan("100.00", loanTerms(0, LocalDate.of(2027, 2, 1))), gl("10101", "0", "100.00")));
        assertTrue(months.contains("months the loan still runs"), months);
        Line credit = new Line("LOAN", 7, 10, null, null, BigDecimal.ZERO, new BigDecimal("100.00"), null, loanTerms(12, LocalDate.of(2027, 2, 1)));
        assertTrue(String.join(" ", problems(credit, gl("10101", "100.00", "0"))).contains("a loan's opening balance is a debit"));
    }

    @Test
    void aLoanCannotOweMoreThanItGrantedNorHaveAStatusItCannotBeOpenedWith() {
        Terms t = new Terms(15, new BigDecimal("150"), 12, LocalDate.of(2027, 2, 1), new BigDecimal("10.00"), LocalDate.of(2027, 3, 1), null);
        String p = String.join(" ", problems(loan("100.00", t), gl("10101", "0", "100.00")));
        assertTrue(p.contains("status can be current, past due"), p);
        assertTrue(p.contains("from 0 to 100 percent"), p);
        assertTrue(p.contains("cannot owe more principal than the loan granted"), p);
        assertTrue(p.contains("granted after the opening date"), p);
    }

    @Test
    void aTimeDepositIsPlacedOnOrBeforeTheOpeningWithATermAndARate() {
        Terms t = new Terms(null, null, 0, LocalDate.of(2027, 2, 1), null, null, new BigDecimal("-1"));
        String p = String.join(" ", problems(td("100.00", t), gl("10101", "100.00", "0")));
        assertTrue(p.contains("placed after the opening date"), p);
        assertTrue(p.contains("term in days"), p);
        assertTrue(p.contains("yearly interest rate"), p);
        assertTrue(p.contains("accrued interest cannot be negative"), p);
        assertEquals(LocalDate.of(2027, 9, 26), OpeningBalanceRules.maturity(tdTerms(null)));
    }

    @Test
    void aLoanOrTimeDepositGetsItsNumberWhenPostedAndAppearsAsOftenAsTheMemberHasThem() {
        Line numbered = new Line("LOAN", 7, 10, 3, null, new BigDecimal("100.00"), BigDecimal.ZERO, null, loanTerms(12, LocalDate.of(2027, 2, 1)));
        assertTrue(String.join(" ", problems(numbered, gl("10101", "0", "100.00"))).contains("gets its number then"));
        // two loans of the same type for one member are two lines, not a duplicate
        assertEquals(List.of(), problems(loan("100.00", loanTerms(12, LocalDate.of(2027, 2, 1))), loan("50.00", loanTerms(6, LocalDate.of(2027, 2, 1))), gl("10101", "0", "150.00")));
    }

    @Test
    void theRemainingScheduleStartsOnePeriodBeforeTheNextPaymentAndRunsDownWhatIsOwed() {
        Terms t = loanTerms(12, LocalDate.of(2027, 1, 15));
        List<LoanSchedule.Row> rows = OpeningBalanceRules.loanSchedule(new BigDecimal("12000.00"), t, new BigDecimal("12"), LoanSchedule.DIMINISHING, LoanSchedule.MONTHLY, false);
        assertEquals(13, rows.size());
        assertEquals(LocalDate.of(2026, 12, 15), rows.get(0).dueDate());
        assertEquals(LocalDate.of(2027, 1, 15), rows.get(1).dueDate());
        assertEquals(new BigDecimal("1000.00"), rows.get(1).principalDue());
        assertEquals(LocalDate.of(2027, 12, 15), rows.get(12).dueDate());
        assertEquals(0, rows.get(12).principalBalance().signum());

        // quarterly payments need whole quarters; a mode or method the schedules do not cover is refused in words
        Terms odd = loanTerms(10, LocalDate.of(2027, 3, 15));
        IllegalArgumentException q = assertThrows(IllegalArgumentException.class,
            () -> OpeningBalanceRules.loanSchedule(new BigDecimal("100.00"), odd, BigDecimal.TEN, LoanSchedule.DIMINISHING, LoanSchedule.QUARTERLY, false));
        assertTrue(q.getMessage().contains("whole payment periods of 3 months"), q.getMessage());
        IllegalArgumentException m = assertThrows(IllegalArgumentException.class,
            () -> OpeningBalanceRules.loanSchedule(new BigDecimal("100.00"), t, BigDecimal.TEN, 2, LoanSchedule.MONTHLY, false));
        assertTrue(m.getMessage().contains("diminishing balance or annuity"), m.getMessage());

        // a payment on the 30th cannot be kept after February: the person is told the date the schedule would give
        Terms thirtieth = loanTerms(12, LocalDate.of(2027, 3, 30));
        IllegalArgumentException d = assertThrows(IllegalArgumentException.class,
            () -> OpeningBalanceRules.loanSchedule(new BigDecimal("100.00"), thirtieth, BigDecimal.TEN, LoanSchedule.DIMINISHING, LoanSchedule.MONTHLY, false));
        assertTrue(d.getMessage().contains("2027-03-28"), d.getMessage());
    }

    @Test
    void aFileGivesLoansAndTimeDepositsWithTheirTerms() {
        Map<String, String> l = new LinkedHashMap<>();
        l.put("Kind", "Loan"); l.put("Member", "7"); l.put("Type", "10"); l.put("Debit", "30,000.00"); l.put("Status", "Past due"); l.put("Rate", "12");
        l.put("Term", "12"); l.put("Next due", "2027-01-15"); l.put("Amount granted", "50000"); l.put("Granted on", "2026-01-15");
        Map<String, String> t = new LinkedHashMap<>();
        t.put("Kind", "Time deposit"); t.put("Member", "8"); t.put("Type", "2"); t.put("Credit", "100000"); t.put("Rate", "5"); t.put("Term", "360");
        t.put("Placed on", "2026-10-01"); t.put("Accrued interest", "1250.00"); t.put("Certificate no", "TD-0001");
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("Kind", "Loan"); bad.put("Member", "7"); bad.put("Type", "10"); bad.put("Next due", "15/01/2027");
        var read = OpeningBalanceRules.read(List.of(new SpreadsheetReader.Row(2, l), new SpreadsheetReader.Row(3, t), new SpreadsheetReader.Row(4, bad)));
        assertEquals(2, read.lines().size());
        Line loan = read.lines().get(0), deposit = read.lines().get(1);
        assertEquals("LOAN", loan.kind());
        assertEquals(12, loan.terms().status());
        assertEquals(LocalDate.of(2027, 1, 15), loan.terms().start());
        assertEquals("TD", deposit.kind());
        assertEquals(new BigDecimal("1250.00"), deposit.accrued());
        assertEquals("Old certificate TD-0001", deposit.note(), "the paper certificate's number is kept in the note");
        assertEquals(1, read.problems().size());
        assertTrue(read.problems().get(0).startsWith("File line 4: "), read.problems().get(0));
    }
}
