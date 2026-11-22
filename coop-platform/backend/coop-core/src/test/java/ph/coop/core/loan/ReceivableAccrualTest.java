package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.loan.LoanWorksheet.Posting;
import ph.coop.core.loan.ReceivableAccrual.Accrual;
import ph.coop.core.loan.ReceivableAccrual.Entry;

/** The Accrued Interest tab's rules without a database: which ledger rows count as interest and penalty paid, and what is owed on the cut-off date. */
class ReceivableAccrualTest {

    static final LocalDate RELEASED = LocalDate.of(2025, 5, 24);

    static Entry e(String date, int tr, String ctl, int event, String amount) {
        return new Entry(LocalDate.parse(date), tr, ctl, "D" + ctl, event, new BigDecimal(amount));
    }

    static BigDecimal m(String s) {
        return new BigDecimal(s);
    }

    @Test
    void rowsGroupPerVoucherAndOnlyPaymentsCount() {
        // the real pattern of loan 3-48-8725: interest for six months taken as unearned interest at release, then moved to income month by month
        List<Posting> p = ReceivableAccrual.postings(List.of(
            e("2025-05-24", 21, "1", 11, "60000.00"), e("2025-05-24", 21, "1", 31, "-600.00"), e("2025-05-24", 21, "1", 22, "-3024.66"),
            e("2025-05-31", 51, "2", 22, "115.07"), e("2025-05-31", 51, "2", 24, "-115.07"),
            e("2025-11-24", 10, "3", 11, "-10000.00"), e("2025-11-24", 10, "3", 41, "-50.00"), e("2025-11-24", 10, "3", 42, "-20.00"), e("2025-11-24", 10, "3", 42, "5.00")));
        assertEquals(2, p.size(), "the month-end move to income is not a payment, the service fee is not interest");
        assertEquals(0, m("60000.00").compareTo(p.get(0).principalReleased()));
        assertEquals(0, m("3024.66").compareTo(p.get(0).interestPaid()), "interest taken in advance counts as paid");
        assertEquals(0, m("10000.00").compareTo(p.get(1).principalPaid()));
        assertEquals(0, m("70.00").compareTo(p.get(1).penaltyPaid()), "penalty paid and waived; a waiver reversal (debit) is left out");
    }

    @Test
    void interestTakenInAdvanceIsNotOwedAgain() {
        // 60,000 at 10% for the 184 days to 2025-11-24 is 3,024.66, exactly what was taken at release
        List<Entry> prepaid = List.of(e("2025-05-24", 21, "1", 11, "60000.00"), e("2025-05-24", 21, "1", 22, "-3024.66"));
        Accrual a = ReceivableAccrual.of(RELEASED, m("10"), null, prepaid, List.of(), LocalDate.of(2025, 11, 24), false);
        assertEquals(0, a.interestDue().signum());
        assertEquals(0, a.interestAhead().signum());
        // without counting it, the whole six months would show as owed
        Accrual counted = ReceivableAccrual.of(RELEASED, m("10"), null, prepaid.subList(0, 1), List.of(), LocalDate.of(2025, 11, 24), false);
        assertEquals(0, m("3024.66").compareTo(counted.interestDue()));
        // a month before, part of it is still paid ahead
        Accrual early = ReceivableAccrual.of(RELEASED, m("10"), null, prepaid, List.of(), LocalDate.of(2025, 10, 24), false);
        assertEquals(0, early.interestDue().signum());
        assertEquals(0, m("509.59").compareTo(early.interestAhead()), "31 days of 60,000 at 10%");
    }

    @Test
    void interestPaidIsTakenOffAndPenaltyOnlyWhenAsked() {
        List<Entry> rows = List.of(e("2025-05-24", 21, "1", 11, "36500.00"), e("2025-06-23", 10, "2", 23, "-100.00"));
        // 36,500 at 10% is 10.00 a day: 61 days to 2025-07-24 is 610.00, less 100.00 paid
        Accrual a = ReceivableAccrual.of(RELEASED, m("10"), m("36"), rows, List.of(), LocalDate.of(2025, 7, 24), false);
        assertEquals(0, m("510.00").compareTo(a.interestDue()));
        assertEquals(0, a.penaltyDue().signum(), "penalty left out");
        assertEquals(Accrual.NONE, ReceivableAccrual.of(null, m("10"), null, rows, List.of(), LocalDate.of(2025, 7, 24), true), "no release date, nothing worked out");
        assertEquals(Accrual.NONE, ReceivableAccrual.of(RELEASED, m("10"), null, rows, List.of(), LocalDate.of(2025, 5, 1), true), "released after the cut-off");
    }
}
