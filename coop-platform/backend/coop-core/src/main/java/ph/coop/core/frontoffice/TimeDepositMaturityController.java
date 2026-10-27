package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.deposit.TimeDepositRules;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;

/**
 * The notice list for time deposits: certificates that mature in the next days and those that have matured and still hold money, so staff can contact the
 * member and then pay out or renew each one by hand. Nothing here changes anything, and nothing happens by itself at maturity. Certificates that were
 * paid out or rolled over are recognised from the ledger (the old master row is never closed). Lock In certificates are listed too.
 */
@RestController
@RequestMapping("/api/time-deposits")
class TimeDepositMaturityController {

    /** How far back a matured certificate that still holds money is looked for. */
    private static final int LOOK_BACK_DAYS = 400;

    record Notice(int clientId, String client, int typeCode, String type, int refNo, String certificate, LocalDate placed, LocalDate maturity, int daysLeft, int termDays, BigDecimal ratePercent,
                  BigDecimal balance, BigDecimal accruedInterest, BigDecimal estimatedInterest, boolean overdue, boolean canClose) {}

    record Notices(LocalDate asOf, int days, List<Notice> notices, int overdue, int maturingSoon, BigDecimal totalBalance) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final FrontOfficeAccess access;

    TimeDepositMaturityController(JdbcTemplate jdbc, Clock clock, FrontOfficeAccess access) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.access = access;
    }

    private int branch(Authentication auth) {
        try {
            return access.of(auth).branch();
        } catch (ResponseStatusException e) {
            boolean staff = auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_ACCOUNTANT"));
            if (!staff) throw e;
            List<Integer> b = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? LIMIT 1", Integer.class, auth.getName());
            if (b.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has no branch.");
            return b.get(0);
        }
    }

    @GetMapping("/maturities")
    Notices maturities(@RequestParam(defaultValue = "30") int days, Authentication auth) {
        int branch = branch(auth);
        if (days < 0 || days > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for 0 to 365 days ahead.");
        LocalDate today = LocalDate.now(clock);
        List<Notice> all = jdbc.query("""
                SELECT * FROM (
                  SELECT d.ClientIDTD AS cid, COALESCE(NULLIF(k.AccountName, ''), CONCAT_WS(', ', k.LName, k.FName)) AS cname, d.TDSLT_CODE AS slt, t.SLTypeM_DESC AS tname, d.TDREF_NO AS ref,
                    d.TDCERT_NUM AS cert, d.TDTR_DATE AS placed, d.TDMAT_DATE AS mat, d.TDTERMS AS terms, d.TDINT_RATE AS rate,
                    (SELECT COALESCE(-SUM(l.AMT), 0) FROM sldtl l WHERE l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE AND l.SLT_CODE = d.TDSLT_CODE
                       AND l.REF_NO = d.TDREF_NO AND l.SLE_CODE = 11) AS bal,
                    (SELECT COALESCE(-SUM(l.AMT), 0) FROM sldtl l WHERE l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE AND l.SLT_CODE = d.TDSLT_CODE
                       AND l.REF_NO = d.TDREF_NO AND l.SLE_CODE = 71) AS accrued
                  FROM td d
                  LEFT JOIN sltype t ON t.SLTypeBR_CODE = d.TDBR_CODE AND t.SLTypeSLC_CODE = d.TDSLC_CODE AND t.SLTypeSLT_CODE = d.TDSLT_CODE
                  LEFT JOIN client k ON k.ClientIDBrCode = d.TDBR_CODE AND k.ClientID = d.ClientIDTD
                  WHERE d.TDBR_CODE = ? AND d.TDSLC_CODE = 24 AND d.TDSTATUS = 15 AND d.TDMAT_DATE >= ? AND d.TDMAT_DATE <= ?
                ) z WHERE z.bal > 0.005 ORDER BY z.mat, z.ref
                """, (rs, i) -> {
                    LocalDate mat = rs.getDate(8).toLocalDate();
                    BigDecimal bal = rs.getBigDecimal(11), rate = rs.getBigDecimal(10);
                    int terms = rs.getInt(9);
                    int slt = rs.getInt(3);
                    return new Notice(rs.getInt(1), rs.getString(2), slt, rs.getString(4), rs.getInt(5), rs.getString(6), rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate(), mat,
                        (int) ChronoUnit.DAYS.between(today, mat), terms, rate, bal, rs.getBigDecimal(12), TimeDepositRules.maturityInterest(slt, bal, rate, terms), mat.isBefore(today),
                        TimeDepositClosureRules.HANDLED_TYPES.contains(slt) && !mat.isAfter(today));
                }, branch, java.sql.Date.valueOf(today.minusDays(LOOK_BACK_DAYS)), java.sql.Date.valueOf(today.plusDays(days)));
        int overdue = (int) all.stream().filter(Notice::overdue).count();
        int soon = (int) all.stream().filter((n) -> !n.overdue() && n.daysLeft() <= 7).count();
        BigDecimal total = all.stream().map(Notice::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Notices(today, days, all, overdue, soon, total);
    }

    /** Just the two numbers for a badge: matured and still holding money, and maturing within a week. */
    @GetMapping("/maturities/count")
    int[] count(Authentication auth) {
        Notices n = maturities(7, auth);
        return new int[] {n.overdue(), n.maturingSoon()};
    }
}
