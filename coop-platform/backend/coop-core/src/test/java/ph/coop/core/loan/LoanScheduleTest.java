package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Expected rows are copied from real `amortsched` rows (see loan-posting-map.md). */
class LoanScheduleTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void monthlyLoanReleasedOnThe31stFallsDueOnThe30th() {
        List<LoanSchedule.Row> r = LoanSchedule.diminishing(bd("100000.00"), 6, LoanSchedule.MONTHLY, bd("10"), LocalDate.of(2026, 3, 31));
        assertEquals(7, r.size());
        assertEquals(new LoanSchedule.Row(LocalDate.of(2026, 4, 30), bd("16666.67"), bd("821.92"), bd("83333.33")), r.get(1));
        assertEquals(LocalDate.of(2026, 5, 30), r.get(2).dueDate());
        assertEquals(bd("684.93"), r.get(2).interestDue());
        assertEquals(bd("0.00"), r.get(6).principalBalance());
    }

    @Test
    void februaryUsesItsLastDayThenGoesBackToThe30th() {
        List<LoanSchedule.Row> r = LoanSchedule.diminishing(bd("30000.00"), 24, LoanSchedule.MONTHLY, bd("10"), LocalDate.of(2026, 3, 30));
        assertEquals(LocalDate.of(2027, 2, 28), r.get(11).dueDate());
        assertEquals(LocalDate.of(2027, 3, 30), r.get(12).dueDate());
    }

    @Test
    void lumpSumHasOneRowWithAllTheInterest() {
        List<LoanSchedule.Row> r = LoanSchedule.diminishing(bd("50000.00"), 6, LoanSchedule.LUMP_SUM, bd("10"), LocalDate.of(2026, 1, 16));
        assertEquals(2, r.size());
        assertEquals(new LoanSchedule.Row(LocalDate.of(2026, 7, 16), bd("50000.00"), bd("2479.45"), bd("0.00")), r.get(1));
    }

    @Test
    void quarterlyLoanHasOneRowPerQuarter() {
        List<LoanSchedule.Row> r = LoanSchedule.diminishing(bd("30000.00"), 6, LoanSchedule.QUARTERLY, bd("10"), LocalDate.of(2026, 1, 5));
        assertEquals(3, r.size());
        assertEquals(new LoanSchedule.Row(LocalDate.of(2026, 4, 5), bd("15000.00"), bd("739.73"), bd("15000.00")), r.get(1));
        assertEquals(new LoanSchedule.Row(LocalDate.of(2026, 7, 5), bd("15000.00"), bd("373.97"), bd("0.00")), r.get(2));
    }

    @Test
    void lastInstalmentTakesTheRounding() {
        List<LoanSchedule.Row> r = LoanSchedule.diminishing(bd("20000.00"), 12, LoanSchedule.MONTHLY, bd("10"), LocalDate.of(2026, 3, 30));
        assertEquals(bd("1666.63"), r.get(12).principalDue());
        assertEquals(bd("0.00"), r.get(12).principalBalance());
    }

    @Test
    void unmappedModesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> LoanSchedule.diminishing(bd("1000"), 6, 2, bd("10"), LocalDate.of(2026, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> LoanSchedule.diminishing(bd("1000"), 4, LoanSchedule.QUARTERLY, bd("10"), LocalDate.of(2026, 1, 1)));
    }

    // ---- annuity: expected rows are copied from real `amortsched` rows ----

    @Test
    void annuityWithThirtyDayMonthsMatchesARealSalaryLoan() {
        List<LoanSchedule.Row> r = LoanSchedule.annuity(bd("80000.00"), 12, LoanSchedule.MONTHLY, bd("10"), LocalDate.of(2026, 3, 18), true);
        assertEquals(13, r.size());
        assertEquals(new LoanSchedule.Row(LocalDate.of(2026, 4, 18), bd("6370.65"), bd("657.53"), bd("73629.35")), r.get(1));
        assertEquals(new LoanSchedule.Row(LocalDate.of(2026, 5, 18), bd("6423.01"), bd("605.17"), bd("67206.34")), r.get(2));
        assertEquals(new LoanSchedule.Row(LocalDate.of(2027, 3, 18), bd("6970.82"), bd("57.29"), bd("0.00")), r.get(12));
    }

    @Test
    void annuityPaysTheSameAmountEveryTimeExceptTheLast() {
        List<LoanSchedule.Row> r = LoanSchedule.annuity(bd("2500000.00"), 120, LoanSchedule.MONTHLY, bd("10"), LocalDate.of(2019, 6, 17), true);
        assertEquals(new LoanSchedule.Row(LocalDate.of(2019, 7, 17), bd("12300.38"), bd("20547.95"), bd("2487699.62")), r.get(1));
        assertEquals(bd("12401.48"), r.get(2).principalDue());
        BigDecimal level = r.get(1).principalDue().add(r.get(1).interestDue());
        for (int i = 2; i < 120; i++) {
            BigDecimal total = r.get(i).principalDue().add(r.get(i).interestDue());
            assertEquals(0, level.subtract(total).abs().compareTo(new BigDecimal("0.01")) <= 0 ? 0 : 1, "payment " + i);
        }
        assertEquals(bd("0.00"), r.get(120).principalBalance());
    }

    @Test
    void annuityWithRealDaysCountsTheDaysBetweenDueDates() {
        List<LoanSchedule.Row> r = LoanSchedule.annuity(bd("500000.00"), 120, LoanSchedule.MONTHLY, bd("10"), LocalDate.of(2023, 7, 28), false);
        assertEquals(new LoanSchedule.Row(LocalDate.of(2023, 8, 28), bd("2323.09"), bd("4246.58"), bd("497676.91")), r.get(1));
        assertEquals(new LoanSchedule.Row(LocalDate.of(2023, 9, 28), bd("2342.83"), bd("4226.84"), bd("495334.08")), r.get(2));
        assertEquals(new LoanSchedule.Row(LocalDate.of(2023, 10, 28), bd("2498.43"), bd("4071.24"), bd("492835.65")), r.get(3));
    }

    @Test
    void annuityLumpSumIsOnePaymentOfPrincipalAndInterest() {
        List<LoanSchedule.Row> r = LoanSchedule.annuity(bd("100000.00"), 6, LoanSchedule.LUMP_SUM, bd("10"), LocalDate.of(2026, 1, 15), true);
        assertEquals(2, r.size());
        assertEquals(bd("100000.00"), r.get(1).principalDue());
        assertEquals(bd("4931.51"), r.get(1).interestDue());
    }

    @Test
    void annuityRefusesUnmappedModes() {
        assertThrows(IllegalArgumentException.class, () -> LoanSchedule.annuity(bd("1000"), 6, 2, bd("10"), LocalDate.of(2026, 1, 1), true));
        assertThrows(IllegalArgumentException.class, () -> LoanSchedule.annuity(bd("1000"), 4, LoanSchedule.QUARTERLY, bd("10"), LocalDate.of(2026, 1, 1), true));
    }
}
