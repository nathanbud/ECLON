package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.loan.LoanWorksheet.Posting;
import ph.coop.core.loan.LoanWorksheet.Result;
import ph.coop.core.loan.LoanWorksheet.Row;
import ph.coop.core.loan.LoanWorksheet.ScheduleDate;

/** The worksheet rules against the real printed worksheet of loan 1221-00003212 as of 2026-04-24 (pics/intwork.pdf): every figure to the centavo. */
class LoanWorksheetTest {

    private static BigDecimal m(String s) { return new BigDecimal(s); }
    private static LocalDate d(String s) { return LocalDate.parse(s); }
    private static final BigDecimal ZERO = m("0");

    static Result sample() {
        List<Posting> posts = List.of(
            new Posting(d("2026-01-28"), "LV", "00001999", "00001999", ZERO, m("1129000.00"), ZERO, ZERO),
            new Posting(d("2026-02-13"), "CV", "00036970", "*0036970", m("7770.46"), ZERO, m("342.86"), ZERO),
            new Posting(d("2026-02-26"), "CV", "00037496", "*0037496", ZERO, ZERO, m("8936.56"), ZERO),
            new Posting(d("2026-03-13"), "CV", "00038103", "*0038103", m("7834.32"), ZERO, m("279.02"), ZERO),
            new Posting(d("2026-03-31"), "CV", "00038891", "*0038891", ZERO, ZERO, m("8936.57"), ZERO),
            new Posting(d("2026-04-15"), "CV", "00039463", "*0039463", m("7898.72"), ZERO, m("1037.85"), ZERO));
        List<ScheduleDate> sched = List.of(
            new ScheduleDate(d("2026-01-28"), m("1129000.00")), new ScheduleDate(d("2026-02-28"), m("1121229.54")), new ScheduleDate(d("2026-03-28"), m("1113395.22")),
            new ScheduleDate(d("2026-04-28"), m("1105496.50")), new ScheduleDate(d("2026-05-28"), m("1097532.86")));
        return LoanWorksheet.compute(d("2026-01-28"), m("10"), m("36"), posts, sched, d("2026-04-24"));
    }

    @Test
    void everyRowMatchesTheOldWorksheet() {
        List<Row> r = sample().rows();
        assertEquals(9, r.size());
        // date, principal balance, days, interest debit, interest credit, interest balance, should-be balance
        Object[][] want = {
            {"2026-01-28", "1129000.00", null, "0.00", "0.00", "0.00", null},
            {"2026-02-13", "1121229.54", 16, "4949.04", "342.86", "4606.18", null},
            {"2026-02-26", "1121229.54", null, "0.00", "8936.56", "-4330.38", null},
            {"2026-02-28", "1121229.54", 15, "4607.79", "0.00", "277.41", "1121229.54"},
            {"2026-03-13", "1113395.22", 13, "3993.42", "279.02", "3991.81", null},
            {"2026-03-28", "1113395.22", 15, "4575.60", "0.00", "8567.41", "1113395.22"},
            {"2026-03-31", "1113395.22", null, "0.00", "8936.57", "-369.16", null},
            {"2026-04-15", "1105496.50", 18, "5490.72", "1037.85", "4083.71", null},
            {"2026-04-24", "1105496.50", 9, "2725.88", "0.00", "6809.59", "1113395.22"},
        };
        for (int i = 0; i < want.length; i++) {
            Row x = r.get(i);
            String at = "row " + (i + 1);
            assertEquals(d((String) want[i][0]), x.date(), at);
            assertEquals(0, m((String) want[i][1]).compareTo(x.principalBalance()), at + " principal balance");
            if (want[i][2] == null) assertTrue(x.days() == null || x.days() == 0 || x.interestDebit().signum() == 0, at + " no accrual"); else assertEquals(want[i][2], x.days(), at + " days");
            assertEquals(0, m((String) want[i][3]).compareTo(x.interestDebit()), at + " interest accrued");
            assertEquals(0, m((String) want[i][4]).compareTo(x.interestCredit()), at + " interest paid");
            assertEquals(0, m((String) want[i][5]).compareTo(x.interestBalance()), at + " interest balance");
            if (want[i][6] == null) assertNull(x.shouldBeBalance(), at); else assertEquals(0, m((String) want[i][6]).compareTo(x.shouldBeBalance()), at + " should be");
            assertEquals(0, x.penaltyBalance().signum(), at + " the loan was never late");
        }
        assertTrue(r.get(8).cutoff());
    }

    @Test
    void totalsAndAmountsCollectibleMatch() {
        Result r = sample();
        assertEquals(0, m("1129000.00").compareTo(r.totals().principalDebit()));
        assertEquals(0, m("23503.50").compareTo(r.totals().principalCredit()));
        assertEquals(0, m("26342.45").compareTo(r.totals().interestDebit()));
        assertEquals(0, m("19532.86").compareTo(r.totals().interestCredit()));
        var s = r.summary();
        assertEquals(0, m("6809.59").compareTo(s.interestDue()));
        assertEquals(0, m("6809.59").compareTo(s.totalDue()));
        assertEquals(0, s.principalDue().signum());
        assertEquals(0, m("7898.72").compareTo(s.principalOver()));
        assertEquals(0, m("7898.72").compareTo(s.totalOver()));
        assertEquals("ADVANCE PAYMENT", s.status());
    }

    @Test
    void aLateLoanShowsDelayedPrincipalAndPenaltyAndIsPastDue() {
        // Nothing paid after the release: by the 2nd due date the whole first two instalments are delayed.
        List<Posting> posts = List.of(new Posting(d("2026-01-28"), "LV", "1", "1", ZERO, m("1129000.00"), ZERO, ZERO));
        List<ScheduleDate> sched = List.of(new ScheduleDate(d("2026-01-28"), m("1129000.00")), new ScheduleDate(d("2026-02-28"), m("1121229.54")), new ScheduleDate(d("2026-03-28"), m("1113395.22")));
        Result r = LoanWorksheet.compute(d("2026-01-28"), m("10"), m("36"), posts, sched, d("2026-04-24"));
        assertEquals(0, m("15604.78").compareTo(r.summary().principalDue()), "1,129,000 less the 1,113,395.22 the schedule expects by 03/28");
        assertTrue(r.summary().penaltyDue().signum() > 0);
        assertEquals("PAST DUE", r.summary().status());
        assertEquals(0, r.summary().totalOver().signum());
    }
}
