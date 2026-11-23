package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BudgetRulesTest {

    @Test void aPeriodCoversTheMonthsOfTheYearItTouches() {
        assertEquals(3, BudgetRules.months(2026, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));
        assertEquals(12, BudgetRules.months(2026, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        assertEquals(1, BudgetRules.months(2026, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 20)));
        assertEquals(0, BudgetRules.months(2026, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)));
        assertThrows(IllegalArgumentException.class, () -> BudgetRules.months(2026, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1)));
    }

    @Test void theAnnualBudgetIsSpreadEvenlyToTheCentavo() {
        assertEquals(new BigDecimal("300000.00"), BudgetRules.forMonths(new BigDecimal("1200000"), 3));
        assertEquals(new BigDecimal("83.33"), BudgetRules.forMonths(new BigDecimal("1000"), 1));
    }

    @Test void revenueAboveAndExpenseBelowBudgetAreFavourable() {
        assertTrue(BudgetRules.favourable(4, new BigDecimal("120"), new BigDecimal("100")));
        assertFalse(BudgetRules.favourable(4, new BigDecimal("90"), new BigDecimal("100")));
        assertTrue(BudgetRules.favourable(5, new BigDecimal("90"), new BigDecimal("100")));
        assertFalse(BudgetRules.favourable(5, new BigDecimal("120"), new BigDecimal("100")));
    }

    @Test void anAmountIsZeroOrMoreWithTwoDecimals() {
        assertEquals(new BigDecimal("1500.50"), BudgetRules.amount(new BigDecimal("1500.5")));
        assertThrows(IllegalArgumentException.class, () -> BudgetRules.amount(new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> BudgetRules.amount(new BigDecimal("1.234")));
        assertThrows(IllegalArgumentException.class, () -> BudgetRules.amount(null));
    }
}
