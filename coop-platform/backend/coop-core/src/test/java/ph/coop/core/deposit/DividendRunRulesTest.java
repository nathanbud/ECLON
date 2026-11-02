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

    // ---- the specification's method: rates worked out from the allocations (divpat_system.docx, sections 3.4 and 3.5) ----

    @Test
    void theSpecificationsSampleWorksOut() {
        // Total average share-month 10,000,000 and allocation 1,000,000: ISC rate 10%; a member with 100,000 gets 10,000
        BigDecimal isc = DividendRun.rateFor(d("1000000"), d("10000000"));
        assertEquals(0, isc.compareTo(d("10")));
        assertEquals(d("10000.00"), DividendRun.dividend(d("100000").multiply(d("12")), isc));
        // Total qualified patronage 5,000,000 and allocation 1,500,000: PR rate 30%; a member with 50,000 gets 15,000
        BigDecimal pr = DividendRun.rateFor(d("1500000"), d("5000000"));
        assertEquals(0, pr.compareTo(d("30")));
        assertEquals(d("15000.00"), DividendRun.refund(d("50000"), pr));
    }

    @Test
    void aRateIsZeroWhenThereIsNothingToSpreadItOver() {
        assertEquals(0, DividendRun.rateFor(d("1000"), BigDecimal.ZERO).signum());
        assertEquals(0, DividendRun.rateFor(null, d("100")).signum());
    }

    private static DividendRun.Params allocation(String netSurplus, String resolution, List<DividendRun.Statutory> funds, String isc, String pr) {
        return new DividendRun.Params(null, null, d("902.17"), d("864.06"), null, isc == null ? null : d(isc), pr == null ? null : d(pr), netSurplus == null ? null : d(netSurplus), funds, resolution);
    }

    @Test
    void whatIsLeftOfTheNetSurplusIsWhatMayBeDistributed() {
        List<DividendRun.Statutory> funds = List.of(new DividendRun.Statutory("Reserve fund", d("10")), new DividendRun.Statutory("Education and training", d("5")));
        assertEquals(d("850000.00"), DividendRun.available(d("1000000"), funds));
        assertEquals(d("1000000.00"), DividendRun.available(d("1000000"), List.of()));
    }

    @Test
    void anAllocationNeedsAnApprovedSurplusAResolutionAndMustFitInIt() {
        List<DividendRun.Statutory> funds = List.of(new DividendRun.Statutory("Reserve fund", d("10")));
        assertTrue(DividendRun.problems(allocation("1000000", "GA Res. 2026-03", funds, "500000", "300000")).isEmpty());
        assertFalse(DividendRun.problems(allocation(null, "GA Res. 2026-03", funds, "500000", "300000")).isEmpty(), "no net surplus");
        assertFalse(DividendRun.problems(allocation("0", "GA Res. 2026-03", funds, "500000", "300000")).isEmpty(), "zero net surplus");
        assertFalse(DividendRun.problems(allocation("1000000", " ", funds, "500000", "300000")).isEmpty(), "no resolution");
        assertFalse(DividendRun.problems(allocation("1000000", "GA", funds, "600000", "400000")).isEmpty(), "more than is left after the funds");
        assertFalse(DividendRun.problems(allocation("1000000", "GA", List.of(new DividendRun.Statutory("A", d("60")), new DividendRun.Statutory("B", d("50"))), "1", null)).isEmpty(), "funds over 100%");
        assertFalse(DividendRun.problems(allocation("1000000", "GA", List.of(new DividendRun.Statutory("", d("10"))), "1", null)).isEmpty(), "a fund without a name");
        assertFalse(DividendRun.problems(allocation("1000000", "GA", funds, "-1", null)).isEmpty(), "negative allocation");
    }

    @Test
    void allocationsDoNotNeedTypedRates() {
        assertTrue(allocation("1000000", "GA", List.of(), "100", "100").fromAllocation());
        assertFalse(new DividendRun.Params(d("8"), d("8.23"), d("902.17"), d("864.06"), null).fromAllocation());
    }
}
