package ph.coop.core.accounting.close;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.close.YearEndRules.Balance;
import ph.coop.core.accounting.close.YearEndRules.Department;
import ph.coop.core.accounting.close.YearEndRules.Facts;
import ph.coop.core.accounting.close.YearEndRules.Line;

class YearEndRulesTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    /** Department 2: income 1,000 (credit), expenses 600 (debit). Department 3: income 100, expenses 250 (a loss). A balance-sheet account too. */
    private static final List<Balance> YEAR = List.of(
            new Balance(2, "41100", d("-1000.00")),
            new Balance(2, "50105", d("600.00")),
            new Balance(2, "10001", d("5000.00")),
            new Balance(3, "41100", d("-100.00")),
            new Balance(3, "50105", d("250.00")),
            new Balance(3, "50200", d("0.00")));

    private static Facts ready() {
        return new Facts(2026, LocalDate.of(2027, 1, 20), false, 2025, 0, true, false);
    }

    @Test
    void eachIncomeAndExpenseAccountIsReversedAndTheDifferenceGoesToTheSurplusAccountPerDepartment() {
        List<Line> lines = YearEndRules.closingLines(YEAR, "30198");
        assertEquals(List.of(
                new Line(2, "41100", d("1000.00")),
                new Line(2, "50105", d("-600.00")),
                new Line(2, "30198", d("-400.00")),
                new Line(3, "41100", d("100.00")),
                new Line(3, "50105", d("-250.00")),
                new Line(3, "30198", d("150.00"))), lines);
        BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, total.signum(), "the closing entry balances");
    }

    @Test
    void balanceSheetAndZeroAccountsAreLeftAlone() {
        List<Line> lines = YearEndRules.closingLines(YEAR, "30198");
        assertTrue(lines.stream().noneMatch(l -> l.account().equals("10001") || l.account().equals("50200")));
        assertFalse(YearEndRules.closes("10110248"), "a loan account with an income type in the chart is not closed");
        assertTrue(YearEndRules.closes("41100") && YearEndRules.closes("50105"));
    }

    @Test
    void aDepartmentThatBreaksEvenGetsNoSurplusLine() {
        List<Line> lines = YearEndRules.closingLines(List.of(new Balance(4, "41100", d("-50")), new Balance(4, "50105", d("50"))), "30198");
        assertEquals(2, lines.size());
    }

    @Test
    void departmentFiguresAndTheYearsSurplus() {
        List<Department> depts = YearEndRules.departments(YEAR);
        assertEquals(new Department(2, d("1000.00"), d("600.00"), d("400.00"), 2), depts.get(0));
        assertEquals(new Department(3, d("100.00"), d("250.00"), d("-150.00"), 2), depts.get(1));
        assertEquals(0, d("250.00").compareTo(YearEndRules.surplus(YEAR.stream().filter(b -> YearEndRules.closes(b.account())).toList())));
    }

    @Test
    void aFinishedYearAfterTheLastClosedOneIsReady() {
        assertEquals(List.of(), YearEndRules.problems(ready()));
    }

    @Test
    void aYearCannotBeClosedBeforeItEnds() {
        Facts f = new Facts(2026, LocalDate.of(2026, 12, 31), false, 2025, 0, true, false);
        assertTrue(YearEndRules.problems(f).get(0).contains("has not ended"));
    }

    @Test
    void yearsAreClosedOnceAndInOrder() {
        assertTrue(YearEndRules.problems(new Facts(2025, LocalDate.of(2027, 1, 20), true, 2025, 0, true, false)).get(0).contains("already closed"));
        assertTrue(YearEndRules.problems(new Facts(2024, LocalDate.of(2027, 1, 20), false, 2025, 0, true, false)).get(0).contains("later year"));
        assertTrue(YearEndRules.problems(new Facts(2027, LocalDate.of(2028, 1, 20), false, 2025, 0, true, false)).get(0).contains("Close 2026 first"));
        assertEquals(List.of(), YearEndRules.problems(new Facts(2026, LocalDate.of(2027, 1, 20), false, null, 0, true, false)), "the first closing ever");
    }

    @Test
    void pendingJournalsAMissingSurplusAccountAndAnEmptyYearBlockTheClose() {
        assertTrue(YearEndRules.problems(new Facts(2026, LocalDate.of(2027, 1, 20), false, 2025, 2, true, false)).get(0).startsWith("2 journals dated 2026"));
        assertTrue(YearEndRules.problems(new Facts(2026, LocalDate.of(2027, 1, 20), false, 2025, 0, false, false)).get(0).contains("undivided net surplus"));
        assertTrue(YearEndRules.problems(new Facts(2026, LocalDate.of(2027, 1, 20), false, 2025, 0, true, true)).get(0).contains("nothing to close"));
    }

    @Test
    void theFingerprintChangesWhenTheLedgerDoes() {
        List<Line> a = YearEndRules.closingLines(YEAR, "30198");
        assertEquals(YearEndRules.fingerprint(a), YearEndRules.fingerprint(a.reversed()), "order does not matter");
        assertEquals(YearEndRules.fingerprint(a), YearEndRules.fingerprint(YearEndRules.closingLines(
                List.of(new Balance(2, "41100", d("-1000")), new Balance(2, "50105", d("600")), new Balance(3, "41100", d("-100")), new Balance(3, "50105", d("250"))), "30198")),
                "scale does not matter");
        List<Line> b = YearEndRules.closingLines(List.of(new Balance(2, "41100", d("-1000.01")), new Balance(2, "50105", d("600"))), "30198");
        assertNotEquals(YearEndRules.fingerprint(a), YearEndRules.fingerprint(b));
    }
}
