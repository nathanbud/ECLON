package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.businessday.BusinessDayRules.Check;
import ph.coop.core.frontoffice.HandoverRules.Drawer;

/**
 * What End Day checks before closing a business day, read only. Must pass (End Day needs a reason otherwise): every teller who handled cash that day
 * counted and closed the drawer, no hand-off of cash is waiting, and every voucher posted that day balances (its lines add up to zero). For review:
 * vouchers dated that day still waiting for approval or posting, supervisor approvals and front-office drafts still open, and drawers closed with
 * a difference. A table this database does not have yet counts as nothing waiting.
 */
@Component
public class DayEndChecks {

    private final JdbcTemplate jdbc;

    DayEndChecks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Check> checks(int branch, LocalDate day) {
        List<Check> out = new ArrayList<>();
        out.add(drawers(branch, day));
        out.add(handoffs(branch));
        out.add(balanced(branch, day));
        out.add(waitingVouchers(branch, day));
        out.add(approvals(branch));
        out.add(drafts(branch));
        out.add(differences(branch, day));
        out.add(posted(branch, day));
        return out;
    }

    private static String join(List<String> items) {
        if (items.size() <= 6) return String.join(", ", items);
        return String.join(", ", items.subList(0, 6)) + " and " + (items.size() - 6) + " more";
    }

