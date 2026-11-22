package ph.coop.core.accounting;



import static ph.coop.core.web.ApiErrors.bad;import ph.coop.core.audit.AuditLog;import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chart of accounts setup for administrators and accountants: add an account, rename it, move it under another summary account, switch it off.
 * <p>
 * The numbering follows the old chart: the first digit is the account type (1 assets, 2 liabilities, 3 equity, 4 revenues, 5 expenses, 6 net income) and an
 * account's number starts with its parent's number. A detail account takes postings; a summary account only totals the accounts under it. Accounts are never
 * deleted and the number never changes, because the ledger points at it. An account the system itself posts to (a ledger account of a loan, savings or share
 * capital type, a bank, a branch setting) cannot be switched off. Every change needs a reason and is written to the audit log with the values before and after.
 */
@RestController
@RequestMapping("/api/accounting/chart")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
public class ChartOfAccountsController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("[1-9][0-9]{0,9}");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    ChartOfAccountsController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record AccountRow(String code, String name, int type, String typeName, boolean summary, String parent, boolean disabled, boolean glOnly, boolean hasChildren, boolean inUse) {}

    public record Usage(String code, long entries, long children, List<String> usedBy) {}

    /** What the form sends. {@code summary} and {@code code} count on a new account only; they cannot change afterwards. */
    public record Form(String code, String name, Boolean summary, String parent, Boolean glOnly, Boolean disabled, String reason) {}


    int branch(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1", Integer.class, auth.getName());
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are signed in with an account that is not in the cooperative's staff list, so this cannot be done with it. Sign in with your own staff account, or ask an administrator to add you in Settings > Users.");
        return r.get(0);
    }

    private static final String SELECT = """
            SELECT c.COAID, c.COADesc, c.COAacct_type, t.GLAcctTypeDesc, c.COAacct_tag, c.COAacct_sum, COALESCE(c.COAisdisabled, 0), COALESCE(c.COAcanGLOnly, 0),
                   (SELECT COUNT(*) FROM coa k WHERE k.COABR_CODE = c.COABR_CODE AND k.COAacct_sum = c.COAID),
                   (SELECT COUNT(*) FROM glcontrol g WHERE g.GLControlBR_CODE = c.COABR_CODE AND g.GLControlACCT_CODE = c.COAID)
                   + (SELECT COUNT(*) FROM bankcode b WHERE b.BCBR_CODE = c.COABR_CODE AND b.BCCOAID = c.COAID)
                   + (SELECT COUNT(*) FROM branches r WHERE r.BranchesID = c.COABR_CODE AND c.COAID IN (r.PWDCOAID, r.SCCOAID, r.REB_EXPACCT, r.TD_EXPACCT))
            FROM coa c LEFT JOIN glaccttype t ON t.GLAcctTypeID = c.COAacct_type
            WHERE c.COABR_CODE = ?""";

    private AccountRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AccountRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), "S".equals(rs.getString(5)), rs.getString(6), rs.getInt(7) != 0, rs.getInt(8) != 0,
            rs.getLong(9) > 0, rs.getLong(10) > 0);
    }

    /** The whole chart of the branch, in account-number order. */
    @GetMapping
    public List<AccountRow> list(Authentication auth) {
        return jdbc.query(SELECT + " ORDER BY c.COAID", (rs, i) -> row(rs), branch(auth));
    }

    private AccountRow find(int branch, String code) {
        List<AccountRow> r = jdbc.query(SELECT + " AND c.COAID = ?", (rs, i) -> row(rs), branch, code);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account.");
        return r.get(0);
    }

    /** How much of the ledger an account carries and what in the system points at it. Read when the edit form opens. */
    @GetMapping("/{code}/usage")
    public Usage usage(@PathVariable String code, Authentication auth) {
        int branch = branch(auth);
        find(branch, code);
        long entries = jdbc.queryForObject("SELECT COUNT(*) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ?", Long.class, branch, code);
        long children = jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = ? AND COAacct_sum = ?", Long.class, branch, code);
        return new Usage(code, entries, children, usedBy(branch, code));
    }

    private List<String> usedBy(int branch, String code) {
        List<String> r = new java.util.ArrayList<>();
        long gl = jdbc.queryForObject("SELECT COUNT(*) FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlACCT_CODE = ?", Long.class, branch, code);
        if (gl > 0) r.add("the ledger account of " + gl + " loan, savings or share capital setting" + (gl == 1 ? "" : "s"));
        long banks = jdbc.queryForObject("SELECT COUNT(*) FROM bankcode WHERE BCBR_CODE = ? AND BCCOAID = ?", Long.class, branch, code);
        if (banks > 0) r.add("the ledger account of " + banks + " bank account" + (banks == 1 ? "" : "s"));
        long br = jdbc.queryForObject("SELECT COUNT(*) FROM branches WHERE BranchesID = ? AND ? IN (PWDCOAID, SCCOAID, REB_EXPACCT, TD_EXPACCT)", Long.class, branch, code);
        if (br > 0) r.add("a branch setting");
        return r;
    }

    static String reason(String r) {
        if (r == null || r.isBlank()) throw bad("Give the reason for this change.");
        String t = r.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    private static String name(String n) {
        String t = n == null ? "" : n.trim();
        if (t.isEmpty() || t.length() > 50) throw bad("The account name is required, up to 50 characters.");
        return t;
    }

    /** The parent must be an enabled summary account of the same type, and the child's number must start with the parent's and be longer. */
    private AccountRow checkParent(int branch, String code, String parent) {
        if (parent == null || parent.isBlank()) throw bad("Choose the summary account this one belongs under.");
        List<AccountRow> p = jdbc.query(SELECT + " AND c.COAID = ?", (rs, i) -> row(rs), branch, parent.trim());
        if (p.isEmpty()) throw bad("The summary account " + parent.trim() + " does not exist.");
        AccountRow a = p.get(0);
        if (!a.summary()) throw bad(a.code() + " " + a.name() + " is a detail account. Choose a summary account.");
        if (a.disabled()) throw bad(a.code() + " " + a.name() + " is switched off.");
        if (code.equals(a.code()) || !code.startsWith(a.code())) throw bad("The number " + code + " must start with the number of its summary account (" + a.code() + ").");
        return a;
    }

    void audit(Authentication auth, String action, int branch, String code, Object prev, Object next, String why) {
        AuditLog.write(jdbc, auth.getName(), action, "coa", branch + "/" + code, json(prev), json(next), why);
    }

    private static String json(Object o) {
        if (o == null) return null;
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    Integer userId(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessID FROM access WHERE AccessUserName = ? LIMIT 1", Integer.class, auth.getName());
        return r.isEmpty() ? null : r.get(0);
    }

    /** Writes one new account the way the old system does, with its audit row. {@code parent} is null only for a top-level heading (from an uploaded list). */
    void insert(int branch, String code, String name, boolean summary, String parent, boolean glOnly, Integer userId, Authentication auth, String why) {
        int type = code.charAt(0) - '0';
        jdbc.update("""
                INSERT INTO coa (COABR_CODE, COAID, COADesc, COAacct_type, COAacct_tag, COAacct_sum, COAconso_acct, COAseqno, COAisdisabled, COAcanGLOnly, COAUpdUser, COAupdDateTime)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)""",
            branch, code, name, type, summary ? "S" : "D", parent, summary ? null : code, glOnly ? 1 : 0, userId, Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)));
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("name", name); shown.put("type", type); shown.put("summary", summary); shown.put("parent", parent); shown.put("glOnly", glOnly);
        audit(auth, "chart.account.create", branch, code, null, shown, why);
    }

    @PostMapping
    @Transactional
    public AccountRow create(@RequestBody Form in, Authentication auth) {
        if (in == null) throw bad("Nothing to save.");
        int branch = branch(auth);
        String why = reason(in.reason());
        String code = in.code() == null ? "" : in.code().trim();
        if (!CODE.matcher(code).matches()) throw bad("The account number is digits only, up to 10, and starts with 1 to 6.");
        int type = code.charAt(0) - '0';
        if (type > 6) throw bad("The account number must start with 1 to 6: 1 assets, 2 liabilities, 3 equity, 4 revenues, 5 expenses, 6 net income.");
        String name = name(in.name());
        AccountRow parent = checkParent(branch, code, in.parent());
        if (parent.type() != type) throw bad("The account number starts with " + type + " but " + parent.code() + " is a " + parent.typeName().toLowerCase() + " account.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = ? AND COAID = ?", Integer.class, branch, code) > 0) throw bad("Account " + code + " already exists.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = ? AND COAacct_sum = ? AND LOWER(COADesc) = ?", Integer.class, branch, parent.code(), name.toLowerCase()) > 0)
            throw bad("There is already an account called " + name + " under " + parent.code() + ".");
        boolean summary = Boolean.TRUE.equals(in.summary());
        // a detail account is posted to from the general ledger unless it is controlled by a subsidiary ledger; a summary account never takes postings
        int glOnly = summary ? 0 : (Boolean.FALSE.equals(in.glOnly()) ? 0 : 1);
        insert(branch, code, name, summary, parent.code(), glOnly == 1, userId(auth), auth, why);
        return find(branch, code);
    }

    @PutMapping("/{code}")
    @Transactional
    public AccountRow update(@PathVariable String code, @RequestBody Form in, Authentication auth) {
        if (in == null) throw bad("Nothing to save.");
        int branch = branch(auth);
        AccountRow now = find(branch, code);
        String why = reason(in.reason());
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> next = new LinkedHashMap<>();
        Map<String, Object> set = new LinkedHashMap<>();

        String name = name(in.name() == null ? now.name() : in.name());
        if (!name.equals(now.name())) {
            was.put("name", now.name()); next.put("name", name); set.put("COADesc", name);
        }
        String parent = in.parent() == null || in.parent().isBlank() ? now.parent() : in.parent().trim();
        if (parent != null && !parent.equals(now.parent())) {
            AccountRow p = checkParent(branch, code, parent);
            if (p.type() != now.type()) throw bad("An account stays in its own type.");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ?", Long.class, branch, code) > 0)
                throw bad("This account already has ledger entries, so it cannot be moved to another summary account.");
            was.put("parent", now.parent()); next.put("parent", parent); set.put("COAacct_sum", parent);
        }
        if (!now.summary() && in.glOnly() != null && in.glOnly() != now.glOnly()) {
            was.put("glOnly", now.glOnly()); next.put("glOnly", in.glOnly()); set.put("COAcanGLOnly", in.glOnly() ? 1 : 0);
        }
        if (in.disabled() != null && in.disabled() != now.disabled()) {
            if (in.disabled()) {
                List<String> by = usedBy(branch, code);
                if (!by.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Account " + code + " cannot be switched off: it is " + String.join(" and ", by) + ".");
                if (now.summary() && jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = ? AND COAacct_sum = ? AND COALESCE(COAisdisabled, 0) = 0", Integer.class, branch, code) > 0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Switch off the accounts under " + code + " first.");
            } else if (now.parent() != null) {
                AccountRow p = find(branch, now.parent());
                if (p.disabled()) throw bad("Its summary account " + p.code() + " is switched off. Switch that on first.");
            }
            was.put("disabled", now.disabled()); next.put("disabled", in.disabled()); set.put("COAisdisabled", in.disabled() ? 1 : 0);
        }
        if (set.isEmpty()) return now;
        set.put("COAUpdUser", userId(auth));
        set.put("COAupdDateTime", Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)));
        String assign = String.join(", ", set.keySet().stream().map((k) -> k + " = ?").toList());
        List<Object> args = new java.util.ArrayList<>(set.values());
        args.add(branch);
        args.add(code);
        jdbc.update("UPDATE coa SET " + assign + " WHERE COABR_CODE = ? AND COAID = ?", args.toArray());
        boolean onlySwitch = next.size() == 1 && next.containsKey("disabled");
        audit(auth, onlySwitch ? (Boolean.TRUE.equals(in.disabled()) ? "chart.account.disable" : "chart.account.enable") : "chart.account.update", branch, code, was, next, why);
        return find(branch, code);
    }
}
