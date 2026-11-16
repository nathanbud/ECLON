package ph.coop.core.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Loan Interest and Penalty Computation Worksheet of the old system, worked out from the loan's ledger. No database access.
 *
 * <p>Rules, fitted to a real worksheet and checked to the centavo (loan 1221-00003212, as of 2026-04-24, {@code LoanWorksheetTest}):
 * <ul>
 *   <li>Interest accrues on the principal balance before the row, for the calendar days since the previous accrual row, over 365, rounded half up. A row accrues when it
 *       is a principal payment, a scheduled due date, or the cutoff. A payment that only pays interest (the ledger's event 23 alone) is credited to the accrued interest
 *       without a new accrual, so an advance payment shows as a negative balance.</li>
 *   <li>The accrued-interest balance is accruals less interest paid. Interest Due is that balance when positive; a negative balance is an advance payment.</li>
 *   <li>Should-be balance is the scheduled principal balance on the latest due date up to the row. Delayed principal is the actual balance above it; a balance below it is
 *       paid ahead of schedule.</li>
 *   <li>Penalty accrues on the delayed principal of the previous row at the loan's yearly penalty rate for the days since that row. <b>Not yet confirmed against the old
 *       system:</b> the sample loan was never late, so every penalty figure on it is zero. Grace days are not taken off, as the loan-penalty notes found for payments.</li>
 * </ul>
 */
public final class LoanWorksheet {

    private LoanWorksheet() {}

    /** One ledger posting group: everything booked on one voucher for the loan. Amounts are positive for a payment (credit) and, for principal, negative for a release (debit). */
    public record Posting(LocalDate date, String kind, String controlNo, String docNo, BigDecimal principalPaid, BigDecimal principalReleased, BigDecimal interestPaid, BigDecimal penaltyPaid) {}

    /** One scheduled due date and the principal balance the schedule expects after it. */
    public record ScheduleDate(LocalDate date, BigDecimal principalBalance) {}

    /** One printed row. Null where the old worksheet leaves the cell empty. */
    public record Row(LocalDate date, String kind, String controlNo, String docNo,
                      BigDecimal principalDebit, BigDecimal principalCredit, BigDecimal principalBalance,
                      Integer days, BigDecimal interestDebit, BigDecimal interestCredit, BigDecimal interestBalance,
                      BigDecimal shouldBeBalance, BigDecimal delayedPrincipal,
                      Integer penaltyDays, BigDecimal penaltyDebit, BigDecimal penaltyCredit, BigDecimal penaltyBalance, boolean cutoff) {}

    public record Totals(BigDecimal principalDebit, BigDecimal principalCredit, BigDecimal interestDebit, BigDecimal interestCredit, BigDecimal penaltyDebit, BigDecimal penaltyCredit) {}

    /** What is collectible (late) and what is over or advance paid, as of the cutoff. */
    public record Summary(BigDecimal penaltyDue, BigDecimal interestDue, BigDecimal principalDue, BigDecimal totalDue,
                          BigDecimal penaltyOver, BigDecimal interestOver, BigDecimal principalOver, BigDecimal totalOver, String status) {}

    public record Result(List<Row> rows, Totals totals, Summary summary) {}

    private static final BigDecimal DAYS_IN_YEAR_PERCENT = BigDecimal.valueOf(36500);
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    static BigDecimal accrue(BigDecimal balance, BigDecimal ratePercent, long days) {
        if (days <= 0 || balance.signum() <= 0 || ratePercent == null) return ZERO;
        return balance.multiply(ratePercent).multiply(BigDecimal.valueOf(days)).divide(DAYS_IN_YEAR_PERCENT, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    /**
     * @param released the release date (the first row's date)
     * @param postings every posting for the loan up to and including {@code asOf}
     * @param schedule the loan's scheduled due dates and balances (the release row, with the full amount, may be included)
     */
    public static Result compute(LocalDate released, BigDecimal interestRate, BigDecimal penaltyRate, List<Posting> postings, List<ScheduleDate> schedule, LocalDate asOf) {
        List<Posting> posts = postings.stream().filter((p) -> !p.date().isAfter(asOf)).sorted(Comparator.comparing(Posting::date)).toList();
        List<ScheduleDate> dues = schedule.stream().filter((d) -> d.date().isAfter(released) && !d.date().isAfter(asOf)).sorted(Comparator.comparing(ScheduleDate::date)).toList();

        List<Row> rows = new ArrayList<>();
        BigDecimal principal = ZERO, intBal = ZERO, penBal = ZERO;
        BigDecimal pDebit = ZERO, pCredit = ZERO, iDebit = ZERO, iCredit = ZERO, penDebit = ZERO, penCredit = ZERO;
        BigDecimal shouldBe = null;
        BigDecimal prevDelayed = ZERO;
        LocalDate prevAccrual = released;
        LocalDate prevPenalty = released;

        // Walk the dates in order: postings, scheduled due dates and the cutoff. Postings come before a due date that falls on the same day.
        int pi = 0, di = 0;
        while (pi < posts.size() || di < dues.size()) {
            boolean takePosting = pi < posts.size() && (di >= dues.size() || !posts.get(pi).date().isAfter(dues.get(di).date()));
            if (takePosting) {
                Posting p = posts.get(pi++);
                boolean release = nz(p.principalReleased()).signum() > 0;
                boolean accrues = nz(p.principalPaid()).signum() > 0;
                BigDecimal debit = nz(p.principalReleased()), credit = nz(p.principalPaid());
                Integer days = null;
                BigDecimal accrual = null;
                if (accrues && !release) {
                    long d = ChronoUnit.DAYS.between(prevAccrual, p.date());
                    days = (int) d;
                    accrual = accrue(principal, interestRate, d);
                    prevAccrual = p.date();
                }
                BigDecimal paid = nz(p.interestPaid());
                if (accrual != null) intBal = intBal.add(accrual);
                intBal = intBal.subtract(paid);
                iDebit = iDebit.add(nz(accrual));
                iCredit = iCredit.add(paid);

                Integer penDays = null;
                BigDecimal penAccrual = null;
                if (!release && penaltyRate != null) {
                    long d = ChronoUnit.DAYS.between(prevPenalty, p.date());
                    if (prevDelayed.signum() > 0 && d > 0) { penDays = (int) d; penAccrual = accrue(prevDelayed, penaltyRate, d); }
                    prevPenalty = p.date();
                }
                BigDecimal penPaid = nz(p.penaltyPaid());
                penBal = penBal.add(nz(penAccrual)).subtract(penPaid);
                penDebit = penDebit.add(nz(penAccrual));
                penCredit = penCredit.add(penPaid);

                principal = principal.add(debit).subtract(credit);
                pDebit = pDebit.add(debit);
                pCredit = pCredit.add(credit);
                BigDecimal delayed = shouldBe == null ? ZERO : principal.subtract(shouldBe).max(ZERO);
                prevDelayed = delayed;
                rows.add(new Row(p.date(), p.kind(), p.controlNo(), p.docNo(), debit.signum() > 0 ? debit : ZERO, credit, principal, days, accrual != null ? accrual : ZERO, paid, intBal,
                    null, delayed, penDays, penAccrual != null ? penAccrual : ZERO, penPaid, penBal, false));
            } else {
                ScheduleDate s = dues.get(di++);
                shouldBe = s.principalBalance();
                long d = ChronoUnit.DAYS.between(prevAccrual, s.date());
                BigDecimal accrual = accrue(principal, interestRate, d);
                prevAccrual = s.date();
                intBal = intBal.add(accrual);
                iDebit = iDebit.add(accrual);
                BigDecimal delayed = principal.subtract(shouldBe).max(ZERO);
                Integer penDays = null;
                BigDecimal penAccrual = ZERO;
                if (penaltyRate != null) {
                    long pd = ChronoUnit.DAYS.between(prevPenalty, s.date());
                    if (prevDelayed.signum() > 0 && pd > 0) { penDays = (int) pd; penAccrual = accrue(prevDelayed, penaltyRate, pd); }
                    prevPenalty = s.date();
                }
                penBal = penBal.add(penAccrual);
                penDebit = penDebit.add(penAccrual);
                prevDelayed = delayed;
                rows.add(new Row(s.date(), null, null, "00000000", ZERO, ZERO, principal, (int) d, accrual, ZERO, intBal, shouldBe, delayed, penDays, penAccrual, ZERO, penBal, false));
            }
        }

        // The cutoff row closes the period at the as-of date.
        long d = ChronoUnit.DAYS.between(prevAccrual, asOf);
        BigDecimal accrual = accrue(principal, interestRate, d);
        intBal = intBal.add(accrual);
        iDebit = iDebit.add(accrual);
        BigDecimal delayed = shouldBe == null ? ZERO : principal.subtract(shouldBe).max(ZERO);
        Integer penDays = null;
        BigDecimal penAccrual = ZERO;
        if (penaltyRate != null) {
            long pd = ChronoUnit.DAYS.between(prevPenalty, asOf);
            if (prevDelayed.signum() > 0 && pd > 0) { penDays = (int) pd; penAccrual = accrue(prevDelayed, penaltyRate, pd); }
        }
        penBal = penBal.add(penAccrual);
        penDebit = penDebit.add(penAccrual);
        rows.add(new Row(asOf, null, "CutOff", "CutOff", ZERO, ZERO, principal, (int) d, accrual, ZERO, intBal, shouldBe, delayed, penDays, penAccrual, ZERO, penBal, true));

        BigDecimal penaltyDue = penBal.max(ZERO), interestDue = intBal.max(ZERO), principalDue = delayed;
        BigDecimal penaltyOver = penBal.min(ZERO).negate(), interestOver = intBal.min(ZERO).negate();
        BigDecimal principalOver = shouldBe == null ? ZERO : shouldBe.subtract(principal).max(ZERO);
        boolean late = principalDue.signum() > 0 || penaltyDue.signum() > 0;
        boolean ahead = principalOver.signum() > 0 || interestOver.signum() > 0 || penaltyOver.signum() > 0;
        String status = late && !ahead ? "PAST DUE" : ahead && !late ? "ADVANCE PAYMENT" : late ? "PAST DUE" : "CURRENT";
        Summary summary = new Summary(penaltyDue, interestDue, principalDue, penaltyDue.add(interestDue).add(principalDue),
            penaltyOver, interestOver, principalOver, penaltyOver.add(interestOver).add(principalOver), status);
        return new Result(rows, new Totals(pDebit, pCredit, iDebit, iCredit, penDebit, penCredit), summary);
    }
}
