package ph.coop.core.loan;



import static ph.coop.core.web.ApiErrors.bad;import ph.coop.core.audit.AuditLog;import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ph.coop.core.db.Db;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Loan setup for administrators: the loan types (sltype, class 12), the loan purposes and the collateral types.
 * <p>
 * Only people with ROLE_ADMIN (the legacy ADMIN and SUPERADMIN levels) may use it, and every change is written to the audit log with the values
 * before and after and the reason given. A loan type is never deleted, because loans and applications point at it; it is disabled instead, which
 * hides it from new applications. A new type is a copy of an existing one (its ledger accounts in glcontrol and its deductions in loansldeduc
 * come along), with the fields below changed. A change to a type affects new applications only: a loan keeps the setup it was made with.
 */
@RestController
@RequestMapping("/api/admin/loan-setup")
@PreAuthorize("hasRole('ADMIN')")
public class LoanSetupController {

    static final int LOAN_CLASS = 12;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Integer> YEAR_DAYS = Set.of(360, 365);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Db db;

    LoanSetupController(JdbcTemplate jdbc, Clock clock, Db db) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.db = db;
    }

    /** What an administrator sets on a loan type. Every other column of the type is left as it is. */
    public record TypeForm(String name, String fullName, Integer terms, Integer termUnit, Integer principalMode, Integer interestMode, Integer amortization,
                           BigDecimal interestRate, BigDecimal penaltyRate, BigDecimal serviceFeeRate, Integer minCoMakers, Boolean requireCollateral,
                           Integer priority, Integer yearDays, Boolean disabled, String reason, Integer dayCount) {
        /** Without the day count: it is left as it is. */
        public TypeForm(String name, String fullName, Integer terms, Integer termUnit, Integer principalMode, Integer interestMode, Integer amortization,
                        BigDecimal interestRate, BigDecimal penaltyRate, BigDecimal serviceFeeRate, Integer minCoMakers, Boolean requireCollateral,
                        Integer priority, Integer yearDays, Boolean disabled, String reason) {
            this(name, fullName, terms, termUnit, principalMode, interestMode, amortization, interestRate, penaltyRate, serviceFeeRate, minCoMakers, requireCollateral,
                priority, yearDays, disabled, reason, null);
        }
    }

    public record TypeRow(int code, String name, String fullName, Integer terms, Integer termUnit, Integer principalMode, Integer interestMode, Integer amortization,
                          BigDecimal interestRate, BigDecimal penaltyRate, BigDecimal serviceFeeRate, Integer minCoMakers, boolean requireCollateral,
                          Integer priority, Integer yearDays, boolean disabled, long loans, long applications, String unavailable, Integer dayCount) {}

    public record Option(int id, String name) {}

    public record Lists(List<Option> termUnits, List<Option> paymentModes, List<Option> amortizations, List<Option> loanClasses) {}

    public record NamedRow(int id, String name, Integer loanClass, long uses) {}

    public record Named(String name, Integer loanClass, String reason) {}


    private int branch(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1", Integer.class, auth.getName());
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not a staff account.");
        return r.get(0);
    }

    private Integer userId(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessID FROM access WHERE AccessUserName = ? LIMIT 1", Integer.class, auth.getName());
        return r.isEmpty() ? null : r.get(0);
    }

    private List<Option> options(String sql) {
        return jdbc.query(sql, (rs, i) -> new Option(rs.getInt(1), rs.getString(2)));
    }

    /** The lists the type form chooses from. */
    @GetMapping("/lists")
    public Lists lists() {
        return new Lists(options("SELECT TermID, TermDesc FROM term ORDER BY TermID"), options("SELECT PaymentModeID, PaymentModeDesc FROM paymentmode ORDER BY PaymentModeID"),
            options("SELECT AmorTypeID, AmorTypeDesc FROM amortype ORDER BY AmorTypeID"), options("SELECT LoanClassID, LoanClassDesc FROM loanclass ORDER BY LoanClassID"));
    }

    private static final String TYPE_SELECT = """
            SELECT s.SLTypeSLT_CODE, s.SLTypeM_DESC, s.SLTypeM_DESC2, s.SLTypeTERMS, s.SLTypeTERM_PERD, s.SLTypePPMT_MODE, s.SLTypeIPMT_MODE, s.SLTypeAMORTYPE,
                   s.SLTypeINT_RATE, s.SLTypePEN_RATE, s.SLTypeSF_RATE, s.SLTypeMINCOMKR, s.SLTypeRequireCollateral, s.SLTypePriority, s.SLTypeNDAYS_1YEAR_INTLOAN,
                   s.SLTypeIsDisabled,
                   (SELECT COUNT(*) FROM loan l WHERE l.LoanBR_CODE = s.SLTypeBR_CODE AND l.LoanSLC_CODE = s.SLTypeSLC_CODE AND l.LoanSLT_CODE = s.SLTypeSLT_CODE),
                   (SELECT COUNT(*) FROM loanapp a WHERE a.BR_CODE = s.SLTypeBR_CODE AND a.SLC_CODE = s.SLTypeSLC_CODE AND a.SLT_CODE = s.SLTypeSLT_CODE),
                   s.SLTypeND_FLAG
            FROM sltype s WHERE s.SLTypeBR_CODE = ? AND s.SLTypeSLC_CODE = ?""";

    private static Integer intOf(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : ((Number) o).intValue();
    }

    private TypeRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("SLTypeTERMS", rs.getObject(4)); d.put("SLTypeTERM_PERD", rs.getObject(5)); d.put("SLTypePPMT_MODE", rs.getObject(6));
        d.put("SLTypeAMORTYPE", rs.getObject(8)); d.put("SLTypeINT_RATE", rs.getBigDecimal(9));
        return new TypeRow(rs.getInt(1), rs.getString(2), rs.getString(3), intOf(rs, 4), intOf(rs, 5), intOf(rs, 6), intOf(rs, 7), intOf(rs, 8), rs.getBigDecimal(9),
            rs.getBigDecimal(10), rs.getBigDecimal(11), intOf(rs, 12), rs.getInt(13) != 0, intOf(rs, 14), intOf(rs, 15), rs.getInt(16) != 0, rs.getLong(17), rs.getLong(18),
            LoanApplicationController.unsupported(d), intOf(rs, 19));
    }

    /** Every loan type of the branch, disabled ones included, with how many loans and applications use it and whether the application screen can use it yet. */
    @GetMapping("/types")
    public List<TypeRow> types(Authentication auth) {
        return jdbc.query(TYPE_SELECT + " ORDER BY COALESCE(s.SLTypeIsDisabled, 0), s.SLTypeM_DESC", (rs, i) -> row(rs), branch(auth), LOAN_CLASS);
    }

    private TypeRow find(int branch, int code) {
        List<TypeRow> r = jdbc.query(TYPE_SELECT + " AND s.SLTypeSLT_CODE = ?", (rs, i) -> row(rs), branch, LOAN_CLASS, code);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such loan type.");
        return r.get(0);
    }

    /** Checks the form and returns the column values it sets, keyed by column name. Whatever the form leaves null is stored as empty. */
    Map<String, Object> columns(TypeForm f, int branch, Integer exceptCode) {
        if (f == null) throw bad("Nothing to save.");
        String name = f.name() == null ? "" : f.name().trim();
        if (name.isEmpty() || name.length() > 20) throw bad("The name is required, up to 20 characters.");
        String full = f.fullName() == null || f.fullName().isBlank() ? name : f.fullName().trim();
        if (full.length() > 30) throw bad("The full name can be up to 30 characters.");
        List<Integer> same = jdbc.queryForList("SELECT SLTypeSLT_CODE FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND LOWER(SLTypeM_DESC) = ?", Integer.class, branch, LOAN_CLASS, name.toLowerCase());
        if (same.stream().anyMatch((c) -> !c.equals(exceptCode))) throw bad("Another loan type is already called " + name + ".");
        if (f.terms() != null && (f.terms() < 1 || f.terms() > 600)) throw bad("The term must be 1 to 600.");
        if ((f.terms() == null) != (f.termUnit() == null)) throw bad("Give both the term and its unit, or neither.");
        checkRef("term", "TermID", f.termUnit(), "term unit");
        checkRef("paymentmode", "PaymentModeID", f.principalMode(), "principal payment mode");
        checkRef("paymentmode", "PaymentModeID", f.interestMode(), "interest payment mode");
        checkRef("amortype", "AmorTypeID", f.amortization(), "amortization");
        percent(f.interestRate(), "interest rate");
        percent(f.penaltyRate(), "penalty rate");
        percent(f.serviceFeeRate(), "service fee rate");
        if (f.minCoMakers() != null && (f.minCoMakers() < 0 || f.minCoMakers() > 20)) throw bad("The minimum number of co-makers is 0 to 20.");
        if (f.priority() != null && (f.priority() < 0 || f.priority() > 999)) throw bad("The order number is 0 to 999.");
        if (f.yearDays() != null && !YEAR_DAYS.contains(f.yearDays())) throw bad("Days in a year for interest is 360 or 365.");
        if (f.dayCount() != null && f.dayCount() != 1 && f.dayCount() != 2) throw bad("Interest days is 30 days a month or the real days.");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("SLTypeM_DESC", name);
        c.put("SLTypeM_DESC2", full);
        c.put("SLTypeTERMS", f.terms());
        c.put("SLTypeTERM_PERD", f.termUnit());
        c.put("SLTypePPMT_MODE", f.principalMode());
        c.put("SLTypeIPMT_MODE", f.interestMode());
        c.put("SLTypeAMORTYPE", f.amortization());
        c.put("SLTypeINT_RATE", f.interestRate());
        c.put("SLTypePEN_RATE", f.penaltyRate());
        c.put("SLTypeSF_RATE", f.serviceFeeRate());
        c.put("SLTypeMINCOMKR", f.minCoMakers());
        c.put("SLTypeRequireCollateral", Boolean.TRUE.equals(f.requireCollateral()) ? 1 : 0);
        c.put("SLTypePriority", f.priority());
        c.put("SLTypeNDAYS_1YEAR_INTLOAN", f.yearDays());
        c.put("SLTypeIsDisabled", Boolean.TRUE.equals(f.disabled()) ? 1 : 0);
        if (f.dayCount() != null) c.put("SLTypeND_FLAG", f.dayCount()); // left as it is when the form does not say
        return c;
    }

    private void checkRef(String table, String idColumn, Integer id, String what) {
        if (id == null) return;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?", Integer.class, id);
        if (n == null || n == 0) throw bad("No such " + what + ".");
    }

    private static void percent(BigDecimal v, String what) {
        if (v != null && (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0 || v.scale() > 2)) throw bad("The " + what + " is 0 to 100, with at most 2 decimals.");
    }

    private static String reason(String r) {
        if (r == null || r.isBlank()) throw bad("Give the reason for this change.");
        String t = r.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    private void audit(Authentication auth, String action, String entity, String id, Object prev, Object next, String reason) {
        AuditLog.write(jdbc, auth.getName(), action, entity, id, json(prev), json(next), reason);
    }

    private static String json(Object o) {
        if (o == null) return null;
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    /** The changed fields only, before and after. */
    private static Map<String, Map<String, Object>> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> was = new LinkedHashMap<>(), now = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : after.entrySet()) {
            Object old = before.get(e.getKey());
            boolean same = old == null ? e.getValue() == null
                : e.getValue() != null && (old instanceof Number && e.getValue() instanceof Number ? new BigDecimal(old.toString()).compareTo(new BigDecimal(e.getValue().toString())) == 0 : old.toString().equals(e.getValue().toString()));
            if (!same) { was.put(e.getKey(), old); now.put(e.getKey(), e.getValue()); }
        }
        return Map.of("was", was, "now", now);
    }

    /** One line of a loan type's ledger wiring: what the event is for, which loan status it applies to (principal only), and the chart account it posts to. */
    public record WiredAccount(int event, String what, Integer status, String statusName, String account, String accountName, boolean accountActive, String remarks, boolean removable) {}

    static String eventRole(int e) {
        return switch (e) {
            case 11 -> "Principal (loan balance)";
            case 21 -> "Accrued interest receivable";
            case 22 -> "Unearned interest";
            case 23 -> "Interest paid (income)";
            case 24 -> "Interest earned";
            case 25 -> "Interest waived";
            case 26 -> "Interest rebate";
            case 31 -> "Service fee";
            case 36 -> "Collection fee";
            case 37 -> "Insurance";
            case 41 -> "Penalty / fines";
            case 42 -> "Penalty waived";
            default -> "Event " + e;
        };
    }

    private static String statusName(int n) {
        return switch (n) {
            case 11 -> "Current";
            case 12 -> "Past due";
            case 13 -> "In litigation";
            case 14 -> "Restructured";
            default -> null;
        };
    }

    /** Where a loan type posts its principal, interest, penalty, fees and the rest in the chart of accounts (read only: the wiring is copied when a type is created). */
    @GetMapping("/types/{code}/accounts")
    public List<WiredAccount> accounts(@PathVariable int code, Authentication auth) {
        int branch = branch(auth);
        find(branch, code);
        return jdbc.query("""
            SELECT g.GLControlSLE_CODE, g.GLControlCTS_NUM, g.GLControlACCT_CODE, c.COADesc, COALESCE(c.COAisdisabled, 0), g.Remarks
            FROM glcontrol g LEFT JOIN coa c ON c.COABR_CODE = g.GLControlBR_CODE AND c.COAID = g.GLControlACCT_CODE
            WHERE g.GLControlBR_CODE = ? AND g.GLControlSLC_CODE = ? AND g.GLControlSLT_CODE = ?
            ORDER BY g.GLControlSLE_CODE, g.GLControlCTS_NUM
            """, (rs, i) -> {
            int e = rs.getInt(1);
            int n = rs.getInt(2);
            Integer st = e == 11 ? n : null;
            return new WiredAccount(e, eventRole(e), st, st == null ? null : statusName(n), rs.getString(3), rs.getString(4), rs.getInt(5) == 0, rs.getString(6), !REQUIRED_LINKS.contains(e));
        }, branch, LOAN_CLASS, code);
    }

    /** An add or change of one ledger link. For principal (event 11) {@code status} is the loan status 11 to 14; for every other event it is ignored. */
    public record LinkIn(int event, Integer status, String account, String reason) {}

    /** Events a loan type may be linked on. */
    private static final Set<Integer> LINK_EVENTS = Set.of(11, 21, 22, 23, 24, 25, 26, 31, 36, 37, 41, 42);

    /** Links every posting needs: principal, interest paid, service fee and penalty. They can be pointed at another account but never removed. */
    private static final Set<Integer> REQUIRED_LINKS = Set.of(11, 23, 31, 41);

    private static int linkNumber(int event, Integer status) {
        if (!LINK_EVENTS.contains(event)) throw bad("That kind of posting cannot be linked.");
        if (event != 11) return 99;
        if (status == null || status < 11 || status > 14) throw bad("Choose the loan status for principal: 11 current, 12 past due, 13 in litigation or 14 restructured.");
        return status;
    }

    /** The account must exist in the chart, be a detail account (not a heading) and be switched on. */
    private void requirePostable(int branch, String account) {
        if (account == null || account.isBlank()) throw bad("Give the account number.");
        List<Map<String, Object>> found = jdbc.queryForList("SELECT COAacct_tag AS tag, COALESCE(COAisdisabled, 0) AS off FROM coa WHERE COABR_CODE = ? AND COAID = ?", branch, account.trim());
        if (found.isEmpty()) throw bad("Account " + account.trim() + " is not in the chart of accounts.");
        if (!"D".equals(found.get(0).get("tag"))) throw bad("Account " + account.trim() + " is a heading account; choose a detail account.");
        if (((Number) found.get(0).get("off")).intValue() != 0) throw bad("Account " + account.trim() + " is switched off.");
    }

    private Map<String, Object> link(int branch, int type, int event, int number) {
        List<Map<String, Object>> found = jdbc.queryForList("SELECT GLControlACCT_CODE AS account FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = ? AND GLControlCTS_NUM = ?",
            branch, LOAN_CLASS, type, event, number);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Adds a ledger link that the loan type does not have. It copies the balance side and payment class that other loan types use for the same kind of posting. */
    @PostMapping("/types/{code}/accounts")
    @Transactional
    public List<WiredAccount> addLink(@PathVariable int code, @RequestBody LinkIn in, Authentication auth) {
        int branch = branch(auth);
        find(branch, code);
        String why = reason(in.reason());
        int number = linkNumber(in.event(), in.status());
        requirePostable(branch, in.account());
        if (link(branch, code, in.event(), number) != null) throw bad("This loan type already has that link. Change it instead.");
        List<Map<String, Object>> pattern = jdbc.queryForList("""
            SELECT BAL_TYPE, LoanPayRepClass FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLE_CODE = ? AND GLControlCTS_NUM = ?
            GROUP BY BAL_TYPE, LoanPayRepClass ORDER BY COUNT(*) DESC LIMIT 1
            """, branch, LOAN_CLASS, in.event(), number);
        if (pattern.isEmpty()) throw bad("No other loan type uses this kind of posting, so its settings cannot be copied.");
        jdbc.update("INSERT INTO glcontrol (GLControlBR_CODE, GLControlSLC_CODE, GLControlSLT_CODE, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, BAL_TYPE, LoanPayRepClass) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            branch, LOAN_CLASS, code, in.event(), number, in.account().trim(), pattern.get(0).get("BAL_TYPE"), pattern.get(0).get("LoanPayRepClass"));
        audit(auth, "loan.type.link.add", "loan_type", String.valueOf(code), null, Map.of("event", in.event(), "status", number, "account", in.account().trim()), why);
        return accounts(code, auth);
    }

    /** Points an existing link at another account. Entries already posted keep the account they were posted to. */
    @PutMapping("/types/{code}/accounts")
    @Transactional
    public List<WiredAccount> changeLink(@PathVariable int code, @RequestBody LinkIn in, Authentication auth) {
        int branch = branch(auth);
        find(branch, code);
        String why = reason(in.reason());
        int number = linkNumber(in.event(), in.status());
        requirePostable(branch, in.account());
        Map<String, Object> was = link(branch, code, in.event(), number);
        if (was == null) throw bad("This loan type has no such link. Add it instead.");
        if (in.account().trim().equals(was.get("account"))) throw bad("That is already the linked account.");
        jdbc.update("UPDATE glcontrol SET GLControlACCT_CODE = ? WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = ? AND GLControlCTS_NUM = ?",
            in.account().trim(), branch, LOAN_CLASS, code, in.event(), number);
        audit(auth, "loan.type.link.change", "loan_type", String.valueOf(code), Map.of("event", in.event(), "status", number, "account", was.get("account")),
            Map.of("event", in.event(), "status", number, "account", in.account().trim()), why);
        return accounts(code, auth);
    }

    /** Removes a link that is not needed for posting (accrued or unearned interest, interest earned, waived or rebated, collection fee, insurance, penalty waived). */
    @DeleteMapping("/types/{code}/accounts")
    @Transactional
    public List<WiredAccount> removeLink(@PathVariable int code, @org.springframework.web.bind.annotation.RequestParam int event,
                                         @org.springframework.web.bind.annotation.RequestParam(required = false) Integer status,
                                         @org.springframework.web.bind.annotation.RequestParam String reason, Authentication auth) {
        int branch = branch(auth);
        find(branch, code);
        String why = reason(reason);
        int number = linkNumber(event, status);
        if (REQUIRED_LINKS.contains(event)) throw bad("This link is needed to post loans of this type. Point it at another account instead of removing it.");
        Map<String, Object> was = link(branch, code, event, number);
        if (was == null) throw bad("This loan type has no such link.");
        jdbc.update("DELETE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = ? AND GLControlCTS_NUM = ?", branch, LOAN_CLASS, code, event, number);
        audit(auth, "loan.type.link.remove", "loan_type", String.valueOf(code), Map.of("event", event, "status", number, "account", was.get("account")), null, why);
        return accounts(code, auth);
    }

    @PutMapping("/types/{code}")
    @Transactional
    public TypeRow update(@PathVariable int code, @RequestBody TypeForm in, Authentication auth) {
        int branch = branch(auth);
        find(branch, code);
        String why = reason(in == null ? null : in.reason());
        Map<String, Object> after = columns(in, branch, code);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, LOAN_CLASS, code);
        Map<String, Map<String, Object>> d = diff(before, after);
        if (d.get("was").isEmpty()) return find(branch, code);
        StringBuilder set = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : after.entrySet()) { set.append(set.isEmpty() ? "" : ", ").append(e.getKey()).append(" = ?"); args.add(e.getValue()); }
        set.append(", SLTypeUPDDATETIME = ?, SLTypeUpdUser = ?");
        args.add(Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)));
        args.add(userId(auth));
        args.add(branch); args.add(LOAN_CLASS); args.add(code);
        jdbc.update("UPDATE sltype SET " + set + " WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", args.toArray());
        boolean toggled = d.get("now").containsKey("SLTypeIsDisabled");
        audit(auth, toggled && d.get("now").size() == 1 ? (Boolean.TRUE.equals(in.disabled()) ? "loan.type.disable" : "loan.type.enable") : "loan.type.update", "loan_type", branch + "/" + LOAN_CLASS + "/" + code, d.get("was"), d.get("now"), why);
        return find(branch, code);
    }

    /** Creates a loan type as a copy of {@code copyFrom}, with the form's values on top. */
    @PostMapping("/types")
    @Transactional
    public TypeRow create(@RequestBody TypeForm in, @org.springframework.web.bind.annotation.RequestParam int copyFrom, Authentication auth) {
        int branch = branch(auth);
        find(branch, copyFrom);
        String why = reason(in == null ? null : in.reason());
        Map<String, Object> cols = columns(in, branch, null);
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(SLTypeSLT_CODE), 0) + 1 FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ?", Integer.class, branch, LOAN_CLASS);
        if (next == null || next > 255) throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no loan type code left.");
        // case-insensitive copy: PostgreSQL names the columns in lowercase and the code puts them back in the legacy spelling
        Map<String, Object> row = new org.springframework.util.LinkedCaseInsensitiveMap<>();
        row.putAll(jdbc.queryForMap("SELECT * FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, LOAN_CLASS, copyFrom));
        row.putAll(cols);
        row.put("SLTypeSLT_CODE", next);
        row.put("SLTypeUPDDATETIME", Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)));
        row.put("SLTypeUpdUser", userId(auth));
        insert("sltype", row);
        jdbc.update("""
            INSERT INTO glcontrol (GLControlBR_CODE, GLControlSLC_CODE, GLControlSLT_CODE, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, Remarks, BAL_TYPE, LoanPayRepClass, PriorityRemittance)
            SELECT GLControlBR_CODE, GLControlSLC_CODE, ?, GLControlSLE_CODE, GLControlCTS_NUM, GLControlACCT_CODE, Remarks, BAL_TYPE, LoanPayRepClass, PriorityRemittance
            FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ?""", next, branch, LOAN_CLASS, copyFrom);
        for (Map<String, Object> d : jdbc.queryForList("SELECT * FROM loansldeduc WHERE BR_CODE = ? AND SLC_CODE = ? AND SLT_CODE = ?", branch, LOAN_CLASS, copyFrom)) {
            Map<String, Object> copy = new org.springframework.util.LinkedCaseInsensitiveMap<>();
            copy.putAll(d);
            copy.remove("ID");
            copy.put("SLT_CODE", next);
            insert("loansldeduc", copy);
        }
        Map<String, Object> shown = new LinkedHashMap<>(cols);
        shown.put("copiedFrom", copyFrom);
        audit(auth, "loan.type.create", "loan_type", branch + "/" + LOAN_CLASS + "/" + next, null, shown, why);
        return find(branch, next);
    }

    private void insert(String table, Map<String, Object> row) {
        String names = String.join(", ", row.keySet());
        String marks = String.join(", ", row.keySet().stream().map((k) -> "?").toList());
        jdbc.update("INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ")", row.values().toArray());
    }

    // ---- loan purposes and collateral types: a code, a description, and (for purposes) the loan class --------------------------------------

    @GetMapping("/purposes")
    public List<NamedRow> purposes() {
        return jdbc.query("""
            SELECT p.LoanPurposeID, p.LoanPurposeDesc, p.LoanPurposeType, (SELECT COUNT(*) FROM loan l WHERE l.LoanPURPOSE = p.LoanPurposeID) FROM loanpurpose p ORDER BY p.LoanPurposeDesc""",
            (rs, i) -> new NamedRow(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getLong(4)));
    }

    @GetMapping("/collateral-types")
    public List<NamedRow> collateralTypes() {
        return jdbc.query("""
            SELECT c.CollateralTypeID, c.CollateralTypeDesc, (SELECT COUNT(*) FROM loan l WHERE l.LoanCOLLTYPE = c.CollateralTypeID) FROM collateraltype c ORDER BY c.CollateralTypeID""",
            (rs, i) -> new NamedRow(rs.getInt(1), rs.getString(2), null, rs.getLong(3)));
    }

    private String description(Named in) {
        String n = in == null || in.name() == null ? "" : in.name().trim();
        if (n.isEmpty() || n.length() > 45) throw bad("The name is required, up to 45 characters.");
        return n;
    }

    @PostMapping("/purposes")
    @Transactional
    public NamedRow addPurpose(@RequestBody Named in, Authentication auth) {
        String name = description(in);
        String why = reason(in.reason());
        Integer cls = in.loanClass() == null ? 1 : in.loanClass();
        checkRef("loanclass", "LoanClassID", cls, "loan class");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM loanpurpose WHERE LOWER(LoanPurposeDesc) = ?", Integer.class, name.toLowerCase()) > 0) throw bad("There is already a purpose called " + name + ".");
        int id = (int) db.insertReturningId("INSERT INTO loanpurpose (LoanPurposeDesc, LoanPurposeType) VALUES (?, ?)", "LoanPurposeID", name, cls);
        audit(auth, "loan.purpose.create", "loan_purpose", String.valueOf(id), null, Map.of("name", name, "loanClass", cls), why);
        return new NamedRow(id, name, cls, 0);
    }

    @PutMapping("/purposes/{id}")
    @Transactional
    public NamedRow renamePurpose(@PathVariable int id, @RequestBody Named in, Authentication auth) {
        String name = description(in);
        String why = reason(in.reason());
        Map<String, Object> before = one("SELECT LoanPurposeDesc, LoanPurposeType FROM loanpurpose WHERE LoanPurposeID = ?", id, "No such loan purpose.");
        Integer cls = in.loanClass() == null ? ((Number) before.get("LoanPurposeType")).intValue() : in.loanClass();
        checkRef("loanclass", "LoanClassID", cls, "loan class");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM loanpurpose WHERE LOWER(LoanPurposeDesc) = ? AND LoanPurposeID <> ?", Integer.class, name.toLowerCase(), id) > 0) throw bad("There is already a purpose called " + name + ".");
        jdbc.update("UPDATE loanpurpose SET LoanPurposeDesc = ?, LoanPurposeType = ? WHERE LoanPurposeID = ?", name, cls, id);
        audit(auth, "loan.purpose.update", "loan_purpose", String.valueOf(id), Map.of("name", before.get("LoanPurposeDesc"), "loanClass", before.get("LoanPurposeType")), Map.of("name", name, "loanClass", cls), why);
        return purposes().stream().filter((p) -> p.id() == id).findFirst().orElseThrow();
    }

    @DeleteMapping("/purposes/{id}")
    @Transactional
    public void deletePurpose(@PathVariable int id, @org.springframework.web.bind.annotation.RequestParam String reason, Authentication auth) {
        String why = reason(reason);
        Map<String, Object> before = one("SELECT LoanPurposeDesc, LoanPurposeType FROM loanpurpose WHERE LoanPurposeID = ?", id, "No such loan purpose.");
        remove("DELETE FROM loanpurpose WHERE LoanPurposeID = ?", id, "This purpose is used by loans or other records, so it cannot be deleted.");
        audit(auth, "loan.purpose.delete", "loan_purpose", String.valueOf(id), Map.of("name", before.get("LoanPurposeDesc")), null, why);
    }

    @PostMapping("/collateral-types")
    @Transactional
    public NamedRow addCollateralType(@RequestBody Named in, Authentication auth) {
        String name = description(in);
        String why = reason(in.reason());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM collateraltype WHERE LOWER(CollateralTypeDesc) = ?", Integer.class, name.toLowerCase()) > 0) throw bad("There is already a collateral type called " + name + ".");
        // OLDID is the one-letter code of the old system; a new entry has none
        int id = (int) db.insertReturningId("INSERT INTO collateraltype (CollateralTypeDesc, CollateralTypeOLDID) VALUES (?, '')", "CollateralTypeID", name);
        audit(auth, "loan.collateral.create", "collateral_type", String.valueOf(id), null, Map.of("name", name), why);
        return new NamedRow(id, name, null, 0);
    }

    @PutMapping("/collateral-types/{id}")
    @Transactional
    public NamedRow renameCollateralType(@PathVariable int id, @RequestBody Named in, Authentication auth) {
        String name = description(in);
        String why = reason(in.reason());
        Map<String, Object> before = one("SELECT CollateralTypeDesc FROM collateraltype WHERE CollateralTypeID = ?", id, "No such collateral type.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM collateraltype WHERE LOWER(CollateralTypeDesc) = ? AND CollateralTypeID <> ?", Integer.class, name.toLowerCase(), id) > 0) throw bad("There is already a collateral type called " + name + ".");
        jdbc.update("UPDATE collateraltype SET CollateralTypeDesc = ? WHERE CollateralTypeID = ?", name, id);
        audit(auth, "loan.collateral.update", "collateral_type", String.valueOf(id), Map.of("name", before.get("CollateralTypeDesc")), Map.of("name", name), why);
        return collateralTypes().stream().filter((p) -> p.id() == id).findFirst().orElseThrow();
    }

    @DeleteMapping("/collateral-types/{id}")
    @Transactional
    public void deleteCollateralType(@PathVariable int id, @org.springframework.web.bind.annotation.RequestParam String reason, Authentication auth) {
        String why = reason(reason);
        Map<String, Object> before = one("SELECT CollateralTypeDesc FROM collateraltype WHERE CollateralTypeID = ?", id, "No such collateral type.");
        remove("DELETE FROM collateraltype WHERE CollateralTypeID = ?", id, "This collateral type is used by loans or other records, so it cannot be deleted.");
        audit(auth, "loan.collateral.delete", "collateral_type", String.valueOf(id), Map.of("name", before.get("CollateralTypeDesc")), null, why);
    }

    private Map<String, Object> one(String sql, int id, String missing) {
        List<Map<String, Object>> r = jdbc.queryForList(sql, id);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing);
        return r.get(0);
    }

    private void remove(String sql, int id, String inUse) {
        try {
            jdbc.update(sql, id);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, inUse);
        }
    }
}
