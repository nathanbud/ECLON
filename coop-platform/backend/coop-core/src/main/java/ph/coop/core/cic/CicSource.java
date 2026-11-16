package ph.coop.core.cic;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.cic.Csdf1.Loan;
import ph.coop.core.cic.Csdf1.Subject;

/**
 * What the ledger says at a cut-off date, as the CIC wants it: the loans with principal still outstanding on that day, and the members who owe them. Read only. The balance of a loan
 * is the sum of its principal entries (class 12, event 11, debits positive) up to the cut-off date, so a past date gives the books as they then stood, as far as later back-dated
 * entries allow.
 */
@Component
class CicSource {

    private static final int CHUNK = 400;

    private final JdbcTemplate jdbc;

    CicSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDate localDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    /** Loans with a principal balance above zero at the cut-off, oldest members first. {@code limit} (0 for all) keeps a sample for a preview. */
    List<Loan> loans(LocalDate cutOff, int limit) {
        String sql = """
                SELECT l.LoanBR_CODE, l.ClientIDLoan, l.LoanSLT_CODE, l.LoanREF_NO, t.SLTypeCIC_CONTYPID, l.LoanPAMT, l.LoanTR_DATE, l.LoanMAT_DATE, pay.last_payment, l.LoanPPMT_MODE, b.balance
                FROM loan l
                JOIN (SELECT SL_BRCODE AS br, SL_CLIENTID AS cl, SLT_CODE AS t, REF_NO AS r, SUM(AMT) AS balance
                      FROM sldtl WHERE SLC_CODE = 12 AND SLE_CODE = 11 AND TR_DATE <= ?
                      GROUP BY SL_BRCODE, SL_CLIENTID, SLT_CODE, REF_NO HAVING SUM(AMT) > 0.004) b
                  ON b.br = l.LoanBR_CODE AND b.cl = l.ClientIDLoan AND b.t = l.LoanSLT_CODE AND b.r = l.LoanREF_NO
                LEFT JOIN sltype t ON t.SLTypeBR_CODE = l.LoanBR_CODE AND t.SLTypeSLC_CODE = 12 AND t.SLTypeSLT_CODE = l.LoanSLT_CODE
                LEFT JOIN (SELECT SL_BRCODE AS br, SL_CLIENTID AS cl, SLT_CODE AS t, REF_NO AS r, MAX(TR_DATE) AS last_payment
                           FROM sldtl WHERE SLC_CODE = 12 AND SLE_CODE IN (11, 23) AND AMT < 0 AND TR_DATE <= ?
                           GROUP BY SL_BRCODE, SL_CLIENTID, SLT_CODE, REF_NO) pay
                  ON pay.br = l.LoanBR_CODE AND pay.cl = l.ClientIDLoan AND pay.t = l.LoanSLT_CODE AND pay.r = l.LoanREF_NO
                WHERE l.LoanSLC_CODE = 12 AND l.LoanTR_DATE <= ?
                ORDER BY l.LoanBR_CODE, l.ClientIDLoan, l.LoanSLT_CODE, l.LoanREF_NO
                """ + (limit > 0 ? " LIMIT " + limit : "");
        return jdbc.query(sql, (rs, i) -> new Loan(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getObject(5) == null ? null : rs.getInt(5), rs.getBigDecimal(6),
            localDate(rs.getDate(7)), localDate(rs.getDate(8)), localDate(rs.getDate(9)), rs.getInt(10), rs.getBigDecimal(11)), Date.valueOf(cutOff), Date.valueOf(cutOff), Date.valueOf(cutOff));
    }

    /** The members behind these loans, in client order. */
    List<Subject> subjects(List<Loan> loans) {
        Map<Integer, Set<Integer>> byBranch = new LinkedHashMap<>();
        for (Loan l : loans) byBranch.computeIfAbsent(l.branch(), (b) -> new TreeSet<>()).add(l.clientId());
        List<Subject> out = new ArrayList<>();
        for (var e : byBranch.entrySet()) {
            List<Integer> ids = new ArrayList<>(e.getValue());
            for (int from = 0; from < ids.size(); from += CHUNK) {
                List<Integer> part = ids.subList(from, Math.min(ids.size(), from + CHUNK));
                String in = "?,".repeat(part.size() - 1) + "?";
                Map<Integer, Integer> benef = new HashMap<>();
                List<Object> args = new ArrayList<>();
                args.add(e.getKey());
                args.addAll(part);
                jdbc.query("SELECT ClientIDBenef, COUNT(*) FROM benef WHERE BenefBR_CODE = ? AND ClientIDBenef IN (" + in + ") GROUP BY ClientIDBenef", (rs) -> {
                    benef.put(rs.getInt(1), rs.getInt(2));
                }, args.toArray());
                out.addAll(jdbc.query("""
                        SELECT ClientIDBrCode, ClientID, LName, FName, MName, SName, Gender, CivilStatus, DateOfBirth, TINNum, SSSGSISNum, GSISNum, PhilHealthNum, PagibigNum, CellNum,
                               ResAddStreet, ResAddBarangay, ResAddCity, ResAddProvince, ProvAddStreet, ProvAddBarangay, ProvAddCity, ProvAddProvince, BusName, SpFName, SpLName, SpMName
                        FROM client WHERE ClientIDBrCode = ? AND ClientID IN (""" + in + ") ORDER BY ClientID",
                    (rs, i) -> new Subject(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), localDate(rs.getDate(9)),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17), rs.getString(18),
                        rs.getString(19), rs.getString(20), rs.getString(21), rs.getString(22), rs.getString(23), rs.getString(24), rs.getString(25), rs.getString(26), rs.getString(27),
                        benef.getOrDefault(rs.getInt(2), 0)), args.toArray()));
            }
        }
        return out;
    }

    /** The loan type's name, for messages. */
    Map<Integer, String> loanTypeNames() {
        Map<Integer, String> m = new HashMap<>();
        jdbc.query("SELECT SLTypeSLT_CODE, SLTypeM_DESC FROM sltype WHERE SLTypeSLC_CODE = 12 AND SLTypeBR_CODE = 1", (rs) -> {
            m.put(rs.getInt(1), rs.getString(2));
        });
        return m;
    }

    static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
