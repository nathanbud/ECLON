package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.loan.LoanRules.Arrears;
import ph.coop.core.loan.LoanRules.Due;
import ph.coop.core.loan.LoanRules.Row;
import ph.coop.core.loan.LoanRules.State;

class LoanRulesTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    /** A 12,000 loan paid monthly in four instalments of 3,000 from 10 March; the first row (the release date) has nothing due. */
    private static final List<Due> DUES = List.of(
        new Due(LocalDate.of(2026, 2, 10), d("0"), d("0")),
        new Due(LocalDate.of(2026, 3, 10), d("3000"), d("100")),
        new Due(LocalDate.of(2026, 4, 10), d("3000"), d("75")),
        new Due(LocalDate.of(2026, 5, 10), d("3000"), d("50")),
        new Due(LocalDate.of(2026, 6, 10), d("3000"), d("25")));

    private static List<BigDecimal> balances() {
        List<BigDecimal> b = new ArrayList<>();
        BigDecimal left = d("12000");
        b.add(left);
        for (int i = 1; i < DUES.size(); i++) { left = left.subtract(d("3000")); b.add(left); }
        return b;
    }

    private static List<State> states(String paid, String asOf) {
        return LoanRules.schedule(DUES, balances(), d(paid), LocalDate.parse(asOf)).stream().map(Row::state).toList();
    }

    @Test
    void aLoanPaidUpToDateHasNoArrears() {
        Arrears a = LoanRules.arrears(DUES, d("6000"), LocalDate.of(2026, 4, 20));
        assertEquals(0, BigDecimal.ZERO.compareTo(a.pastDuePrincipal()));
        assertEquals(0, a.daysPastDue());
        assertNull(a.oldestUnpaidDate());
        assertEquals(List.of(State.PAID, State.PAID, State.PAID, State.UPCOMING, State.UPCOMING), states("6000", "2026-04-20"));
    }

    @Test
    void countsPrincipalPastDueAndTheAgeOfTheOldestUnpaidInstalment() {
        // 3,000 paid, but 6,000 was due by 20 April: 3,000 behind, the second instalment is 10 days late
        Arrears a = LoanRules.arrears(DUES, d("3000"), LocalDate.of(2026, 4, 20));
        assertEquals(0, d("3000").compareTo(a.pastDuePrincipal()));
        assertEquals(10, a.daysPastDue());
        assertEquals(LocalDate.of(2026, 4, 10), a.oldestUnpaidDate());
        assertEquals(List.of(State.PAID, State.PAID, State.PAST_DUE, State.UPCOMING, State.UPCOMING), states("3000", "2026-04-20"));
    }

    @Test
    void aPartPaymentMarksTheInstalmentPartialOrPastDue() {
        // 4,500 paid: the first instalment is paid, half of the second; on 20 April the second is past due, on 20 March it is upcoming-partial
        List<Row> rows = LoanRules.schedule(DUES, balances(), d("4500"), LocalDate.of(2026, 4, 20));
        assertEquals(State.PAST_DUE, rows.get(2).state());
        assertEquals(0, d("1500").compareTo(rows.get(2).paidOfThis()));
        assertEquals(State.PARTIAL, LoanRules.schedule(DUES, balances(), d("4500"), LocalDate.of(2026, 3, 20)).get(2).state());
        Arrears a = LoanRules.arrears(DUES, d("4500"), LocalDate.of(2026, 4, 20));
        assertEquals(0, d("1500").compareTo(a.pastDuePrincipal()));
    }

    @Test
    void nothingPaidMeansEverythingDueIsLate() {
        Arrears a = LoanRules.arrears(DUES, BigDecimal.ZERO, LocalDate.of(2026, 5, 15));
        assertEquals(0, d("9000").compareTo(a.pastDuePrincipal()));
        assertEquals(66, a.daysPastDue(), "from 10 March to 15 May");
    }

    @Test
    void interestMatchesWhatTheOldSystemChargedOnRealPayments() {
        // outstanding, rate, last interest date, payment date, interest the old system booked
        assertEquals(0, d("337.53").compareTo(LoanRules.interest(d("44000"), d("10"), LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 10))));
        // the old system booked 416.82 here; plain rounding gives 416.83. Of 18 real payments checked, 14 match to the cent and the other 4 are one cent apart
        assertTrue(d("0.01").compareTo(d("416.82").subtract(LoanRules.interest(d("40037.53"), d("10"), LocalDate.of(2026, 3, 10), LocalDate.of(2026, 4, 17))).abs()) >= 0);
        assertEquals(0, d("4471.23").compareTo(LoanRules.interest(d("100000"), d("12"), LocalDate.of(2025, 8, 26), LocalDate.of(2026, 1, 9))));
        assertEquals(0, d("523.02").compareTo(LoanRules.interest(d("59657.28"), d("10"), LocalDate.of(2026, 3, 23), LocalDate.of(2026, 4, 24))));
        assertEquals(0, d("579.67").compareTo(LoanRules.interest(d("72958.06"), d("10"), LocalDate.of(2025, 11, 24), LocalDate.of(2025, 12, 23))));
        assertEquals(0, BigDecimal.ZERO.compareTo(LoanRules.interest(d("1000"), d("10"), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1))), "no days, no interest");
        assertEquals(0, BigDecimal.ZERO.compareTo(LoanRules.interest(d("1000"), d("10"), LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 1))), "never negative");
    }

    @Test
    void aPaymentPaysInterestFirstAndTheRestReducesPrincipal() {
        LoanRules.Split a = LoanRules.split(d("4300.00"), d("337.53"));
        assertEquals(0, d("337.53").compareTo(a.interest()));
        assertEquals(0, d("3962.47").compareTo(a.principal()));
        LoanRules.Split small = LoanRules.split(d("100.00"), d("337.53"));
        assertEquals(0, d("100.00").compareTo(small.interest()));
        assertEquals(0, BigDecimal.ZERO.compareTo(small.principal()), "less than the interest: all of it is interest");
        assertEquals(0, d("0.00").compareTo(LoanRules.split(d("500"), BigDecimal.ZERO).interest()));
    }

    @Test
    void loanStatusCodesHaveTheNamesTheLedgerControlTableGivesThem() {
        assertEquals("Current", LoansController.statusName(11));
        assertEquals("Past due", LoansController.statusName(12));
        assertEquals("In litigation", LoansController.statusName(13));
        assertEquals("Restructured", LoansController.statusName(14));
        assertEquals("Status 99", LoansController.statusName(99));
    }

    @Test
    void theNextDueIsTheFirstUnpaidInstalmentAfterToday() {
        Due n = LoanRules.nextDue(DUES, d("6000"), LocalDate.of(2026, 4, 20));
        assertEquals(LocalDate.of(2026, 5, 10), n.date());
        assertEquals(0, d("3000").compareTo(n.principal()));
        assertNull(LoanRules.nextDue(DUES, d("12000"), LocalDate.of(2026, 4, 20)), "fully paid in advance");
        assertNull(LoanRules.nextDue(DUES, BigDecimal.ZERO, LocalDate.of(2026, 7, 1)), "nothing left after the last date");
        assertTrue(LoanRules.arrears(DUES, d("12000"), LocalDate.of(2026, 7, 1)).pastDuePrincipal().signum() == 0);
    }
}
