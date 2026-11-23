package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Budget versus actual: the annual budget of an income or expense account is spread evenly over the twelve months, so the budget for a period is the annual
 * amount times the months of the year it covers over twelve. The variance is actual less budget; it is favourable when revenue comes in above budget or an
 * expense stays below it. Pure logic.
 */
final class BudgetRules {

    private BudgetRules() {}

    /** The months of a budget year a period covers, counting a month once any of it is inside (from January 1 to the period's last day's month). */
    static int months(int year, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("The end date is before the start date.");
        LocalDate start = from.getYear() < year ? LocalDate.of(year, 1, 1) : from;
        LocalDate end = to.getYear() > year ? LocalDate.of(year, 12, 31) : to;
        if (start.getYear() != year || end.getYear() != year || end.isBefore(start)) return 0;
        return end.getMonthValue() - start.getMonthValue() + 1;
    }

    /** The budget for so many months of the year, to the centavo. */
    static BigDecimal forMonths(BigDecimal annual, int months) {
        return annual.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    /** Whether actual against budget is good news: revenue (type 4) above budget, an expense (type 5) at or below it. */
    static boolean favourable(int type, BigDecimal actual, BigDecimal budget) {
        int c = actual.compareTo(budget);
        return type == 4 ? c >= 0 : c <= 0;
    }

    /** A budget amount as typed: zero or more, two decimals at most. */
    static BigDecimal amount(BigDecimal a) {
        if (a == null) throw new IllegalArgumentException("Give an amount for every account, zero for none.");
        if (a.signum() < 0) throw new IllegalArgumentException("A budget is zero or more.");
        if (a.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("A budget has at most two decimals.");
        if (a.compareTo(new BigDecimal("999999999999999.99")) > 0) throw new IllegalArgumentException("That budget is too large.");
        return a.setScale(2, RoundingMode.UNNECESSARY);
    }
}
