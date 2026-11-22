package ph.coop.core.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ages every open loan of a branch on a date, in bulk, with the same rules the payment window quotes one loan by ({@link LoanBook#quote}): what is
 * still owed, the principal past due by the schedule and since when, the interest due, and the penalty the formula gives where it applies (penalty
 * mode 4; null elsewhere, where the cashier types it). Nothing is posted: the old system charges interest and penalties when a payment is taken, so
 * this is a record of the day for the reports and the collectors. Three reads (loans, their ledger sums, their schedules) whatever the number of loans.
 */
@Component
public class LoanAging {

    private static final int CLASS_LOANS = 12;
    /** The loan statuses that can still owe money: current, past due, in litigation (13, written off, is left out). */
    static final List<Integer> OPEN = List.of(11, 12, 14);

    public record Aged(int clientId, int typeCode, int refNo, BigDecimal released, BigDecimal outstanding, BigDecimal pastDuePrincipal, long daysPastDue,
                       LocalDate pastDueSince, BigDecimal interestDue, BigDecimal penalty, String bucket) {}

    private record Key(int client, int type, int ref) {}

    private record LoanRow(Key key, BigDecimal amount, BigDecimal rate, LocalDate released, int graceDays, Integer penaltyMode, BigDecimal penaltyRate) {}

    private final JdbcTemplate jdbc;

    LoanAging(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The aging bucket of a number of days past due, as the portfolio-at-risk reports group them. */
    public static String bucket(long daysPastDue) {
        if (daysPastDue <= 0) return "CURRENT";
        if (daysPastDue <= 30) return "1-30";
        if (daysPastDue <= 60) return "31-60";
        if (daysPastDue <= 90) return "61-90";
        if (daysPastDue <= 180) return "91-180";
        if (daysPastDue <= 365) return "181-365";
        return "OVER-365";
    }

    public List<Aged> age(int branch, LocalDate asOf) {
        Map<Key, LoanRow> loans = new HashMap<>();
        jdbc.query("""
                SELECT ClientIDLoan, LoanSLT_CODE, LoanREF_NO, COALESCE(LoanPAMT, 0), LoanINT_RATE, LoanTR_DATE, COALESCE(LoanPENGP, 0), LoanPEN_MODE, LoanPEN_RATE
                FROM loan WHERE LoanBR_CODE = ? AND LoanSLC_CODE = ? AND LoanSTATUS IN (11, 12, 14) AND LoanTR_DATE <= ?
                """, (rs) -> {
            if (rs.getDate(6) == null) return;
            Key k = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            Object mode = rs.getObject(8);
            loans.put(k, new LoanRow(k, rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getDate(6).toLocalDate(), rs.getInt(7), mode == null ? null : ((Number) mode).intValue(), rs.getBigDecimal(9)));
        }, branch, CLASS_LOANS, java.sql.Date.valueOf(asOf));

        // principal owed (event 11: release a debit, payments credits) and interest paid (event 23 credits) and when interest was last paid
        Map<Key, BigDecimal> outstanding = new HashMap<>(), interestPaid = new HashMap<>();
        Map<Key, LocalDate> interestTo = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, SLT_CODE, REF_NO, SLE_CODE, COALESCE(SUM(AMT), 0), COALESCE(SUM(CASE WHEN AMT < 0 THEN AMT ELSE 0 END), 0), MAX(CASE WHEN AMT < 0 THEN TR_DATE END)
                FROM sldtl WHERE SL_BRCODE = ? AND SLC_CODE = ? AND SLE_CODE IN (11, 23) AND TR_DATE <= ?
                GROUP BY SL_CLIENTID, SLT_CODE, REF_NO, SLE_CODE
                """, (rs) -> {
            Key k = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            if (!loans.containsKey(k)) return;
            if (rs.getInt(4) == 11) outstanding.put(k, rs.getBigDecimal(5));
            else {
                interestPaid.put(k, rs.getBigDecimal(6).negate());
                java.sql.Date d = rs.getDate(7);
                if (d != null) interestTo.put(k, d.toLocalDate());
            }
        }, branch, CLASS_LOANS, java.sql.Date.valueOf(asOf));
        loans.keySet().removeIf((k) -> outstanding.getOrDefault(k, BigDecimal.ZERO).compareTo(new BigDecimal("0.005")) < 0);

        Map<Key, List<LoanRules.Due>> dues = new HashMap<>();
        jdbc.query("""
                SELECT CLIENTID, SLT_CODE, REF_NO, DUEDATE, COALESCE(PRINDUE, 0), COALESCE(INTDUE, 0) FROM amortsched
                WHERE BR_CODE = ? AND SLC_CODE = ? ORDER BY CLIENTID, SLT_CODE, REF_NO, DUEDATE
                """, (rs) -> {
            Key k = new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            if (!loans.containsKey(k) || rs.getDate(4) == null) return;
            dues.computeIfAbsent(k, (x) -> new ArrayList<>()).add(new LoanRules.Due(rs.getDate(4).toLocalDate(), rs.getBigDecimal(5), rs.getBigDecimal(6)));
        }, branch, CLASS_LOANS);

        List<Aged> out = new ArrayList<>();
        for (LoanRow l : loans.values()) {
            Key k = l.key();
            BigDecimal owed = outstanding.get(k);
            List<LoanRules.Due> d = dues.getOrDefault(k, List.of());
            BigDecimal paid = l.amount().subtract(owed).max(BigDecimal.ZERO);
            LoanRules.Arrears a = LoanRules.arrears(d, paid, asOf);
            LocalDate last = interestTo.get(k);
            LocalDate paidTo = last == null || last.isBefore(l.released()) ? l.released() : last;
            BigDecimal interest = LoanRules.interest(owed, l.rate() == null ? BigDecimal.ZERO : l.rate(), paidTo, asOf);
            BigDecimal penalty = null;
            if (l.penaltyMode() != null && l.penaltyMode() == 4 && l.penaltyRate() != null) {
                penalty = a.pastDuePrincipal().signum() <= 0 || a.daysPastDue() <= l.graceDays() ? BigDecimal.ZERO
                    : LoanRules.penalty(d, paid.add(interestPaid.getOrDefault(k, BigDecimal.ZERO)), l.penaltyRate(), asOf);
            }
            long late = a.pastDuePrincipal().signum() > 0 ? a.daysPastDue() : 0;
            out.add(new Aged(k.client(), k.type(), k.ref(), l.amount(), owed, a.pastDuePrincipal(), late, late > 0 ? a.oldestUnpaidDate() : null, interest, penalty, bucket(late)));
        }
        out.sort(java.util.Comparator.comparingLong(Aged::daysPastDue).reversed().thenComparing(Aged::clientId).thenComparing(Aged::refNo));
        return out;
    }
}
