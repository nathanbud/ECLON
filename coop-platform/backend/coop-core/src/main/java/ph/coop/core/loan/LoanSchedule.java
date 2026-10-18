package ph.coop.core.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the repayment schedule of a diminishing-balance loan the way the legacy system does (see loan-posting-map.md). No database
 * access, nothing is written. Checked against 599 of 600 real schedules; the one miss is a cent of interest in a leap year.
 * Only monthly, quarterly and lump-sum payment modes are supported, the others are refused rather than guessed.
 */
public final class LoanSchedule {

    private LoanSchedule() {}

    /** Legacy payment mode ids (`paymentmode`) that are mapped. */
    public static final int MONTHLY = 4, QUARTERLY = 5, LUMP_SUM = 9;

    /** Legacy amortization id (`amortype`): diminishing balance. */
    public static final int DIMINISHING = 1;

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
