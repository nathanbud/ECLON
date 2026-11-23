package ph.coop.core.accounting;

import static ph.coop.core.web.ApiErrors.bad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
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
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;

/**
 * Account types outside loans (the old system's S/L Type Table): the savings, time deposit, share capital, receivable and payable types and the chart accounts each one posts to.
 * Administrators see every class; they can rename a type, set its order and the settings its class has (interest rate, minimum and maximum balance, dormancy period), switch it
 * off, add a type as a copy of another of the same class (its ledger links are copied) or the first type of a class that has none, and add, repoint or remove a ledger link. A type
 * and its code are never deleted or renumbered, because accounts and posted entries point at them. Loan types are kept in Loan Setup and are shown read only. The cash class keeps its
 * fixed types, but its links can be pointed at another account and a cashier's or teller's drawer can be added. Every change needs a reason and is written to the audit log with the
 * values before and after.
 */
@RestController
@RequestMapping("/api/admin/account-types")
@PreAuthorize("hasRole('ADMIN')")
public class AccountTypeController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AccountTypeController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** {@code accounts} is how many accounts are of the type, or null where the class keeps no account table of its own. {@code maxBalance} is null for no limit. */
    public record TypeRow(int classId, int code, String name, String fullName, Integer priority, BigDecimal interestRate, BigDecimal minBalance, BigDecimal maxBalance,
                          Integer dormantAfterMonths, boolean disabled, Long accounts, int links) {}

    public record ClassRow(int id, String name, String managed, List<String> fields, List<TypeRow> types) {}

    public record Link(int event, String what, int number, String statusName, String account, String accountName, boolean accountActive, boolean removable) {}

    /** What the form sends. A setting the class does not have is ignored and left as it is; an empty rate or minimum balance is stored as empty. */
    public record Form(String name, String fullName, Integer priority, BigDecimal interestRate, BigDecimal minBalance, BigDecimal maxBalance, Integer dormantAfterMonths,
                       Boolean disabled, String reason) {
        public Form(String name, String fullName, Integer priority, BigDecimal interestRate, BigDecimal minBalance, Boolean disabled, String reason) {
            this(name, fullName, priority, interestRate, minBalance, null, null, disabled, reason);
        }
    }

    public record LinkIn(int event, int number, String account, String reason) {}

    /** The first type of a class that has none: its names and the account its balance posts to. */
    public record FirstType(String name, String fullName, String account, String reason) {}

    public record Option(int id, String name) {}

    public record LinkOptions(List<Option> events, List<Option> statuses) {}

    public record Drawer(int event, String kind, int number, String account, String accountName, List<String> heldBy) {}

    /** accounts: for the first drawer of its kind, the chart account per cash type (1, 3) it posts to; later drawers take the other drawers' accounts. */
    public record DrawerIn(int event, int number, String reason, Map<Integer, String> accounts) {}
    /** A fixed link of the cash class (vault, cheques on hand) and the account it posts to, null when not linked yet. */
    public record CashFixed(int type, int event, String label, boolean required, String account, String accountName) {}
    /** What the teller window still needs of the cash class, in words; ready when nothing. */
    public record CashSetup(List<String> missing, List<Integer> types, List<CashFixed> fixed, boolean ready) {}
    /** accounts: "type/event" (1/50, 3/51, 97/51) to the chart account it posts to. */
    public record CashSetupIn(Map<String, String> accounts, String reason) {}

    private int branch(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1", Integer.class, auth.getName());
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are signed in with an account that is not in the cooperative's staff list, so this cannot be done with it. Sign in with your own staff account, or ask an administrator to add you in Settings > Users.");
        return r.get(0);
    }

    private Integer userId(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessID FROM access WHERE AccessUserName = ? LIMIT 1", Integer.class, auth.getName());
        return r.isEmpty() ? null : r.get(0);
    }

    private Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
    }

    private static String reason(String r) {
        if (r == null || r.isBlank()) throw bad("Give the reason for this change.");
        String t = r.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    private void audit(Authentication auth, String action, String id, Object prev, Object next, String why) {
        AuditLog.write(jdbc, auth.getName(), action, "account_type", id, json(prev), json(next), why);
    }

    private static String json(Object o) {
        if (o == null) return null;
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static final String SELECT = """
            SELECT s.SLTypeSLC_CODE, s.SLTypeSLT_CODE, s.SLTypeM_DESC, s.SLTypeM_DESC2, s.SLTypePriority, s.SLTypeINT_RATE, s.SLTypeMIN_BAL, COALESCE(s.SLTypeIsDisabled, 0),
                   (SELECT COUNT(*) FROM glcontrol g WHERE g.GLControlBR_CODE = s.SLTypeBR_CODE AND g.GLControlSLC_CODE = s.SLTypeSLC_CODE AND g.GLControlSLT_CODE = s.SLTypeSLT_CODE),
                   s.SLTypeMAXBAL, s.SLTypeDORM_TERM, s.SLTypeDORM_PERD
            FROM sltype s WHERE s.SLTypeBR_CODE = ?""";

    private static Integer intOf(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : ((Number) o).intValue();
    }

    private TypeRow row(java.sql.ResultSet rs, Map<Integer, Map<Integer, Long>> counts) throws java.sql.SQLException {
        int cls = rs.getInt(1);
        int code = rs.getInt(2);
        Map<Integer, Long> ofClass = counts.get(cls);
        BigDecimal max = rs.getBigDecimal(10);
        return new TypeRow(cls, code, rs.getString(3), rs.getString(4), intOf(rs, 5), rs.getBigDecimal(6), rs.getBigDecimal(7),
            max == null || max.compareTo(AccountTypeRules.NO_LIMIT) >= 0 ? null : max, AccountTypeRules.dormancyMonths(intOf(rs, 11), intOf(rs, 12)), rs.getInt(8) != 0,
            ofClass == null ? null : ofClass.getOrDefault(code, 0L), rs.getInt(9));
    }

    /** How many accounts there are of each type, for the classes that keep an account table. */
    private Map<Integer, Map<Integer, Long>> counts(int branch) {
        Map<Integer, Map<Integer, Long>> out = new LinkedHashMap<>();
        AccountTypeRules.ACCOUNT_TABLES.forEach((cls, t) -> {
            Map<Integer, Long> m = new LinkedHashMap<>();
            jdbc.query("SELECT " + t[3] + ", COUNT(*) FROM " + t[0] + " WHERE " + t[1] + " = ? AND " + t[2] + " = ? GROUP BY " + t[3], (rs) -> { m.put(rs.getInt(1), rs.getLong(2)); }, branch, cls);
            out.put(cls, m);
        });
        return out;
    }

    /** Every class with its types, in the order the old table shows them. */
    @GetMapping
    public List<ClassRow> list(Authentication auth) {
        int branch = branch(auth);
        Map<Integer, Map<Integer, Long>> counts = counts(branch);
        Map<Integer, List<TypeRow>> byClass = new LinkedHashMap<>();
        jdbc.query(SELECT + " ORDER BY s.SLTypeSLC_CODE, s.SLTypeSLT_CODE", (rs) -> { TypeRow t = row(rs, counts); byClass.computeIfAbsent(t.classId(), (k) -> new ArrayList<>()).add(t); }, branch);
        return jdbc.query("SELECT SLClassID, SLClassDesc FROM slclass ORDER BY SLClassID", (rs, i) -> {
            int id = rs.getInt(1);
            return new ClassRow(id, rs.getString(2), AccountTypeRules.managed(id), AccountTypeRules.fields(id), byClass.getOrDefault(id, List.of()));
        });
    }

    private TypeRow find(int branch, int cls, int code) {
        Map<Integer, Map<Integer, Long>> counts = counts(branch);
        List<TypeRow> r = jdbc.query(SELECT + " AND s.SLTypeSLC_CODE = ? AND s.SLTypeSLT_CODE = ?", (rs, i) -> row(rs, counts), branch, cls, code);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account type.");
        return r.get(0);
    }

    private Map<String, Object> columns(int branch, int cls, Form in, Integer exceptCode) {
        Map<String, Object> c;
        try {
            c = AccountTypeRules.columns(cls, in);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        String name = (String) c.get("SLTypeM_DESC");
        List<Integer> same = jdbc.queryForList("SELECT SLTypeSLT_CODE FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND LOWER(SLTypeM_DESC) = ?", Integer.class, branch, cls, name.toLowerCase());
        if (same.stream().anyMatch((x) -> !x.equals(exceptCode))) throw bad("Another type of this class is already called " + name + ".");
        return c;
    }

    /** The changed fields only, before and after. */
    private static Map<String, Map<String, Object>> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> was = new LinkedHashMap<>(), now = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : after.entrySet()) {
            Object old = before.get(e.getKey());
            Object val = e.getValue();
            if (old == null && e.getKey().equals("SLTypeIsDisabled")) old = 0; // an empty flag means in use
            boolean same = old == null || val == null ? old == val
                : old instanceof Number && val instanceof Number ? new BigDecimal(old.toString()).compareTo(new BigDecimal(val.toString())) == 0 : old.toString().equals(val.toString());
            if (!same) { was.put(e.getKey(), old); now.put(e.getKey(), val); }
        }
        return Map.of("was", was, "now", now);
    }

    @PutMapping("/{cls}/{code}")
    @Transactional
    public TypeRow update(@PathVariable int cls, @PathVariable int code, @RequestBody Form in, Authentication auth) {
        int branch = branch(auth);
        find(branch, cls, code);
        String why = reason(in == null ? null : in.reason());
        Map<String, Object> after = columns(branch, cls, in, code);
        Map<String, Object> before = new LinkedCaseInsensitiveMap<>();
        before.putAll(jdbc.queryForMap("SELECT * FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, cls, code));
        // a dormancy period that is not counted in months or years is not understood here, so it is left alone unless a new one is given
        if (after.containsKey("SLTypeDORM_TERM") && after.get("SLTypeDORM_TERM") == null && before.get("SLTypeDORM_TERM") != null
            && AccountTypeRules.dormancyMonths(((Number) before.get("SLTypeDORM_TERM")).intValue(), before.get("SLTypeDORM_PERD") == null ? null : ((Number) before.get("SLTypeDORM_PERD")).intValue()) == null) {
            after.remove("SLTypeDORM_TERM");
            after.remove("SLTypeDORM_PERD");
        }
        Map<String, Map<String, Object>> d = diff(before, after);
        Map<String, Object> changed = d.get("now");
        if (changed.isEmpty()) return find(branch, cls, code);
        if (Integer.valueOf(1).equals(changed.get("SLTypeIsDisabled"))) {
            long used = jdbc.queryForObject("SELECT COUNT(*) FROM branches WHERE BranchesID = ? AND REB_SLC_CODE = ? AND REB_SLT_CODE = ?", Long.class, branch, cls, code);
            if (used > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This type cannot be switched off: a branch setting pays rebates into it.");
        }
        StringBuilder set = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : changed.entrySet()) { set.append(set.isEmpty() ? "" : ", ").append(e.getKey()).append(" = ?"); args.add(e.getValue()); }
        set.append(", SLTypeUPDDATETIME = ?, SLTypeUpdUser = ?");
        args.add(now()); args.add(userId(auth));
        args.add(branch); args.add(cls); args.add(code);
        jdbc.update("UPDATE sltype SET " + set + " WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", args.toArray());
        boolean onlySwitch = changed.size() == 1 && changed.containsKey("SLTypeIsDisabled");
        audit(auth, onlySwitch ? (Boolean.TRUE.equals(in.disabled()) ? "account.type.disable" : "account.type.enable") : "account.type.update", branch + "/" + cls + "/" + code, d.get("was"), changed, why);
        return find(branch, cls, code);
    }

    /**
     * What points at a type, by what it is, for {@link AccountTypeRules#deleteProblems}. Every entry the old system or this application posted to a
     * member account has a journal line (transdt), so that and the per-account summaries stand for the 51-million-row subsidiary ledger, whose
     * index does not start with the type.
     */
    private Map<String, Long> uses(int branch, int cls, int code) {
        Map<String, Long> u = new LinkedHashMap<>();
        String[] t = AccountTypeRules.ACCOUNT_TABLES.get(cls);
        if (t != null) u.put("accounts", count("SELECT COUNT(*) FROM " + t[0] + " WHERE " + t[1] + " = ? AND " + t[2] + " = ? AND " + t[3] + " = ?", branch, cls, code));
        u.put("account summaries", count("SELECT COUNT(*) FROM slmastersum WHERE MBR_CODE = ? AND MSLC_CODE = ? AND MSLT_CODE = ?", branch, cls, code));
        u.put("ledger balances", count("SELECT COUNT(*) FROM slsum WHERE SL_BRCODE = ? AND SLC_CODE = ? AND SLT_CODE = ?", branch, cls, code));
        u.put("posted entries", count("SELECT COUNT(*) FROM transdt WHERE DTTR_BRCODE = ? AND DTSLC_CODE = ? AND DTSLT_CODE = ?", branch, cls, code));
        u.put("front office entries", count("SELECT COUNT(*) FROM transfo WHERE FOTR_BRCODE = ? AND FOSLC_CODE = ? AND FOSLT_CODE = ?", branch, cls, code));
        u.put("cheques", count("SELECT COUNT(*) FROM transck WHERE CKTR_BRCODE = ? AND CKSLC_CODE = ? AND CKSLT_CODE = ?", branch, cls, code));
        u.put("passbooks", count("SELECT COUNT(*) FROM passbk WHERE PASSBKBR_CODE = ? AND PASSBKSLC_CODE = ? AND PASSBKSLT_CODE = ?", branch, cls, code));
        u.put("holds", count("SELECT COUNT(*) FROM hold WHERE HBR_CODE = ? AND HSLC_CODE = ? AND HSLT_CODE = ?", branch, cls, code));
        u.put("hold settings", count("SELECT COUNT(*) FROM ecis_hold_setting WHERE branch_code = ? AND slc_code = ? AND slt_code = ?", branch, cls, code));
        u.put("joint account records", count("SELECT COUNT(*) FROM jointacct WHERE BR_CODE = ? AND SLC_CODE = ? AND SLT_CODE = ?", branch, cls, code));
        u.put("other subsidiary records", count("SELECT COUNT(*) FROM osl WHERE OSLBR_CODE = ? AND ((OSLSLC_CODE = ? AND OSLSLT_CODE = ?) OR (OSLLSLC_CODE = ? AND OSLLSLT_CODE = ?))", branch, cls, code, cls, code));
        u.put("time deposits that pay interest or roll over into it", count("SELECT COUNT(*) FROM td WHERE TDBR_CODE = ? AND ((TDAI_SLC_CODE = ? AND TDAI_SLT_CODE = ?) OR (TDRO_SLC_CODE = ? AND TDRO_SLT_CODE = ?))", branch, cls, code, cls, code));
        u.put("loan type deduction lines", count("SELECT COUNT(*) FROM loansldeduc WHERE BR_CODE = ? AND DSLC_CODE = ? AND DSLT_CODE = ?", branch, cls, code));
        u.put("loan application deductions", count("SELECT COUNT(*) FROM loandeduc WHERE BR_CODE = ? AND SLC_CODE = ? AND SLT_CODE = ?", branch, cls, code));
        u.put("loan applications paying into it", count("SELECT COUNT(*) FROM loanapp WHERE BR_CODE = ? AND NPSLC_CODE = ? AND NPSLT_CODE = ?", branch, cls, code));
        u.put("branch rebate settings", count("SELECT COUNT(*) FROM branches WHERE BranchesID = ? AND REB_SLC_CODE = ? AND REB_SLT_CODE = ?", branch, cls, code));
        return u;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    public record DeleteCheck(boolean deletable, List<String> problems, int links) {}

    /** Whether a type may be deleted, and why not, so the screen can say so before asking for a reason. */
    @GetMapping("/{cls}/{code}/delete-check")
    public DeleteCheck deleteCheck(@PathVariable int cls, @PathVariable int code, Authentication auth) {
        int branch = branch(auth);
        TypeRow t = find(branch, cls, code);
        List<String> p = AccountTypeRules.deleteProblems(cls, uses(branch, cls, code));
        return new DeleteCheck(p.isEmpty(), p, t.links());
    }

    /**
     * Deletes a type that nothing ever used (a type added by mistake), with its ledger links. A type that was used is switched off instead, so the
     * accounts and entries that point at it can still be read. The whole row and its links go into the audit log, so it can be put back by hand.
     */
    @DeleteMapping("/{cls}/{code}")
    @Transactional
    public Map<String, Object> delete(@PathVariable int cls, @PathVariable int code, @RequestParam String reason, Authentication auth) {
        int branch = branch(auth);
        find(branch, cls, code);
        String why = reason(reason);
        jdbc.queryForList("SELECT 1 FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ? FOR UPDATE", branch, cls, code);
        List<String> p = AccountTypeRules.deleteProblems(cls, uses(branch, cls, code));
        if (!p.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This type cannot be deleted. " + String.join(" ", p));
        Map<String, Object> row = new LinkedCaseInsensitiveMap<>();
        row.putAll(jdbc.queryForMap("SELECT * FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, cls, code));
        List<Map<String, Object>> links = jdbc.queryForList("SELECT * FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? ORDER BY GLControlSLE_CODE, GLControlCTS_NUM", branch, cls, code);
        jdbc.update("DELETE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ?", branch, cls, code);
        jdbc.update("DELETE FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, cls, code);
        audit(auth, "account.type.delete", branch + "/" + cls + "/" + code, map("type", row, "links", links), null, why);
        return map("deleted", true, "name", row.get("SLTypeM_DESC"), "links", links.size());
    }

    private void insertType(Map<String, Object> row) {
        String names = String.join(", ", row.keySet());
        String marks = String.join(", ", row.keySet().stream().map((k) -> "?").toList());
        jdbc.update("INSERT INTO sltype (" + names + ") VALUES (" + marks + ")", row.values().toArray());
    }

    /** Adds a type as a copy of another of the same class, with the form's values on top. Its ledger links are copied, so it posts where the other one does until they are changed. */
    @PostMapping("/{cls}")
    @Transactional
    public TypeRow create(@PathVariable int cls, @RequestParam int copyFrom, @RequestBody Form in, Authentication auth) {
        int branch = branch(auth);
        String why = reason(in == null ? null : in.reason());
        Map<String, Object> cols = columns(branch, cls, in, null);
        find(branch, cls, copyFrom);
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(SLTypeSLT_CODE), 0) + 1 FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ?", Integer.class, branch, cls);
        if (next == null || next > 999) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no type code left in this class.");
        // case-insensitive copy: PostgreSQL names the columns in lowercase and the code puts them back in the old spelling
        Map<String, Object> row = new LinkedCaseInsensitiveMap<>();
        row.putAll(jdbc.queryForMap("SELECT * FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, cls, copyFrom));
        row.putAll(cols);
        row.put("SLTypeSLT_CODE", next);
        row.put("SLTypeUPDDATETIME", now());
        row.put("SLTypeUpdUser", userId(auth));
        insertType(row);
        jdbc.update("""
            INSERT INTO glcontrol (GLControlBR_CODE, GLControlSLC_CODE, GLControlSLT_CODE, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, Remarks, BAL_TYPE, LoanPayRepClass, PriorityRemittance)
            SELECT GLControlBR_CODE, GLControlSLC_CODE, ?, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, Remarks, BAL_TYPE, LoanPayRepClass, PriorityRemittance
            FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ?""", next, branch, cls, copyFrom);
        Map<String, Object> shown = new LinkedHashMap<>(cols);
        shown.put("copiedFrom", copyFrom);
        audit(auth, "account.type.create", branch + "/" + cls + "/" + next, null, shown, why);
        return find(branch, cls, next);
    }

    /**
     * Adds the first type of a class that has none, so there is nothing to copy: its names, and one ledger link for its balance on the class's first account status. Everything else
     * starts at the table's own defaults; the other links are added afterwards.
     */
    @PostMapping("/{cls}/first")
    @Transactional
    public TypeRow createFirst(@PathVariable int cls, @RequestBody FirstType in, Authentication auth) {
        int branch = branch(auth);
        String why = reason(in == null ? null : in.reason());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM slclass WHERE SLClassID = ?", Integer.class, cls) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such class.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ?", Integer.class, branch, cls) > 0) throw bad("This class already has types. Add one as a copy of another.");
        Map<String, Object> cols = columns(branch, cls, new Form(in.name(), in.fullName(), 99, null, null, false, why), null);
        List<Option> statuses = options(cls).statuses();
        if (statuses.isEmpty()) throw bad("This product cannot be added yet, so nothing was saved: this database is missing the system's standard list of account statuses (current, past due, closed and so on), which every product's balance is posted under. Ask your administrator to load the standard system lists on this database, then add the product again.");
        int kind = postable(branch, in.account());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("SLTypeBR_CODE", branch);
        row.put("SLTypeSLC_CODE", cls);
        row.put("SLTypeSLT_CODE", 1);
        row.put("SLTypeM_DESC", cols.get("SLTypeM_DESC"));
        row.put("SLTypeM_DESC2", cols.get("SLTypeM_DESC2"));
        row.put("SLTypePriority", 99);
        row.put("SLTypeIsDisabled", 0);
        row.put("SLTypeUPDDATETIME", now());
        row.put("SLTypeUpdUser", userId(auth));
        insertType(row);
        jdbc.update("INSERT INTO glcontrol (GLControlBR_CODE, GLControlSLC_CODE, GLControlSLT_CODE, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, BAL_TYPE) VALUES (?, ?, 1, 11, ?, ?, ?)",
            branch, cls, statuses.get(0).id(), in.account().trim(), AccountTypeRules.balanceSide(kind));
        audit(auth, "account.type.create", branch + "/" + cls + "/1", null, map("SLTypeM_DESC", cols.get("SLTypeM_DESC"), "SLTypeM_DESC2", cols.get("SLTypeM_DESC2"), "balanceAccount", in.account().trim()), why);
        return find(branch, cls, 1);
    }

    // ---- ledger links -------------------------------------------------------------------------------------------

    private static final String LINK_KEY = " WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = ? AND GLControlCTS_NUM = ?";

    /** How many posted or front office entries were written through a link; a link that carries entries cannot be removed. */
    private long entries(int branch, int cls, int code, int event, int number) {
        long posted = jdbc.queryForObject("SELECT COUNT(*) FROM transdt WHERE DTTR_BRCODE = ? AND DTSLC_CODE = ? AND DTSLT_CODE = ? AND DTSLE_CODE = ? AND DTCTS_NUM = ?", Long.class, branch, cls, code, event, number);
        return posted + jdbc.queryForObject("SELECT COUNT(*) FROM transfo WHERE FOTR_BRCODE = ? AND FOSLC_CODE = ? AND FOSLT_CODE = ? AND FOSLE_CODE = ? AND FOCTS_NUM = ?", Long.class, branch, cls, code, event, number);
    }

    /** The chart accounts a type posts to: its balance by account status, and whatever else its class posts (accrued interest, withholding tax and so on). */
    @GetMapping("/{cls}/{code}/accounts")
    public List<Link> links(@PathVariable int cls, @PathVariable int code, Authentication auth) {
        int branch = branch(auth);
        find(branch, cls, code);
        boolean edit = AccountTypeRules.EDIT.equals(AccountTypeRules.managed(cls));
        Map<Integer, String> statusNames = new LinkedHashMap<>();
        jdbc.query("SELECT CTS_TypeCode, CTS_TypeDesc FROM cts_type", (rs) -> { statusNames.put(rs.getInt(1), rs.getString(2)); });
        return jdbc.query("""
            SELECT g.GLControlSLE_CODE, g.GLControlCTS_NUM, g.GLControlACCT_CODE, c.COADesc, COALESCE(c.COAisdisabled, 0)
            FROM glcontrol g LEFT JOIN coa c ON c.COABR_CODE = g.GLControlBR_CODE AND c.COAID = g.GLControlACCT_CODE
            WHERE g.GLControlBR_CODE = ? AND g.GLControlSLC_CODE = ? AND g.GLControlSLT_CODE = ?
            ORDER BY g.GLControlSLE_CODE, g.GLControlCTS_NUM
            """, (rs, i) -> {
            int e = rs.getInt(1);
            int n = rs.getInt(2);
            String status = n == 99 ? null : AccountTypeRules.statusName(n) != null ? AccountTypeRules.statusName(n) : cls == AccountTypeRules.CASH ? "No. " + n : statusNames.get(n);
            return new Link(e, AccountTypeRules.eventName(cls, e), n, status, rs.getString(3), rs.getString(4), rs.getInt(5) == 0, edit);
        }, branch, cls, code);
    }

    /** The postings and account statuses a link of this class may be for, from the old lookup tables. */
    @GetMapping("/{cls}/link-options")
    public LinkOptions options(@PathVariable int cls) {
        List<Option> events = new ArrayList<>();
        jdbc.query("SELECT SLE_TypeCode, SLE_TypeActive FROM sle_type ORDER BY SLE_TypeCode", (rs) -> {
            int e = rs.getInt(1);
            if (AccountTypeRules.appliesTo(rs.getString(2), cls) && events.stream().noneMatch((o) -> o.id() == e)) events.add(new Option(e, AccountTypeRules.eventName(cls, e)));
        });
        List<Option> statuses = new ArrayList<>();
        jdbc.query("SELECT CTS_TypeCode, CTS_TypeDesc, CTS_TypeActive FROM cts_type ORDER BY CTS_TypeCode", (rs) -> {
            int n = rs.getInt(1);
            if (n != 99 && AccountTypeRules.appliesTo(rs.getString(3), cls)) statuses.add(new Option(n, AccountTypeRules.statusName(n) != null ? AccountTypeRules.statusName(n) : rs.getString(2)));
        });
        return new LinkOptions(events, statuses);
    }

    /**
     * Chart accounts to suggest for a ledger link, best first, each with why (rules in {@link LinkSuggestions}): what the class's other types use for the same posting, what this
     * type uses for its other account statuses, then accounts of the right kind whose names match. {@code type} is left out for a type not made yet (the first of a class), and
     * {@code name} gives the name typed so far. Suggests only; nothing is saved.
     */
    @GetMapping("/{cls}/link-suggestions")
    public List<LinkSuggestions.Suggestion> suggestions(@PathVariable int cls, @RequestParam int event, @RequestParam(defaultValue = "99") int number,
                                                        @RequestParam(required = false) Integer type, @RequestParam(required = false) String name, Authentication auth) {
        int branch = branch(auth);
        String typeName = name;
        if (type != null) {
            List<String> names = jdbc.queryForList("SELECT CONCAT_WS(' ', SLTypeM_DESC2, SLTypeM_DESC) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?",
                String.class, branch, cls, type);
            if (names.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account type.");
            if (typeName == null || typeName.isBlank()) typeName = names.get(0);
        }
        int self = type == null ? -1 : type;
        List<LinkSuggestions.Peer> peers = jdbc.query("""
            SELECT g.GLControlACCT_CODE, s.SLTypeM_DESC FROM glcontrol g
            JOIN sltype s ON s.SLTypeBR_CODE = g.GLControlBR_CODE AND s.SLTypeSLC_CODE = g.GLControlSLC_CODE AND s.SLTypeSLT_CODE = g.GLControlSLT_CODE
            WHERE g.GLControlBR_CODE = ? AND g.GLControlSLC_CODE = ? AND g.GLControlSLE_CODE = ? AND g.GLControlCTS_NUM = ? AND g.GLControlSLT_CODE <> ?
            ORDER BY g.GLControlSLT_CODE
            """, (rs, i) -> new LinkSuggestions.Peer(rs.getString(1), rs.getString(2)), branch, cls, event, number, self);
        List<String> own = type == null ? List.of() : jdbc.queryForList("""
            SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = ? AND GLControlCTS_NUM <> ?
            ORDER BY GLControlCTS_NUM
            """, String.class, branch, cls, type, event, number);
        List<LinkSuggestions.Account> chart = jdbc.query("""
            SELECT COAID, COADesc, COALESCE(COAacct_type, 0) FROM coa WHERE COABR_CODE = ? AND COAacct_tag = 'D' AND COALESCE(COAisdisabled, 0) = 0 ORDER BY COAID
            """, (rs, i) -> new LinkSuggestions.Account(rs.getString(1), rs.getString(2), rs.getInt(3)), branch);
        List<String> className = jdbc.queryForList("SELECT SLClassDesc FROM slclass WHERE SLClassID = ?", String.class, cls);
        return LinkSuggestions.suggest(cls, event, typeName, className.isEmpty() ? null : className.get(0), AccountTypeRules.eventName(cls, event), peers, own, chart);
    }

    /** The account must be in the chart, take postings and be in use. Returns its kind (1 assets to 6 net income). */
    private int postable(int branch, String account) {
        String a = account == null ? "" : account.trim();
        if (a.isEmpty()) throw bad("Give the account number.");
        List<Map<String, Object>> found = jdbc.queryForList("SELECT COAacct_tag AS tag, COALESCE(COAisdisabled, 0) AS off, COAacct_type AS kind FROM coa WHERE COABR_CODE = ? AND COAID = ?", branch, a);
        if (found.isEmpty()) throw bad("Account " + a + " is not in the chart of accounts.");
        if (!"D".equals(found.get(0).get("tag"))) throw bad("Account " + a + " is a heading; choose an account that takes postings.");
        if (((Number) found.get(0).get("off")).intValue() != 0) throw bad("Account " + a + " is switched off.");
        return found.get(0).get("kind") == null ? 0 : ((Number) found.get(0).get("kind")).intValue();
    }

    private void requireLinkEditing(int cls, boolean cashToo) {
        String m = AccountTypeRules.managed(cls);
        if (AccountTypeRules.EDIT.equals(m) || (cashToo && AccountTypeRules.CASH_SETUP.equals(m))) return;
        throw bad(cls == AccountTypeRules.LOANS ? "Loan types are changed in Loan Setup." : "The links of this class cannot be changed that way here.");
    }

    /**
     * Points a ledger link at another account. The account must be a detail account that is in use and of the same kind (asset, liability, equity and so on) as the one it replaces,
     * so a deposit balance cannot be pointed at an income account by mistake. Entries already posted keep the account they were posted to.
     */
    @PutMapping("/{cls}/{code}/accounts")
    @Transactional
    public List<Link> changeLink(@PathVariable int cls, @PathVariable int code, @RequestBody LinkIn in, Authentication auth) {
        int branch = branch(auth);
        find(branch, cls, code);
        requireLinkEditing(cls, true);
        String why = reason(in == null ? null : in.reason());
        String account = in.account() == null ? "" : in.account().trim();
        if (account.isEmpty()) throw bad("Give the account number.");
        List<String> was = jdbc.queryForList("SELECT GLControlACCT_CODE FROM glcontrol" + LINK_KEY, String.class, branch, cls, code, in.event(), in.number());
        if (was.isEmpty()) throw bad("This type has no such ledger link.");
        if (account.equals(was.get(0))) throw bad("That is already the linked account.");
        int kind = postable(branch, account);
        List<Integer> old = jdbc.queryForList("SELECT COAacct_type FROM coa WHERE COABR_CODE = ? AND COAID = ?", Integer.class, branch, was.get(0));
        if (!old.isEmpty() && old.get(0) != null && kind != 0 && old.get(0) != kind) {
            throw bad("Account " + account + " is not the same kind of account as " + was.get(0) + " (asset, liability, equity, revenue or expense). Choose one of the same kind.");
        }
        jdbc.update("UPDATE glcontrol SET GLControlACCT_CODE = ?" + LINK_KEY, account, branch, cls, code, in.event(), in.number());
        audit(auth, "account.type.link.change", branch + "/" + cls + "/" + code, map("event", in.event(), "number", in.number(), "account", was.get(0)),
            map("event", in.event(), "number", in.number(), "account", account), why);
        return links(cls, code, auth);
    }

    /**
     * Adds a ledger link the type does not have. The posting must be one the class uses; a balance link is for one account status, every other link is for all of them. The balance
     * side is taken from the same link of another type of the class, or from the kind of the account.
     */
    @PostMapping("/{cls}/{code}/accounts")
    @Transactional
    public List<Link> addLink(@PathVariable int cls, @PathVariable int code, @RequestBody LinkIn in, Authentication auth) {
        int branch = branch(auth);
        find(branch, cls, code);
        requireLinkEditing(cls, false);
        String why = reason(in == null ? null : in.reason());
        LinkOptions o = options(cls);
        if (o.events().stream().noneMatch((e) -> e.id() == in.event())) throw bad("This class does not use that kind of posting.");
        int number = 99;
        if (AccountTypeRules.BY_STATUS.contains(in.event())) {
            number = in.number();
            int n = number;
            if (o.statuses().stream().noneMatch((s) -> s.id() == n)) throw bad("Choose the account status the balance link is for.");
        }
        if (!jdbc.queryForList("SELECT 1 FROM glcontrol" + LINK_KEY, branch, cls, code, in.event(), number).isEmpty()) throw bad("This type already has that link. Change it instead.");
        int kind = postable(branch, in.account());
        List<Integer> side = jdbc.queryForList("SELECT BAL_TYPE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLE_CODE = ? AND BAL_TYPE IS NOT NULL GROUP BY BAL_TYPE ORDER BY COUNT(*) DESC LIMIT 1",
            Integer.class, branch, cls, in.event());
        jdbc.update("INSERT INTO glcontrol (GLControlBR_CODE, GLControlSLC_CODE, GLControlSLT_CODE, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, BAL_TYPE) VALUES (?, ?, ?, ?, ?, ?, ?)",
            branch, cls, code, in.event(), number, in.account().trim(), side.isEmpty() ? AccountTypeRules.balanceSide(kind) : side.get(0));
        audit(auth, "account.type.link.add", branch + "/" + cls + "/" + code, null, map("event", in.event(), "number", number, "account", in.account().trim()), why);
        return links(cls, code, auth);
    }

    /** Removes a ledger link nothing was ever posted through. The last balance link of a type stays: without it the type cannot be posted to at all. */
    @DeleteMapping("/{cls}/{code}/accounts")
    @Transactional
    public List<Link> removeLink(@PathVariable int cls, @PathVariable int code, @RequestParam int event, @RequestParam int number, @RequestParam String reason, Authentication auth) {
        int branch = branch(auth);
        find(branch, cls, code);
        requireLinkEditing(cls, false);
        String why = reason(reason);
        List<String> was = jdbc.queryForList("SELECT GLControlACCT_CODE FROM glcontrol" + LINK_KEY, String.class, branch, cls, code, event, number);
        if (was.isEmpty()) throw bad("This type has no such ledger link.");
        if (event == 11 && jdbc.queryForObject("SELECT COUNT(*) FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = 11", Integer.class, branch, cls, code) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This is the type's only balance link. Point it at another account instead of removing it.");
        }
        long used = entries(branch, cls, code, event, number);
        if (used > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This link cannot be removed: " + used + " entr" + (used == 1 ? "y was" : "ies were") + " posted through it. Point it at another account instead.");
        jdbc.update("DELETE FROM glcontrol" + LINK_KEY, branch, cls, code, event, number);
        audit(auth, "account.type.link.remove", branch + "/" + cls + "/" + code, map("event", event, "number", number, "account", was.get(0)), null, why);
        return links(cls, code, auth);
    }

    // ---- cash drawers -------------------------------------------------------------------------------------------

    private static final int CASH_ON_HAND = 1;
    private static final int CASHIER = 52;
    private static final int TELLER = 53;

    /** The cashiers' and tellers' drawers the ledger knows (links of the cash type by drawer number), and the people who hold each number. */
    @GetMapping("/cash/drawers")
    public List<Drawer> drawers(Authentication auth) {
        int branch = branch(auth);
        Map<Integer, List<String>> tellers = new LinkedHashMap<>(), cashiers = new LinkedHashMap<>();
        jdbc.query("""
            SELECT AccessTellerNumber, COALESCE(AccessIsHeadCashier, 0), CONCAT_WS(' ', AccessFName, AccessLName) FROM access
            WHERE AccessBR_CODE = ? AND AccessTellerNumber > 0 AND COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessLName, AccessFName
            """, (rs) -> { (rs.getInt(2) != 0 ? cashiers : tellers).computeIfAbsent(rs.getInt(1), (k) -> new ArrayList<>()).add(rs.getString(3)); }, branch);
        return jdbc.query("""
            SELECT g.GLControlSLE_CODE, g.GLControlCTS_NUM, g.GLControlACCT_CODE, c.COADesc
            FROM glcontrol g LEFT JOIN coa c ON c.COABR_CODE = g.GLControlBR_CODE AND c.COAID = g.GLControlACCT_CODE
            WHERE g.GLControlBR_CODE = ? AND g.GLControlSLC_CODE = ? AND g.GLControlSLT_CODE = ? AND g.GLControlSLE_CODE IN (52, 53)
            ORDER BY g.GLControlSLE_CODE, g.GLControlCTS_NUM
            """, (rs, i) -> {
            int e = rs.getInt(1);
            int n = rs.getInt(2);
            return new Drawer(e, e == CASHIER ? "Cashier" : "Teller", n, rs.getString(3), rs.getString(4), (e == CASHIER ? cashiers : tellers).getOrDefault(n, List.of()));
        }, branch, AccountTypeRules.CASH, CASH_ON_HAND);
    }

    private List<Integer> cashTypes(int branch) {
        return jdbc.queryForList("SELECT SLTypeSLT_CODE FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? ORDER BY 1", Integer.class, branch, AccountTypeRules.CASH);
    }

    private CashSetup cashSetup(int branch) {
        List<Integer> typeList = cashTypes(branch);
        java.util.Set<Integer> types = new java.util.HashSet<>(typeList);
        java.util.Set<String> links = new java.util.HashSet<>();
        List<CashFixed> fixed = new ArrayList<>();
        for (CashSetupRules.Fixed f : CashSetupRules.FIXED) {
            List<Map<String, Object>> l = jdbc.queryForList("""
                SELECT g.GLControlACCT_CODE AS account, c.COADesc AS name FROM glcontrol g LEFT JOIN coa c ON c.COABR_CODE = g.GLControlBR_CODE AND c.COAID = g.GLControlACCT_CODE
                WHERE g.GLControlBR_CODE = ? AND g.GLControlSLC_CODE = ? AND g.GLControlSLT_CODE = ? AND g.GLControlSLE_CODE = ? AND g.GLControlCTS_NUM = ?
                ORDER BY g.GLControlACCT_CODE
                """, branch, AccountTypeRules.CASH, f.type(), f.event(), CashSetupRules.NOT_PER_DRAWER);
            if (!l.isEmpty()) links.add(f.type() + "/" + f.event());
            fixed.add(new CashFixed(f.type(), f.event(), f.label(), f.required(), l.isEmpty() ? null : (String) l.get(0).get("account"), l.isEmpty() ? null : (String) l.get(0).get("name")));
        }
        List<String> missing = CashSetupRules.missing(types, links);
        return new CashSetup(missing, typeList, fixed, missing.isEmpty());
    }

    /** Whether the cash class is set up for the teller window: its types and the vault and cheques-on-hand accounts. */
    @GetMapping("/cash/setup")
    public CashSetup cashSetup(Authentication auth) {
        return cashSetup(branch(auth));
    }

    /**
     * Sets up the cash class on a database that lacks it: creates the system's cash types that are missing (their numbers are fixed, the ledger writes them by number)
     * and links the vault and the cheques on hand to the chart accounts the cooperative chose. Only adds: a type or link already there is kept as it is.
     */
    @PostMapping("/cash/setup")
    @Transactional
    public CashSetup setUpCash(@RequestBody CashSetupIn in, Authentication auth) {
        int branch = branch(auth);
        String why = reason(in == null ? null : in.reason());
        Map<String, String> given = in.accounts() == null ? Map.of() : in.accounts();
        CashSetup now = cashSetup(branch);
        List<String> lacking = new ArrayList<>();
        for (CashFixed f : now.fixed()) {
            String a = given.get(f.type() + "/" + f.event());
            if (f.account() == null && f.required() && (a == null || a.isBlank())) lacking.add(f.label().toLowerCase());
        }
        if (!lacking.isEmpty()) throw bad("Choose the chart account for " + String.join(" and for ", lacking) + ", so the teller window knows where that money is kept. Nothing was saved.");
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (CashFixed f : now.fixed()) {
            String a = given.get(f.type() + "/" + f.event());
            if (f.account() == null && a != null && !a.isBlank()) kinds.put(f.type() + "/" + f.event(), postable(branch, a));
        }
        List<Object> made = new ArrayList<>();
        java.util.Set<Integer> have = new java.util.HashSet<>(now.types());
        for (var t : CashSetupRules.TYPES.entrySet()) {
            if (have.contains(t.getKey())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("SLTypeBR_CODE", branch);
            row.put("SLTypeSLC_CODE", AccountTypeRules.CASH);
            row.put("SLTypeSLT_CODE", t.getKey());
            row.put("SLTypeM_DESC", t.getValue());
            row.put("SLTypeM_DESC2", t.getValue());
            row.put("SLTypePriority", 99);
            row.put("SLTypeIsDisabled", 0);
            row.put("SLTypeUPDDATETIME", now());
            row.put("SLTypeUpdUser", userId(auth));
            insertType(row);
            made.add(map("type", t.getKey(), "name", t.getValue()));
        }
        for (CashFixed f : now.fixed()) {
            String key = f.type() + "/" + f.event();
            if (f.account() != null || !kinds.containsKey(key)) continue;
            String a = given.get(key).trim();
            jdbc.update("INSERT INTO glcontrol (GLControlBR_CODE, GLControlSLC_CODE, GLControlSLT_CODE, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, BAL_TYPE) VALUES (?, ?, ?, ?, ?, ?, ?)",
                branch, AccountTypeRules.CASH, f.type(), f.event(), CashSetupRules.NOT_PER_DRAWER, a, AccountTypeRules.balanceSide(kinds.get(key)));
            made.add(map("link", key, "account", a));
        }
        if (made.isEmpty()) throw bad("The cash types and their accounts are already set up, so nothing needed adding. To point one at another account, change its link on the cash type below.");
        audit(auth, "cash.setup", branch + "/" + AccountTypeRules.CASH, null, map("added", made), why);
        return cashSetup(branch);
    }

    /**
     * Adds a cashier's or teller's drawer: the drawer number gets a ledger link on every cash type that keeps drawers of that kind (cash, cheques and other cash items, ATM), each
     * posting to the account the other drawers of that type use. The number is then given to a person in Users.
     */
    @PostMapping("/cash/drawers")
    @Transactional
    public List<Drawer> addDrawer(@RequestBody DrawerIn in, Authentication auth) {
        int branch = branch(auth);
        String why = reason(in == null ? null : in.reason());
        if (in.event() != CASHIER && in.event() != TELLER) throw bad("A drawer is a cashier's or a teller's.");
        List<String> known = jdbc.queryForList("SELECT CTS_TypeActive FROM cts_type WHERE CTS_TypeCode = ?", String.class, in.number());
        if (known.isEmpty() || in.number() == 99 || !AccountTypeRules.appliesTo(known.get(0), AccountTypeRules.CASH)) throw bad("That is not a drawer number the ledger knows (31 to 94).");
        if (!jdbc.queryForList("SELECT 1 FROM glcontrol" + LINK_KEY, branch, AccountTypeRules.CASH, CASH_ON_HAND, in.event(), in.number()).isEmpty()) throw bad("Drawer " + in.number() + " already exists.");
        java.util.Set<Integer> types = new java.util.HashSet<>(cashTypes(branch));
        java.util.Set<Integer> withKind = new java.util.HashSet<>(jdbc.queryForList(
            "SELECT DISTINCT GLControlSLT_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLE_CODE = ? ORDER BY 1", Integer.class, branch, AccountTypeRules.CASH, in.event()));
        // the first drawer of a kind has no other drawer to copy from: its chart accounts are the ones the cooperative chose
        List<String> lacking = CashSetupRules.drawerAccountsMissing(types, withKind, in.accounts());
        if (!lacking.isEmpty()) {
            throw bad("This is the first " + (in.event() == CASHIER ? "cashier's" : "teller's") + " drawer, so choose the chart account each part of it posts to: "
                + String.join(", ", lacking) + ". Nothing was saved.");
        }
        List<Map<String, Object>> made = new ArrayList<>();
        for (int type : CashSetupRules.DRAWER_TYPES) {
            if (!types.contains(type)) continue;
            if (!jdbc.queryForList("SELECT 1 FROM glcontrol" + LINK_KEY, branch, AccountTypeRules.CASH, type, in.event(), in.number()).isEmpty()) continue;
            List<Map<String, Object>> pattern = jdbc.queryForList("""
                SELECT GLControlACCT_CODE AS account, BAL_TYPE AS side FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = ?
                GROUP BY GLControlACCT_CODE, BAL_TYPE ORDER BY COUNT(*) DESC, GLControlACCT_CODE LIMIT 1
                """, branch, AccountTypeRules.CASH, type, in.event());
            String account;
            Object side;
            if (!pattern.isEmpty()) {
                account = (String) pattern.get(0).get("account");
                side = pattern.get(0).get("side");
            } else {
                String given = in.accounts() == null ? null : in.accounts().get(type);
                if (given == null || given.isBlank()) continue; // ATM items are optional
                account = given.trim();
                side = AccountTypeRules.balanceSide(postable(branch, account));
            }
            jdbc.update("INSERT INTO glcontrol (GLControlBR_CODE, GLControlSLC_CODE, GLControlSLT_CODE, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, BAL_TYPE) VALUES (?, ?, ?, ?, ?, ?, ?)",
                branch, AccountTypeRules.CASH, type, in.event(), in.number(), account, side);
            made.add(map("type", type, "account", account));
        }
        if (made.isEmpty()) throw bad("The cash types are not set up yet, so a drawer has nothing to hold. Press Set up the cash types first. Nothing was saved.");
        audit(auth, "cash.drawer.add", branch + "/" + AccountTypeRules.CASH + "/" + in.event() + "/" + in.number(), null, map("kind", in.event() == CASHIER ? "Cashier" : "Teller", "number", in.number(), "links", made), why);
        return drawers(auth);
    }
}
