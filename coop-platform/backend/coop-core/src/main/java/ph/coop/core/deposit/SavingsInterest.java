package ph.coop.core.deposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The quarterly "Interest on Deposits (Computer Generated) - A.D.B." of the old system (documentation/savings-interest-map.md): the end-of-day balance of
 * every day of the period is added up, the average daily balance (A.D.B.) is that sum over the number of days, and the interest is
 * A.D.B. x rate x days / 365, rounded half up to the cent. An account whose A.D.B. is under the type's minimum earns nothing. No database access;
 * nothing here posts.
 */
public final class SavingsInterest {

    private SavingsInterest() {}

    /** A change to the balance on a day: deposits positive, withdrawals negative (the ledger stores credits as negative, so flip them first). */
    public record Movement(LocalDate date, BigDecimal amount) {}

    /** The sum of the end-of-day balances from {@code from} to {@code to}, both included, starting from {@code opening} (the balance before {@code from}). */
    public static BigDecimal balanceDays(BigDecimal opening, List<Movement> movements, LocalDate from, LocalDate to) {
        BigDecimal balance = opening;
        for (Movement m : movements) if (m.date().isBefore(from)) balance = balance.add(m.amount());
        BigDecimal sum = BigDecimal.ZERO;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            for (Movement m : movements) if (m.date().equals(day)) balance = balance.add(m.amount());
            sum = sum.add(balance);
        }
        return sum;
    }

    public static int days(LocalDate from, LocalDate to) {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    public static BigDecimal averageDailyBalance(BigDecimal balanceDays, int days) {
        return balanceDays.divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
    }

    /** The interest for the period; zero when the A.D.B. is below {@code minimumAdb} or the rate is zero. */
    public static BigDecimal interest(BigDecimal balanceDays, int days, BigDecimal ratePercent, BigDecimal minimumAdb) {
        if (days <= 0 || ratePercent == null || ratePercent.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        BigDecimal adb = averageDailyBalance(balanceDays, days);
        if (minimumAdb != null && adb.compareTo(minimumAdb) < 0) return BigDecimal.ZERO.setScale(2);
        return balanceDays.multiply(ratePercent).divide(BigDecimal.valueOf(36_500), 2, RoundingMode.HALF_UP);
    }
}
