package ph.coop.core.deposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * What the app can say about a time deposit from the old data (no database access). Mapped in documentation/time-deposit-map.md.
 * The balance of a certificate is the sum of its principal ledger rows (event 11, a credit is stored negative); its master row in
 * {@code td} is never closed by the old system when the money is paid out, so status comes from the ledger.
 */
public final class TimeDepositRules {

    /** The flat amount the old system keeps back from the interest it rolls into the new certificate (seen on 1,181 of 1,629 real rollovers). */
    public static final BigDecimal ROLLOVER_FEE = new BigDecimal("10.00");

    private TimeDepositRules() {}

    public enum Status { ACTIVE, MATURED, CLOSED, NO_LEDGER }

    /**
     * ACTIVE: money is in and the maturity date has not come. MATURED: money is in and the date has passed (waiting to be rolled over or paid out).
     * CLOSED: the old master says closed, or the ledger is empty again. NO_LEDGER: the certificate exists but has no ledger rows at all.
     */
    public static Status status(int masterStatus, boolean hasLedger, BigDecimal balance, LocalDate maturity, LocalDate asOf) {
        if (masterStatus == 17) return Status.CLOSED;
        if (!hasLedger) return Status.NO_LEDGER;
        if (balance.compareTo(new BigDecimal("0.005")) < 0) return Status.CLOSED;
        return maturity != null && maturity.isBefore(asOf) ? Status.MATURED : Status.ACTIVE;
    }

    /** Interest for the whole term on a 30-day-month year: amount x rate% x term days / 360, rounded half up. */
    public static BigDecimal termInterest(BigDecimal amount, BigDecimal ratePercent, int termDays) {
        if (amount == null || ratePercent == null || termDays <= 0) return BigDecimal.ZERO;
        return amount.multiply(ratePercent).multiply(BigDecimal.valueOf(termDays)).divide(BigDecimal.valueOf(36000), 2, RoundingMode.HALF_UP);
    }

    /** What a rollover credits as interest into the new certificate: the term interest less the flat fee, never below zero. */
    public static BigDecimal rolledInterest(BigDecimal amount, BigDecimal ratePercent, int termDays) {
        return termInterest(amount, ratePercent, termDays).subtract(ROLLOVER_FEE).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** The name of a ledger event on a time deposit. 71 is the interest accrued at year end; 72 is seen rarely and its meaning is not known. */
    public static String eventName(int event) {
        return switch (event) {
            case 11 -> "Principal";
            case 71 -> "Accrued interest";
            case 72 -> "Interest (event 72)";
            default -> "Event " + event;
        };
    }
}
