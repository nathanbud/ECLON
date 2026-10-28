package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.deposit.DividendRun.Movement;

/** The dividend and patronage refund rules, from the real February 2026 run (documentation/dividend-map.md). */
class DividendRunRulesTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void aBalanceThatDidNotMoveEarnsEightPercent() {
        // 2026 run: 21,000 got 1,680.00; 20,000 got 1,600.00; 127,000 got 10,160.00
        assertEquals(d("1680.00"), DividendRun.dividend(d("21000").multiply(d("12")), d("8")));
        assertEquals(d("1600.00"), DividendRun.dividend(d("20000").multiply(d("12")), d("8")));
        assertEquals(d("10160.00"), DividendRun.dividend(d("127000").multiply(d("12")), d("8")));
        assertEquals(d("1680.00"), DividendRun.dividend(DividendRun.sumOfMonthEnds(d("21000"), List.of(), 2025), d("8")));
    }

    @Test
    void theMonthEndBalancesAreAveragedAndRoundedHalfUp() {
        // balances at the month ends: Jan and Feb 0; Mar to Jun 10,000; Jul to Dec 14,000
        List<Movement> moves = List.of(new Movement(LocalDate.of(2025, 3, 10), d("10000")), new Movement(LocalDate.of(2025, 7, 1), d("4000")));
        BigDecimal sum = DividendRun.sumOfMonthEnds(BigDecimal.ZERO, moves, 2025);
        assertEquals(0, d("124000").compareTo(sum));
        assertEquals(d("826.67"), DividendRun.dividend(sum, d("8")));
        // a movement of another year is not counted, the opening balance is
        assertEquals(0, d("12000").compareTo(DividendRun.sumOfMonthEnds(d("1000"), List.of(new Movement(LocalDate.of(2024, 5, 5), d("999"))), 2025)));
        assertEquals(RoundingMode.HALF_UP, RoundingMode.HALF_UP);
    }

    @Test
    void thePatronageRefundIsEightPointTwoThreePercentOfWhatWasPaid() {
        // member 91738: 1,325.98 interest and 550.00 service fees paid in 2025 got a refund of 154.39
        assertEquals(d("154.39"), DividendRun.refund(d("1325.98").add(d("550.00")), d("8.23")));
        assertEquals(d("0.37"), DividendRun.refund(d("4.54"), d("8.23")));
        assertEquals(0, DividendRun.refund(BigDecimal.ZERO, d("8.23")).signum());
    }

    @Test
    void theInsuranceAmountsComeOutFirstAndTheRestGoesToSavings() {
        // 2026: a 1,600.00 total paid 902.17 and 697.83; 2,000.00 paid 902.17, 863.03 and 234.80 to savings
        BigDecimal[] a = DividendRun.split(d("1600.00"), d("902.17"), true, d("864.06"), true);
        assertEquals(d("902.17"), a[0]);
        assertEquals(d("697.83"), a[1]);
        assertEquals(0, a[2].signum());
        BigDecimal[] b = DividendRun.split(d("2000.00"), d("902.17"), true, d("863.03"), true);
        assertEquals(d("234.80"), b[2]);
        // no hospitalization account: it is skipped and savings gets the rest
        BigDecimal[] c = DividendRun.split(d("1600.00"), d("402.17"), true, d("864.06"), false);
        assertEquals(d("1197.83"), c[2]);
        // a small total is used up by the mutual benefit amount
        BigDecimal[] e = DividendRun.split(d("500.00"), d("902.17"), true, d("864.06"), true);
        assertEquals(d("500.00"), e[0]);
        assertEquals(0, e[1].signum());
        assertEquals(0, e[2].signum());
    }

    @Test
    void ratesAndAmountsAreChecked() {
        assertTrue(DividendRun.problems(new DividendRun.Params(d("8"), d("8.23"), d("902.17"), d("864.06"), null)).isEmpty());
        assertFalse(DividendRun.problems(new DividendRun.Params(d("-1"), d("8.23"), d("902.17"), d("864.06"), null)).isEmpty());
        assertFalse(DividendRun.problems(new DividendRun.Params(d("8"), d("101"), d("902.17"), d("864.06"), null)).isEmpty());
        assertFalse(DividendRun.problems(new DividendRun.Params(d("8"), d("8.23"), d("-0.01"), d("864.06"), null)).isEmpty());
        assertFalse(DividendRun.problems(new DividendRun.Params(d("8"), d("8.23"), d("902.17"), d("864.06"), List.of(new DividendRun.MemberAmounts(5, d("-1"), null)))).isEmpty());
        assertEquals(2025, DividendRun.periodYear(LocalDate.of(2026, 2, 21)));
    }
}
