package ph.coop.core.deposit;

import static ph.coop.core.web.ApiErrors.bad;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;

/**
 * Dormant savings accounts. A savings type may carry a dormancy period (Settings > Account Types). Accounts of such a type whose member has not deposited or withdrawn for that long
 * are listed for review; an administrator marks the ones chosen as dormant, with a reason. Nothing is marked by itself. A dormant account takes no deposit or withdrawal until an
 * administrator or a Membership Officer reactivates it, again with a reason, after which the period starts over. Both are written to the audit log per account. No fee is charged:
 * the savings types carry none.
 */
@RestController
@RequestMapping("/api/savings-dormancy")
public class SavingsDormancyController {

    private static final int SAVINGS = 22;
    private static final int PAGE = 500;
    private static final String REACTIVATE = "savings.dormant.reactivate";
    private static final String ENTITY = "savings_account";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    SavingsDormancyController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Row(int clientId, String clientName, int typeCode, String typeName, int refNo, String accountNo, String opened, String lastActivity, BigDecimal balance) {}

    public record TypePeriod(int typeCode, String typeName, Integer months) {}

    /** {@code more} says the list was cut at 500 accounts; mark these and look again. */
    public record Review(String asOf, List<TypePeriod> types, List<Row> rows, boolean more) {}

    public record Key(int clientId, int typeCode, int refNo) {}

    public record MarkIn(List<Key> accounts, String reason) {}

    public record Marked(int marked, List<String> skipped) {}

    public record ReactivateIn(int clientId, int typeCode, int refNo, String reason) {}

    private int branch(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1", Integer.class, auth.getName());
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not a staff account.");
        return r.get(0);
    }

    private Integer userId(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessID FROM access WHERE AccessUserName = ? LIMIT 1", Integer.class, auth.getName());
        return r.isEmpty() ? null : r.get(0);
    }

