package ph.coop.core.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ph.coop.core.loan.LoanWorksheet.Posting;
import ph.coop.core.loan.LoanWorksheet.ScheduleDate;

/**
 * The Accrued Interest tab of the aging of receivables: the interest and penalty one account owes on the cut-off date, worked out the way the Loan Interest and Penalty
 * Computation Worksheet does ({@link LoanWorksheet}: interest accrues on the principal balance for the calendar days, over 365, at the account's yearly rate; interest paid
 * is taken off; what is left is interest due, or, below zero, interest paid ahead). No database here.
 *
 * <p>Two things this adds to what the printed worksheet reads from the ledger, both found in the old data:
 * <ul>
 *   <li>Interest collected in advance: about a third of the loans have their interest taken at release as <b>unearned interest</b> (event 22, a credit), later moved to
 *       income month by month (a 22 debit against a 24 credit, which is not a payment). Those 22 credits count as interest paid on their date, else such a loan would
 *       show its prepaid interest as owed. The 22 debits and the 24 entries are left out.</li>
 *   <li>Penalty waived (event 42, credits) settles penalty like a payment (event 41).</li>
 * </ul>
 * The penalty follows the worksheet's rule, which is <b>not yet confirmed</b> against the old system (the sample loan was never late), so the screen calls it an estimate.
 */
final class ReceivableAccrual {

    private ReceivableAccrual() {}

    static final int PRINCIPAL = 11, UNEARNED_INTEREST = 22, INTEREST_PAID = 23, PENALTY_PAID = 41, PENALTY_WAIVED = 42;

    /** One ledger row of the account (amounts as stored: debits positive, credits negative). */
    record Entry(LocalDate date, int trCode, String controlNo, String docNo, int event, BigDecimal amount) {}

    /** What the account owes besides principal on the cut-off date. */
    record Accrual(BigDecimal interestDue, BigDecimal interestAhead, BigDecimal penaltyDue) {
        static final Accrual NONE = new Accrual(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** The account's rows grouped per voucher (date, transaction code, control and document number), in the order given, as the worksheet takes them. */
    static List<Posting> postings(List<Entry> entries) {
        Map<String, Object[]> keys = new LinkedHashMap<>();
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        for (Entry e : entries) {
            int slot;
            BigDecimal amt = e.amount() == null ? BigDecimal.ZERO : e.amount();
            switch (e.event()) {
                case PRINCIPAL -> slot = 0;
                case INTEREST_PAID -> slot = 1;
                case UNEARNED_INTEREST -> {
                    if (amt.signum() >= 0) continue; // moving unearned interest to income is not a payment
                    slot = 1;
                }
                case PENALTY_PAID -> slot = 2;
                case PENALTY_WAIVED -> {
                    if (amt.signum() >= 0) continue;
                    slot = 2;
                }
                default -> { continue; }
            }
            String k = e.date() + "|" + e.trCode() + "|" + e.controlNo() + "|" + e.docNo();
            keys.putIfAbsent(k, new Object[] {e.date(), e.trCode(), e.controlNo(), e.docNo()});
            BigDecimal[] s = sums.computeIfAbsent(k, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            s[slot] = s[slot].add(amt);
        }
        List<Posting> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : sums.entrySet()) {
            Object[] k = keys.get(e.getKey());
            BigDecimal[] s = e.getValue();
            out.add(new Posting((LocalDate) k[0], "TR" + k[1], k[2] == null ? "" : (String) k[2], (String) k[3],
                s[0].signum() < 0 ? s[0].negate() : BigDecimal.ZERO, s[0].signum() > 0 ? s[0] : BigDecimal.ZERO, s[1].negate(), s[2].negate()));
        }
        return out;
    }

    /** Interest due, interest paid ahead and penalty due on {@code cutOff}; nothing when the release date is unknown. */
    static Accrual of(LocalDate released, BigDecimal interestRate, BigDecimal penaltyRate, List<Entry> entries, List<ScheduleDate> schedule, LocalDate cutOff, boolean withPenalty) {
        if (released == null || released.isAfter(cutOff)) return Accrual.NONE;
        LoanWorksheet.Summary s = LoanWorksheet.compute(released, interestRate == null ? BigDecimal.ZERO : interestRate, withPenalty ? penaltyRate : null, postings(entries), schedule, cutOff).summary();
        return new Accrual(s.interestDue(), s.interestOver(), withPenalty ? s.penaltyDue() : BigDecimal.ZERO);
    }
}
