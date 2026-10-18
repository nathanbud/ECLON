package ph.coop.core.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.loan.LoanRules.Arrears;
import ph.coop.core.loan.LoanRules.Due;

/**
 * What a payment into a loan needs to know, from the legacy tables: the balance, the rate, the date interest was last paid up to, and how
 * late the loan is. Read-only; the payment itself is written by the front office.
 */
@Component
public class LoanBook {

    private static final int CLASS_LOANS = 12;

    /** status: 11 current, 12 past due, 13 in litigation, 14 restructured. graceDays is the loan's penalty grace period. */
    public record Terms(int status, String type, BigDecimal amount, BigDecimal outstanding, BigDecimal ratePercent, LocalDate released, LocalDate interestPaidTo, int graceDays) {}

    public record Quote(Terms terms, LocalDate asOf, int days, BigDecimal interestDue, BigDecimal payoff, BigDecimal pastDuePrincipal, long daysPastDue) {}

    private final JdbcTemplate jdbc;

    LoanBook(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Terms> terms(int branch, int clientId, int typeCode, int refNo) {
        List<Terms> rows = jdbc.query("""
                SELECT l.LoanSTATUS, t.SLTypeM_DESC, l.LoanPAMT, l.LoanINT_RATE, l.LoanTR_DATE, COALESCE(l.LoanPENGP, 0),
                       (SELECT COALESCE(SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = l.LoanBR_CODE AND d.SL_CLIENTID = l.ClientIDLoan AND d.SLC_CODE = l.LoanSLC_CODE
                          AND d.SLT_CODE = l.LoanSLT_CODE AND d.REF_NO = l.LoanREF_NO AND d.SLE_CODE = 11),
                       (SELECT MAX(d.TR_DATE) FROM sldtl d WHERE d.SL_BRCODE = l.LoanBR_CODE AND d.SL_CLIENTID = l.ClientIDLoan AND d.SLC_CODE = l.LoanSLC_CODE
                          AND d.SLT_CODE = l.LoanSLT_CODE AND d.REF_NO = l.LoanREF_NO AND d.SLE_CODE = 23 AND d.AMT < 0)
                FROM loan l LEFT JOIN sltype t ON t.SLTypeBR_CODE = l.LoanBR_CODE AND t.SLTypeSLC_CODE = l.LoanSLC_CODE AND t.SLTypeSLT_CODE = l.LoanSLT_CODE
                WHERE l.LoanBR_CODE = ? AND l.ClientIDLoan = ? AND l.LoanSLC_CODE = ? AND l.LoanSLT_CODE = ? AND l.LoanREF_NO = ?
                """, (rs, i) -> {
                    LocalDate released = rs.getDate(5).toLocalDate();
                    java.sql.Date last = rs.getDate(8);
                    LocalDate paidTo = last == null || last.toLocalDate().isBefore(released) ? released : last.toLocalDate();
                    return new Terms(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(7), rs.getBigDecimal(4), released, paidTo, rs.getInt(6));
                }, branch, clientId, CLASS_LOANS, typeCode, refNo);
        return rows.stream().findFirst();
    }

    /** The interest due on {@code asOf} and how late the loan is. */
    public Quote quote(int branch, int clientId, int typeCode, int refNo, Terms t, LocalDate asOf) {
        BigDecimal interest = t.outstanding().signum() > 0 ? LoanRules.interest(t.outstanding(), t.ratePercent() == null ? BigDecimal.ZERO : t.ratePercent(), t.interestPaidTo(), asOf) : BigDecimal.ZERO;
        List<Due> dues = new ArrayList<>();
        jdbc.query("SELECT DUEDATE, COALESCE(PRINDUE, 0), COALESCE(INTDUE, 0) FROM amortsched WHERE BR_CODE = ? AND CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? ORDER BY DUEDATE",
            (rs) -> { dues.add(new Due(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getBigDecimal(3))); }, branch, clientId, CLASS_LOANS, typeCode, refNo);
        BigDecimal paid = t.amount().subtract(t.outstanding()).max(BigDecimal.ZERO);
        Arrears a = LoanRules.arrears(dues, paid, asOf);
        int days = (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(t.interestPaidTo(), asOf));
        return new Quote(t, asOf, days, interest, t.outstanding().add(interest), a.pastDuePrincipal(), a.daysPastDue());
    }
}