    private static String reason(String r) {
        if (r == null || r.isBlank()) throw bad("Give the reason.");
        String t = r.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    private static String id(int branch, int clientId, int type, int ref) {
        return branch + "/" + clientId + "/" + type + "/" + ref;
    }

    private static LocalDate dateOf(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        if (o instanceof LocalDateTime t) return t.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    private static final String SYSTEM = "(53, 54)"; // DormancyRules.SYSTEM_POSTINGS

    private static final String MOVEMENTS = " FROM sldtl d WHERE d.SL_BRCODE = s.SDBR_CODE AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = s.SDSLC_CODE AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO AND d.SLE_CODE = 11";

    private static final String SELECT = "SELECT s.ClientIDSD, COALESCE(NULLIF(c.AccountName, ''), CONCAT_WS(', ', c.LName, c.FName)), s.SDSLT_CODE, t.SLTypeM_DESC, s.SDREF_NO, s.SDREF_NOX, s.SDTR_DATE, s.SDSTATUS,"
        + " (SELECT MAX(d.TR_DATE)" + MOVEMENTS + " AND d.TR_CODE NOT IN " + SYSTEM + "),"
        + " (SELECT COALESCE(-SUM(d.AMT), 0)" + MOVEMENTS + "),"
        + " (SELECT MAX(a.at) FROM ecis_audit_log a WHERE a.action = '" + REACTIVATE + "' AND a.entity = '" + ENTITY + "' AND a.entity_id = CONCAT(s.SDBR_CODE, '/', s.ClientIDSD, '/', s.SDSLT_CODE, '/', s.SDREF_NO))"
        + " FROM sd s JOIN client c ON c.ClientIDBrCode = s.SDBR_CODE AND c.ClientID = s.ClientIDSD"
        + " LEFT JOIN sltype t ON t.SLTypeBR_CODE = s.SDBR_CODE AND t.SLTypeSLC_CODE = s.SDSLC_CODE AND t.SLTypeSLT_CODE = s.SDSLT_CODE"
        + " WHERE s.SDBR_CODE = ? AND s.SDSLC_CODE = " + SAVINGS;

    /** One account as read, before the rule is applied. */
    private record Found(Row row, int status, LocalDate opened, LocalDate lastActivity, LocalDate reactivated) {}

    private Found found(java.sql.ResultSet rs) throws java.sql.SQLException {
        LocalDate opened = dateOf(rs.getObject(7)), last = dateOf(rs.getObject(9)), reactivated = dateOf(rs.getObject(11));
        return new Found(new Row(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getInt(5), rs.getString(6), opened == null ? null : opened.toString(),
            last == null ? null : last.toString(), rs.getBigDecimal(10)), rs.getInt(8), opened, last, reactivated);
    }

    /** The savings types and their dormancy period in months (null: the type never goes dormant). */
    private List<TypePeriod> periods(int branch) {
        return jdbc.query("SELECT SLTypeSLT_CODE, SLTypeM_DESC, SLTypeDORM_TERM, SLTypeDORM_PERD FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? ORDER BY SLTypeSLT_CODE", (rs, i) -> {
            Object term = rs.getObject(3), unit = rs.getObject(4);
            Integer months = term == null || unit == null || ((Number) term).intValue() <= 0 ? null : ((Number) unit).intValue() == 4 ? Integer.valueOf(((Number) term).intValue())
                : ((Number) unit).intValue() == 7 ? Integer.valueOf(((Number) term).intValue() * 12) : null;
            return new TypePeriod(rs.getInt(1), rs.getString(2), months);
        }, branch, SAVINGS);
    }

    /** The active savings accounts that are due to be marked dormant today, longest quiet first, at most 500 at a time. Saves nothing. */
    @GetMapping("/review")
    @PreAuthorize("hasRole('ADMIN')")
    public Review review(Authentication auth) {
        int branch = branch(auth);
        LocalDate today = LocalDate.now(clock);
        List<TypePeriod> types = periods(branch);
        List<Found> due = new ArrayList<>();
        for (TypePeriod t : types) {
            if (t.months() == null) continue;
            Date cutoff = Date.valueOf(DormancyRules.cutoff(today, t.months()));
            // the database narrows the list (active, old enough, no deposit or withdrawal since the cut-off); DormancyRules decides
            List<Found> rows = jdbc.query(SELECT + " AND s.SDSLT_CODE = ? AND s.SDSTATUS = " + DormancyRules.ACTIVE + " AND (s.SDTR_DATE IS NULL OR s.SDTR_DATE <= ?)"
                + " AND NOT EXISTS (SELECT 1" + MOVEMENTS + " AND d.TR_DATE > ? AND d.TR_CODE NOT IN " + SYSTEM + ")"
                + " ORDER BY s.ClientIDSD, s.SDREF_NO LIMIT " + (PAGE + 1), (rs, i) -> found(rs), branch, t.typeCode(), cutoff, cutoff);
            for (Found f : rows) if (DormancyRules.due(f.status(), f.opened(), f.lastActivity(), f.reactivated(), today, t.months())) due.add(f);
        }
        due.sort(java.util.Comparator.comparing((Found f) -> f.lastActivity() != null ? f.lastActivity() : f.opened() != null ? f.opened() : LocalDate.MIN));
        boolean more = due.size() > PAGE;
        return new Review(today.toString(), types, due.stream().limit(PAGE).map(Found::row).toList(), more);
    }

    private Found one(int branch, Key k) {
        List<Found> r = jdbc.query(SELECT + " AND s.ClientIDSD = ? AND s.SDSLT_CODE = ? AND s.SDREF_NO = ?", (rs, i) -> found(rs), branch, k.clientId(), k.typeCode(), k.refNo());
        return r.isEmpty() ? null : r.get(0);
    }

    private void setStatus(int branch, Key k, int from, int to, Authentication auth) {
        int n = jdbc.update("UPDATE sd SET SDSTATUS = ?, SDUPDDATETIME = ?, SDUPDUSER = ? WHERE SDBR_CODE = ? AND SDSLC_CODE = ? AND ClientIDSD = ? AND SDSLT_CODE = ? AND SDREF_NO = ? AND SDSTATUS = ?",
            to, Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), userId(auth), branch, SAVINGS, k.clientId(), k.typeCode(), k.refNo(), from);
        if (n != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "The account changed while this was being saved. Look at it again.");
    }

    /** Marks the chosen accounts dormant. Each is checked again first; one that is no longer due (a deposit came in, it was closed) is skipped and named. */
    @PostMapping("/mark")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Marked mark(@RequestBody MarkIn in, Authentication auth) {
        int branch = branch(auth);
        String why = reason(in == null ? null : in.reason());
        if (in.accounts() == null || in.accounts().isEmpty()) throw bad("Choose at least one account.");
        if (in.accounts().size() > PAGE) throw bad("Mark at most " + PAGE + " accounts at a time.");
        LocalDate today = LocalDate.now(clock);
        Map<Integer, Integer> months = new LinkedHashMap<>();
        for (TypePeriod t : periods(branch)) months.put(t.typeCode(), t.months());
        int marked = 0;
        List<String> skipped = new ArrayList<>();
        for (Key k : new java.util.LinkedHashSet<>(in.accounts())) {
            Found f = one(branch, k);
            if (f == null) { skipped.add("Client " + k.clientId() + " account " + k.refNo() + ": not found."); continue; }
            if (!DormancyRules.due(f.status(), f.opened(), f.lastActivity(), f.reactivated(), today, months.get(k.typeCode()))) {
                skipped.add(f.row().clientName() + " " + (f.row().accountNo() == null ? k.refNo() : f.row().accountNo()) + ": no longer due.");
                continue;
            }
            setStatus(branch, k, DormancyRules.ACTIVE, DormancyRules.DORMANT, auth);
            AuditLog.write(jdbc, auth.getName(), "savings.dormant.mark", ENTITY, id(branch, k.clientId(), k.typeCode(), k.refNo()),
                "{\"status\":15,\"lastActivity\":" + (f.lastActivity() == null ? "null" : "\"" + f.lastActivity() + "\"") + "}", "{\"status\":16}", why);
            marked++;
        }
        return new Marked(marked, skipped);
    }

    /** Dormant savings accounts of a member (by client number) or by part of a name, at most 100. */
    @GetMapping("/dormant")
    @PreAuthorize("hasAnyRole('ADMIN','MEMBERSHIP_OFFICER')")
    public List<Row> dormant(@RequestParam(defaultValue = "") String q, Authentication auth) {
        int branch = branch(auth);
        String t = q.trim();
        if (t.isEmpty()) return List.of();
        String base = SELECT + " AND s.SDSTATUS = " + DormancyRules.DORMANT;
        if (t.matches("[0-9]{1,9}")) return jdbc.query(base + " AND s.ClientIDSD = ? ORDER BY s.SDSLT_CODE, s.SDREF_NO LIMIT 100", (rs, i) -> found(rs).row(), branch, Integer.parseInt(t));
        if (t.length() < 3) throw bad("Type at least 3 letters of the name, or the client number.");
        String like = t.toLowerCase().replace("%", "").replace("_", "") + "%";
        return jdbc.query(base + " AND (LOWER(c.LName) LIKE ? OR LOWER(c.FName) LIKE ?) ORDER BY c.LName, c.FName, s.SDREF_NO LIMIT 100", (rs, i) -> found(rs).row(), branch, like, like);
    }

    /** Makes a dormant account active again, so it takes deposits and withdrawals. The dormancy period starts over from today. */
    @PostMapping("/reactivate")
    @PreAuthorize("hasAnyRole('ADMIN','MEMBERSHIP_OFFICER')")
    @Transactional
    public Row reactivate(@RequestBody ReactivateIn in, Authentication auth) {
        int branch = branch(auth);
        String why = reason(in == null ? null : in.reason());
        Key k = new Key(in.clientId(), in.typeCode(), in.refNo());
        Found f = one(branch, k);
        if (f == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such savings account.");
        if (f.status() != DormancyRules.DORMANT) throw bad("This account is not dormant.");
        List<Integer> client = jdbc.queryForList("SELECT AccountStatus FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, branch, in.clientId());
        if (!client.isEmpty() && client.get(0) != null && (client.get(0) == 3 || client.get(0) == 98 || client.get(0) == 99)) {
            throw bad("This member's membership is closed, disapproved or not yet approved. Reactivate the membership first.");
        }
        setStatus(branch, k, DormancyRules.DORMANT, DormancyRules.ACTIVE, auth);
        AuditLog.write(jdbc, auth.getName(), REACTIVATE, ENTITY, id(branch, in.clientId(), in.typeCode(), in.refNo()), "{\"status\":16}", "{\"status\":15}", why);
        return one(branch, k).row();
    }
}
