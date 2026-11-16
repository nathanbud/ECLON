package ph.coop.core.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the repayment schedule of a diminishing-balance or an annuity loan the way the legacy system does (see loan-posting-map.md). No database
 * access, nothing is written. Diminishing balance is checked against 599 of 600 real schedules; the one miss is a cent of interest in a leap year.
 * Annuity (a level payment of principal and interest) is checked against about 2,430 of 2,700 real schedules, and every miss is a loan whose
 * schedule was changed afterwards (the 2020 payment holiday, a rate changed after the schedule was made); see the annuity notes below.
 * Only monthly, quarterly, half-yearly (annuity) and lump-sum payment modes are supported, the others are refused rather than guessed.
 */
public final class LoanSchedule {

    private LoanSchedule() {}

    /** Legacy payment mode ids (`paymentmode`) that are mapped. */
    public static final int MONTHLY = 4, QUARTERLY = 5, SEMIANNUAL = 6, LUMP_SUM = 9;

    /** Legacy amortization id (`amortype`): diminishing balance. */
    public static final int DIMINISHING = 1;

    /** Legacy amortization id (`amortype`): annuity. */
    public static final int ANNUITY = 3;

    /** One row of `amortsched`; the first row is the release date with nothing due. */
    public record Row(LocalDate dueDate, BigDecimal principalDue, BigDecimal interestDue, BigDecimal principalBalance) {}

    /**
     * @param termMonths the number of months of the loan (`LoanTERMS` with term unit months)
     * @param ratePercent the yearly rate, e.g. 10
     */
    public static List<Row> diminishing(BigDecimal amount, int termMonths, int paymentMode, BigDecimal ratePercent, LocalDate release) {
        if (amount.signum() <= 0 || termMonths <= 0) throw new IllegalArgumentException("amount and term must be positive");
        int step;
        int count;
        switch (paymentMode) {
            case MONTHLY -> { step = 1; count = termMonths; }
            case QUARTERLY -> {
                if (termMonths % 3 != 0) throw new IllegalArgumentException("a quarterly loan needs a term in whole quarters");
                step = 3; count = termMonths / 3;
            }
            case LUMP_SUM -> { step = termMonths; count = 1; }
            default -> throw new IllegalArgumentException("payment mode " + paymentMode + " is not mapped yet");
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(release, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), amount));
        BigDecimal balance = amount;
        BigDecimal instalment = amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        LocalDate previous = release;
        for (int i = 1; i <= count; i++) {
            LocalDate due = addMonths(release, (long) i * step);
            BigDecimal principal = i == count ? balance : instalment;
            BigDecimal interest = LoanRules.interest(balance, ratePercent, previous, due);
            balance = balance.subtract(principal);
            rows.add(new Row(due, principal, interest, balance));
            previous = due;
        }
        return rows;
    }

    /**
     * An annuity schedule: every payment is the same amount of principal plus interest, so the principal part grows as the interest part shrinks.
     * <p>
     * What the old schedules show (about 2,700 real loans): the payment is {@code P r / (1 - (1 + r)^-n)} rounded to the cent, with {@code r} the
     * rate for one payment period counted as 30 days of a 365-day year ({@code rate x 30 x months per payment / 36500}); the interest of each row is
     * the balance times {@code r} rounded to the cent, and the principal is the payment less that interest; the last row clears whatever balance is left.
     * Loan types whose day count is "30 days a month" (the Salary Loan) charge exactly that every row; the other kind counts the real days between
     * due dates for the interest, while the payment is still worked out from 30-day months.
     *
     * @param thirtyDayMonths true to charge 30 days a month (the loan type's {@code ND_FLAG} 1), false to count the real days (ND_FLAG 2)
     */
    public static List<Row> annuity(BigDecimal amount, int termMonths, int paymentMode, BigDecimal ratePercent, LocalDate release, boolean thirtyDayMonths) {
        if (amount.signum() <= 0 || termMonths <= 0) throw new IllegalArgumentException("amount and term must be positive");
        int step;
        int count;
        switch (paymentMode) {
            case MONTHLY -> { step = 1; count = termMonths; }
            case QUARTERLY, SEMIANNUAL -> {
                step = paymentMode == QUARTERLY ? 3 : 6;
                if (termMonths % step != 0) throw new IllegalArgumentException("this payment mode needs a term in whole payment periods");
                count = termMonths / step;
            }
            case LUMP_SUM -> { step = termMonths; count = 1; }
            default -> throw new IllegalArgumentException("payment mode " + paymentMode + " is not mapped yet");
        }
        MathContext mc = new MathContext(34, RoundingMode.HALF_EVEN);
        BigDecimal days = BigDecimal.valueOf(30L * step);
        BigDecimal r = ratePercent.multiply(days).divide(BigDecimal.valueOf(36500), mc);
        BigDecimal payment;
        if (r.signum() == 0) {
            payment = amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        } else {
            BigDecimal growth = BigDecimal.ONE.add(r).pow(count, mc);
            payment = amount.multiply(r).multiply(growth).divide(growth.subtract(BigDecimal.ONE), mc).setScale(2, RoundingMode.HALF_UP);
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(release, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), amount));
        BigDecimal balance = amount;
        LocalDate previous = release;
        for (int i = 1; i <= count; i++) {
            LocalDate due = addMonths(release, (long) i * step);
            BigDecimal interest = thirtyDayMonths
                ? balance.multiply(ratePercent).multiply(days).divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP)
                : LoanRules.interest(balance, ratePercent, previous, due);
            BigDecimal principal = i == count ? balance : payment.subtract(interest);
            balance = balance.subtract(principal);
            rows.add(new Row(due, principal, interest, balance));
            previous = due;
        }
        return rows;
    }

    /** The release day of the month is capped at 30; a month too short for it uses its last day. */
    static LocalDate addMonths(LocalDate release, long months) {
        YearMonth ym = YearMonth.from(release).plusMonths(months);
        return ym.atDay(Math.min(Math.min(release.getDayOfMonth(), 30), ym.lengthOfMonth()));
    }

    /** Total interest over the life of the loan. */
    public static BigDecimal totalInterest(List<Row> rows) {
        return rows.stream().map(Row::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