    private static String peso(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Teller drawers that took or paid cash that day and have no count (ecis_drawer_close) for it. */
    private Check drawers(int branch, LocalDate day) {
        List<Integer> active = jdbc.query("""
                SELECT DISTINCT x.DTCTS_NUM FROM transsum t
                JOIN transdt x ON t.TR_BRCODE = x.DTTR_BRCODE AND t.TR_YEAR = x.DTTR_YEAR AND t.TR_MODULE = x.DTTR_MODULE AND t.TR_CODE = x.DTTR_CODE AND t.TR_CTLNO = x.DTTR_CTLNO
                WHERE t.TR_BRCODE = ? AND t.TR_YEAR = ? AND t.TR_DATE = ? AND x.DTSLC_CODE = 11 AND x.DTSLT_CODE = 1 AND x.DTSLE_CODE = ?
                ORDER BY x.DTCTS_NUM
                """, (rs, i) -> rs.getInt(1), branch, day.getYear(), java.sql.Date.valueOf(day), HandoverRules.TELLER);
        Set<Integer> closed = new HashSet<>();
        try {
            jdbc.query("SELECT number FROM ecis_drawer_close WHERE branch_code = ? AND tr_date = ? AND event = ?", (rs) -> { closed.add(rs.getInt(1)); },
                branch, java.sql.Date.valueOf(day), HandoverRules.TELLER);
        } catch (DataAccessException e) {
            // drawer controls are not set up yet: nothing counted
        }
        List<String> open = active.stream().filter((n) -> !closed.contains(n)).map((n) -> new Drawer(HandoverRules.TELLER, n).label()).toList();
        String label = "Every teller who handled cash closed the drawer";
        if (active.isEmpty()) return new Check("drawers", label, true, "No teller handled cash.", true);
        if (open.isEmpty()) return new Check("drawers", label, true, active.size() + (active.size() == 1 ? " drawer" : " drawers") + " counted and closed.", true);
        return new Check("drawers", label, false, join(open) + (open.size() == 1 ? " has" : " have") + " not counted and closed the drawer (Front Office > Handover).", true);
    }

    /** Cash handed from one drawer to another and not yet counted by the receiver, or disputed. */
    private Check handoffs(int branch) {
        int waiting = count("SELECT COUNT(*) FROM ecis_cash_handover WHERE branch_code = ? AND status IN ('PENDING', 'DISPUTED')", branch);
        String label = "No cash hand-off is waiting";
        return waiting == 0 ? new Check("handoffs", label, true, "All hand-offs are settled.", true)
            : new Check("handoffs", label, false, waiting + (waiting == 1 ? " hand-off is" : " hand-offs are") + " waiting to be counted or settled (Front Office > Handover).", true);
    }

    /** Posted vouchers of the day whose lines do not add up to zero (debits not equal to credits). */
    private Check balanced(int branch, LocalDate day) {
        List<String> off = jdbc.query("""
                SELECT t.TR_DOCNO, SUM(d.DTAMT) FROM transsum t
                JOIN transdt d ON d.DTTR_BRCODE = t.TR_BRCODE AND d.DTTR_YEAR = t.TR_YEAR AND d.DTTR_MODULE = t.TR_MODULE AND d.DTTR_CODE = t.TR_CODE AND d.DTTR_CTLNO = t.TR_CTLNO
                WHERE t.TR_BRCODE = ? AND t.TR_YEAR = ? AND t.TR_DATE = ? AND d.DTUPD_TAG = 1
                GROUP BY t.TR_MODULE, t.TR_CODE, t.TR_CTLNO, t.TR_DOCNO
                HAVING ABS(SUM(d.DTAMT)) >= 0.005
                ORDER BY t.TR_MODULE, t.TR_CODE, t.TR_CTLNO
                """, (rs, i) -> "voucher " + rs.getString(1) + " (off by " + peso(rs.getBigDecimal(2).abs()) + ")", branch, day.getYear(), java.sql.Date.valueOf(day));
        String label = "Debits equal credits";
        return off.isEmpty() ? new Check("balanced", label, true, "Every voucher posted on the day balances.", true)
            : new Check("balanced", label, false, "Not balanced: " + join(off) + ". Tell the accountant.", true);
    }

    /** Vouchers dated that day that are encoded but not yet approved or posted, by kind. */
    private Check waitingVouchers(int branch, LocalDate day) {
        Map<String, Integer> byKind = new LinkedHashMap<>();
        jdbc.query("""
                SELECT y.TransTypeDesc, t.TR_CODE, COUNT(DISTINCT t.TR_CTLNO) FROM transsum t
                JOIN transdt d ON d.DTTR_BRCODE = t.TR_BRCODE AND d.DTTR_YEAR = t.TR_YEAR AND d.DTTR_MODULE = t.TR_MODULE AND d.DTTR_CODE = t.TR_CODE AND d.DTTR_CTLNO = t.TR_CTLNO
                LEFT JOIN transtype y ON y.TransTypeID = t.TR_CODE
                WHERE t.TR_BRCODE = ? AND t.TR_YEAR = ? AND t.TR_DATE = ? AND d.DTUPD_TAG = 2
                GROUP BY y.TransTypeDesc, t.TR_CODE
                ORDER BY t.TR_CODE
                """, (rs) -> { byKind.merge(rs.getString(1) == null ? "type " + rs.getInt(2) : rs.getString(1).trim(), rs.getInt(3), Integer::sum); },
            branch, day.getYear(), java.sql.Date.valueOf(day));
        String label = "Vouchers of the day approved and posted";
        if (byKind.isEmpty()) return new Check("vouchers", label, true, "Nothing is waiting.", false);
        List<String> parts = byKind.entrySet().stream().map((e) -> e.getValue() + " " + e.getKey()).toList();
        return new Check("vouchers", label, false, "Still waiting for approval or posting: " + join(parts) + ". They keep their date and can be finished after End Day.", false);
    }

    private Check approvals(int branch) {
        int n = count("SELECT COUNT(*) FROM ecis_fo_override WHERE branch_code = ? AND status = 'PENDING'", branch);
        String label = "Supervisor approvals answered";
        return n == 0 ? new Check("approvals", label, true, "None waiting.", false)
            : new Check("approvals", label, false, n + (n == 1 ? " request waits" : " requests wait") + " for a supervisor. A transaction waiting for one was not posted.", false);
    }

    private Check drafts(int branch) {
        int n = count("SELECT COUNT(*) FROM ecis_fo_draft WHERE branch_code = ?", branch);
        String label = "Front office drafts finished";
        return n == 0 ? new Check("drafts", label, true, "None open.", false)
            : new Check("drafts", label, false, n + (n == 1 ? " draft is" : " drafts are") + " not posted. Drafts are never posted by themselves.", false);
    }

    /** Drawers counted that day with a difference between the count and what the ledger expected. */
    private Check differences(int branch, LocalDate day) {
        List<String> diffs = new ArrayList<>();
        try {
            jdbc.query("""
                    SELECT event, number, variance FROM ecis_drawer_close WHERE branch_code = ? AND tr_date = ? AND ABS(variance) >= 0.005 ORDER BY event, number, id
                    """, (rs) -> { diffs.add(new Drawer(rs.getInt(1), rs.getInt(2)).label() + " " + (rs.getBigDecimal(3).signum() > 0 ? "over " : "short ") + peso(rs.getBigDecimal(3).abs())); },
                branch, java.sql.Date.valueOf(day));
        } catch (DataAccessException e) {
            // drawer controls are not set up yet
        }
        String label = "Drawers counted without a difference";
        return diffs.isEmpty() ? new Check("differences", label, true, "No differences.", false)
            : new Check("differences", label, false, "Counted with a difference: " + join(diffs) + ". The reasons were given at the count.", false);
    }

    private Check posted(int branch, LocalDate day) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_DATE = ?", Integer.class, branch, day.getYear(), java.sql.Date.valueOf(day));
        int c = n == null ? 0 : n;
        return new Check("posted", "Transactions of the day", true, c == 0 ? "Nothing was posted." : c + (c == 1 ? " transaction" :  " transactions") + " dated on the day.", false);
    }

    private int count(String sql, int branch) {
        try {
            Integer n = jdbc.queryForObject(sql, Integer.class, branch);
            return n == null ? 0 : n;
        } catch (DataAccessException e) {
            return 0;
        }
    }
}
