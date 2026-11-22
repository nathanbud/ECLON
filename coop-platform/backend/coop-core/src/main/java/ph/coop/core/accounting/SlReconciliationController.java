package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;

/**
 * SL to control account reconciliation (Reports > Accounting): for every member account type whose principal posts to the chart (glcontrol, event 11),
 * the members' total in the subsidiary ledger (sldtl) against the balance of its control accounts in the general ledger (gltrans), as of a date, grouped
 * and compared by {@link SlReconciliation}. Read only. Administrators and accountants; every run is audited as {@code report.run}.
 * <p>
 * It adds up the whole member ledger (about 51 million rows on the main database), which takes a few minutes (over four on the running server), so the result of a run is kept for
 * ten minutes per branch and date and given again unless {@code fresh}.
 */
@RestController
@RequestMapping("/api/reports/sl-reconciliation")
class SlReconciliationController {

    static final String REPORT_ID = "sl-reconciliation";
    private static final long KEEP_MS = 10 * 60_000L;

    record Response(int branch, LocalDate asOf, List<SlReconciliation.Group> groups, BigDecimal totalDifference, int unreconciled,
                    LocalDateTime ranAt, String ranBy, boolean kept) {}

    private record Kept(Response response, long at) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    SlReconciliationController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static void check(Authentication auth) {
        boolean may = auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_ACCOUNTANT"));
        if (!may) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only administrators and accountants can run the SL reconciliation.");
    }

    @GetMapping
    Response run(@RequestParam(defaultValue = "1") int branchCode, @RequestParam(required = false) LocalDate asOf,
                 @RequestParam(defaultValue = "false") boolean fresh, Authentication auth) {
        check(auth);
        LocalDate day = asOf == null ? LocalDate.now(clock) : asOf;
        if (day.isAfter(LocalDate.now(clock))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The date cannot be after today.");
        String key = branchCode + "/" + day;
        Kept k = kept.get(key);
        if (!fresh && k != null && System.currentTimeMillis() - k.at() < KEEP_MS) {
            Response r = k.response();
            return new Response(r.branch(), r.asOf(), r.groups(), r.totalDifference(), r.unreconciled(), r.ranAt(), r.ranBy(), true);
        }
        Response r = compute(branchCode, day, auth.getName());
        kept.put(key, new Kept(r, System.currentTimeMillis()));
        AuditLog.write(jdbc, auth.getName(), "report.run", "report", REPORT_ID, null,
            "branch " + branchCode + ", as of " + day + " -> " + r.groups().size() + " groups, " + r.unreconciled() + " unreconciled", null);
        return r;
    }

    private Response compute(int branch, LocalDate day, String by) {
        // Only balance-sheet accounts (assets 1, liabilities 2, equity 3) are control accounts. glcontrol also names income accounts for some types
        // (a fee taken as the type's principal), which collect a year's income and are closed to zero, so they never equal the members' accounts.
        List<SlReconciliation.Link> links = jdbc.query("""
                SELECT DISTINCT g.GLControlSLC_CODE, g.GLControlSLT_CODE, g.GLControlACCT_CODE FROM glcontrol g
                JOIN coa c ON c.COABR_CODE = g.GLControlBR_CODE AND c.COAID = g.GLControlACCT_CODE
                WHERE g.GLControlBR_CODE = ? AND g.GLControlSLE_CODE = 11 AND c.COAacct_type IN (1, 2, 3)
                ORDER BY g.GLControlSLC_CODE, g.GLControlSLT_CODE, g.GLControlACCT_CODE
                """, (rs, i) -> new SlReconciliation.Link(rs.getInt(1), rs.getInt(2), rs.getString(3).trim()), branch);
        if (links.isEmpty()) return new Response(branch, day, List.of(), BigDecimal.ZERO, 0, LocalDateTime.now(clock).withNano(0), by, false);

        List<Integer> classes = links.stream().map(SlReconciliation.Link::slc).distinct().sorted().toList();
        String in = String.join(", ", classes.stream().map((c) -> "?").toList());
        List<Object> args = new ArrayList<>(List.of(branch, Date.valueOf(day)));
        args.addAll(classes);
        Map<String, BigDecimal> sl = new HashMap<>();
        jdbc.query("SELECT SLC_CODE, SLT_CODE, SUM(AMT) FROM sldtl WHERE SL_BRCODE = ? AND SLE_CODE = 11 AND TR_DATE <= ? AND SLC_CODE IN (" + in + ") GROUP BY SLC_CODE, SLT_CODE",
            (rs) -> { sl.put(rs.getInt(1) + "/" + rs.getInt(2), nz(rs.getBigDecimal(3))); }, args.toArray());

        // In gltrans a credit is stored as a negative amount, so a balance is debits plus credits (as the financial statements add it). The year-end
        // profit and loss summary rows (type 98) are left out, as the statement of financial condition leaves them out.
        Map<String, BigDecimal> gl = new HashMap<>();
        jdbc.query("""
                SELECT GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE <= ? AND GLTransTR_CODE <> ?
                  AND GLTransACCT_CODE IN (SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLE_CODE = 11)
                GROUP BY GLTransACCT_CODE
                """, (rs) -> { gl.merge(rs.getString(1).trim(), nz(rs.getBigDecimal(2)), BigDecimal::add); },
                branch, Date.valueOf(day), ph.coop.core.accounting.close.ClosedBooks.PROFIT_LOSS_SUMMARY, branch);

        Map<String, String> typeNames = new HashMap<>();
        jdbc.query("SELECT SLTypeSLC_CODE, SLTypeSLT_CODE, SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ?",
            (rs) -> { typeNames.put(rs.getInt(1) + "/" + rs.getInt(2), rs.getString(3) == null ? "" : rs.getString(3).trim()); }, branch);
        Map<Integer, String> classNames = new HashMap<>();
        jdbc.query("SELECT SLClassID, SLClassDesc FROM slclass", (rs) -> { classNames.put(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim()); });
        Map<String, String> accountNames = new HashMap<>();
        jdbc.query("SELECT COAID, COADesc FROM coa WHERE COABR_CODE = ? AND COAID IN (SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLE_CODE = 11)",
            (rs) -> { accountNames.put(rs.getString(1).trim(), rs.getString(2) == null ? "" : rs.getString(2).trim()); }, branch, branch);

        SlReconciliation.Result r = SlReconciliation.reconcile(links, sl, gl, typeNames, classNames, accountNames);
        return new Response(branch, day, r.groups(), r.totalDifference(), r.unreconciled(), LocalDateTime.now(clock).withNano(0), by, false);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
