package ph.coop.core.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * How far a loan is paid against its repayment schedule. No database access here. A loan's principal paid is what was released minus
 * what is still outstanding; the schedule says how much principal should have been paid by each due date. Interest is not tracked
 * per instalment here (the legacy ledger books it as it is paid), so arrears are measured in principal.
 */
public final class LoanRules {

    private LoanRules() {}

    /** What a payment is split into: interest first, the rest to principal. */
    public record Split(BigDecimal interest, BigDecimal principal) {}

    /**
     * Interest on the outstanding principal for the actual days since the last interest payment (or the release), on a 365-day year,
     * rounded to the cent. Checked against real payments: 44,000 at 10% for 28 days is 337.53; 100,000 at 12% for 136 days is 4,471.23.
     */
    public static BigDecimal interest(BigDecimal outstanding, BigDecimal ratePercent, LocalDate from, LocalDate to) {
        long days = Math.max(0, ChronoUnit.DAYS.between(from, to));
        return outstanding.multiply(ratePercent).multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(36500), 2, java.math.RoundingMode.HALF_UP);
    }

    /** Interest is paid first; whatever is left reduces the principal. */
    public static Split split(BigDecimal amount, BigDecimal interestDue) {
        BigDecimal interest = amount.min(interestDue);
        return new Split(interest, amount.subtract(interest));
    }

    enum State { PAID, PARTIAL, PAST_DUE, UPCOMING }

    record Due(LocalDate date, BigDecimal principal, BigDecimal interest) {}

    record Row(LocalDate date, BigDecimal principalDue, BigDecimal interestDue, BigDecimal principalBalance, State state, BigDecimal paidOfThis) {}

    record Arrears(BigDecimal pastDuePrincipal, long daysPastDue, LocalDate oldestUnpaidDate) {}

    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

    /** Marks each instalment paid, partly paid, past due or upcoming, given the principal paid so far and the date we are looking from. */
    static List<Row> schedule(List<Due> dues, List<BigDecimal> balances, BigDecimal principalPaid, LocalDate asOf) {
        List<Row> rows = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int i = 0; i < dues.size(); i++) {
            Due d = dues.get(i);
            BigDecimal before = cumulative;
            cumulative = cumulative.add(d.principal());
            State s;
            BigDecimal paidOfThis;
            if (d.principal().signum() == 0) {
                s = d.date().isAfter(asOf) ? State.UPCOMING : State.PAID;
                paidOfThis = BigDecimal.ZERO;
            } else if (cumulative.subtract(principalPaid).compareTo(HALF_CENT) <= 0) {
                s = State.PAID;
                paidOfThis = d.principal();
            } else if (principalPaid.compareTo(before) > 0) {
                s = d.date().isAfter(asOf) ? State.PARTIAL : State.PAST_DUE;
                paidOfThis = principalPaid.subtract(before);
            } else {
                s = d.date().isAfter(asOf) ? State.UPCOMING : State.PAST_DUE;
                paidOfThis = BigDecimal.ZERO;
            }
            rows.add(new Row(d.date(), d.principal(), d.interest(), balances.get(i), s, paidOfThis));
        }
        return rows;
    }

    /** Principal that should have been paid by {@code asOf} but is not, and how long the oldest unpaid instalment has been late. */
    static Arrears arrears(List<Due> dues, BigDecimal principalPaid, LocalDate asOf) {
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal dueByNow = BigDecimal.ZERO;
        LocalDate oldest = null;
        for (Due d : dues) {
            cumulative = cumulative.add(d.principal());
            if (!d.date().isAfter(asOf)) {
                dueByNow = cumulative;
                if (oldest == null && d.principal().signum() > 0 && cumulative.subtract(principalPaid).compareTo(HALF_CENT) > 0) oldest = d.date();
            }
        }
        BigDecimal past = dueByNow.subtract(principalPaid);
        if (past.compareTo(HALF_CENT) <= 0) return new Arrears(BigDecimal.ZERO, 0, null);
        return new Arrears(past.setScale(2, java.math.RoundingMode.HALF_UP), oldest == null ? 0 : ChronoUnit.DAYS.between(oldest, asOf), oldest);
    }

    /** The first instalment after {@code asOf} that is not yet paid, or null. */
    static Due nextDue(List<Due> dues, BigDecimal principalPaid, LocalDate asOf) {
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Due d : dues) {
            cumulative = cumulative.add(d.principal());
            if (d.date().isAfter(asOf) && d.principal().signum() > 0 && cumulative.subtract(principalPaid).compareTo(HALF_CENT) > 0) return d;
        }
        return null;
    }
}
