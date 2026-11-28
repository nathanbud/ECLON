package ph.coop.core.loan;

import static ph.coop.core.web.ApiErrors.bad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ph.coop.core.loan.LoanDeductionRules.Form;

/**
 * Loan Setup > a loan type > Deductions: the lines a loan application of that type starts with (rules in {@link LoanDeductionRules}). An administrator adds a line, changes its
 * description, order and where it goes, hides it from the printed slip, or removes it. Applications already saved keep their own copy of the lines, so nothing here changes them.
 * Every change needs a reason and is written to the audit log with the values before and after.
 */
@RestController
@RequestMapping("/api/admin/loan-setup/types/{code}/deductions")
@PreAuthorize("hasRole('ADMIN')")
public class LoanDeductionSetupController {

    private static final int LOAN_CLASS = LoanSetupController.LOAN_CLASS;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    LoanDeductionSetupController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** {@code target} says in words where the amount goes. {@code editableTarget} is false for the net proceeds line and for kinds this screen does not set. */
    public record Line(long id, String description, int order, String kind, Integer classId, Integer typeCode, Integer event, String account, String target, String formula,
                       boolean hidePrinting, boolean editableTarget, boolean removable) {}

    public record MemberType(int classId, int typeCode, String name) {}

    public record Charge(int event, String name) {}

    public record Options(List<MemberType> memberTypes, List<Charge> charges) {}

    private int branch(Authentication auth) {
        List<Integer> r = jdbc.queryForList("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1", Integer.class, auth.getName());
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are signed in with an account that is not in the cooperative's staff list, so this cannot be done with it. Sign in with your own staff account, or ask an administrator to add you in Settings > Users.");
        return r.get(0);
    }

    private void requireType(int branch, int code) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", Integer.class, branch, LOAN_CLASS, code) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such loan type.");
        }
    }

    private static String reason(String r) {
        if (r == null || r.isBlank()) throw bad("Give the reason for this change.");
        String t = r.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    private void audit(Authentication auth, String action, int branch, int code, Object prev, Object next, String why) {
        AuditLog.write(jdbc, auth.getName(), action, "loan_type", branch + "/" + LOAN_CLASS + "/" + code, json(prev), json(next), why);
    }

    private static String json(Object o) {
        if (o == null) return null;
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    private static Integer intOf(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? null : ((Number) o).intValue();
    }

    private List<Map<String, Object>> rows(int branch, int code) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM loansldeduc WHERE BR_CODE = ? AND SLC_CODE = ? AND SLT_CODE = ? ORDER BY DEDUCORDER, ID", branch, LOAN_CLASS, code)) {
            Map<String, Object> m = new LinkedCaseInsensitiveMap<>();
            m.putAll(r);
            out.add(m);
        }
        return out;
    }

    private Line line(int branch, Map<String, Object> r) {
        Integer behavior = intOf(r, "SLBEHAVIOR"), cls = intOf(r, "DSLC_CODE"), type = intOf(r, "DSLT_CODE"), event = intOf(r, "DSLE_CODE");
        String account = (String) r.get("DACCT_CODE");
        if (account != null && account.isBlank()) account = null;
        String kind = LoanDeductionRules.kind(behavior, cls, event, account);
        String target = switch (kind) {
            case LoanDeductionRules.NET_PROCEEDS -> "Net proceeds: what the member receives";
            case LoanDeductionRules.MEMBER_ACCOUNT -> {
                List<String> n = jdbc.queryForList("SELECT SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", String.class, branch, cls, type);
                yield "The member's " + (n.isEmpty() || n.get(0) == null ? "account " + cls + "/" + type : n.get(0));
            }
            case LoanDeductionRules.LOAN_CHARGE -> "This loan: " + LoanSetupController.eventRole(event).toLowerCase();
            case LoanDeductionRules.CHART_ACCOUNT -> {
                List<String> n = jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, branch, account);
                yield "Chart account " + account + (n.isEmpty() || n.get(0) == null ? "" : " " + n.get(0));
            }
            default -> "Kept as the old system set it";
        };
        Integer order = intOf(r, "DEDUCORDER");
        boolean settable = !kind.equals(LoanDeductionRules.NET_PROCEEDS) && !kind.equals(LoanDeductionRules.OTHER);
        return new Line(((Number) r.get("ID")).longValue(), (String) r.get("DESCR"), order == null ? 1 : order, kind, cls, type, event, account, target, (String) r.get("FORMULA"),
            intOf(r, "IsHidePrinting") != null && intOf(r, "IsHidePrinting") != 0, settable, !kind.equals(LoanDeductionRules.NET_PROCEEDS));
    }

    @GetMapping
    public List<Line> list(@PathVariable int code, Authentication auth) {
        int branch = branch(auth);
        requireType(branch, code);
        return rows(branch, code).stream().map((r) -> line(branch, r)).toList();
    }

    /** What a line may go to: the member account types that can be posted to, and the charges this loan type has a ledger link for. */
    @GetMapping("/options")
    public Options options(@PathVariable int code, Authentication auth) {
        int branch = branch(auth);
        requireType(branch, code);
        return options(code, branch);
    }

    /** Checks the description and order, and that no other line of the type has the same description. */
    private Map<String, Object> basics(int branch, int code, Form f, Long exceptId, boolean net) {
        Map<String, Object> c = new LinkedHashMap<>();
        try {
            c.put("DESCR", LoanDeductionRules.description(f.description()));
            if (!net) c.put("DEDUCORDER", LoanDeductionRules.order(f.order()));
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        String name = (String) c.get("DESCR");
        for (Map<String, Object> r : rows(branch, code)) {
            if (((Number) r.get("ID")).longValue() != (exceptId == null ? -1 : exceptId) && name.equalsIgnoreCase(String.valueOf(r.get("DESCR")).trim())) throw bad("This loan type already has a deduction called " + name + ".");
        }
        c.put("IsHidePrinting", Boolean.TRUE.equals(f.hidePrinting()) ? 1 : 0);
        return c;
    }

    /** Checks that what the line points at exists and can be posted to, and returns the columns for it. */
    private Map<String, Object> target(int branch, int code, Form f) {
        Map<String, Object> c;
        try {
            c = LoanDeductionRules.target(f);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        Options o = options(code, branch);
        switch (f.kind()) {
            case LoanDeductionRules.MEMBER_ACCOUNT -> {
                if (o.memberTypes().stream().noneMatch((t) -> t.classId() == f.classId() && t.typeCode() == f.typeCode())) throw bad("That kind of member account does not exist, is switched off or has no ledger account.");
            }
            case LoanDeductionRules.LOAN_CHARGE -> {
                if (o.charges().stream().noneMatch((t) -> t.event() == f.event())) throw bad("This loan type has no ledger link for that charge. Add it under COA Links first.");
            }
            default -> {
                String a = f.account().trim();
                List<Map<String, Object>> found = jdbc.queryForList("SELECT COAacct_tag AS tag, COALESCE(COAisdisabled, 0) AS off FROM coa WHERE COABR_CODE = ? AND COAID = ?", branch, a);
                if (found.isEmpty()) throw bad("Account " + a + " is not in the chart of accounts.");
                if (!"D".equals(found.get(0).get("tag"))) throw bad("Account " + a + " is a heading; choose an account that takes postings.");
                if (((Number) found.get(0).get("off")).intValue() != 0) throw bad("Account " + a + " is switched off.");
            }
        }
        return c;
    }

    private Options options(int code, int branch) {
        List<MemberType> types = jdbc.query("""
            SELECT s.SLTypeSLC_CODE, s.SLTypeSLT_CODE, s.SLTypeM_DESC FROM sltype s
            WHERE s.SLTypeBR_CODE = ? AND s.SLTypeSLC_CODE IN (22, 26, 29, 31) AND COALESCE(s.SLTypeIsDisabled, 0) = 0
              AND EXISTS (SELECT 1 FROM glcontrol g WHERE g.GLControlBR_CODE = s.SLTypeBR_CODE AND g.GLControlSLC_CODE = s.SLTypeSLC_CODE AND g.GLControlSLT_CODE = s.SLTypeSLT_CODE AND g.GLControlSLE_CODE = 11)
            ORDER BY s.SLTypeSLC_CODE, s.SLTypeSLT_CODE
            """, (rs, i) -> new MemberType(rs.getInt(1), rs.getInt(2), rs.getString(3)), branch);
        List<Charge> charges = jdbc.query("SELECT DISTINCT GLControlSLE_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE <> 11 ORDER BY 1",
            (rs, i) -> new Charge(rs.getInt(1), LoanSetupController.eventRole(rs.getInt(1))), branch, LOAN_CLASS, code);
        return new Options(types, charges);
    }

    private static Map<String, Object> shown(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("DESCR", "DEDUCORDER", "DSLC_CODE", "DSLT_CODE", "DSLE_CODE", "DACCT_CODE", "SLBEHAVIOR", "IsHidePrinting")) m.put(k, r.get(k));
        return m;
    }

    @PostMapping
    @Transactional
    public List<Line> add(@PathVariable int code, @RequestBody Form in, Authentication auth) {
        int branch = branch(auth);
        requireType(branch, code);
        String why = reason(in == null ? null : in.reason());
        Map<String, Object> c = basics(branch, code, in, null, false);
        c.putAll(target(branch, code, in));
        List<Map<String, Object>> existing = rows(branch, code);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("BR_CODE", branch); row.put("SLC_CODE", LOAN_CLASS); row.put("SLT_CODE", code);
        row.putAll(c);
        row.put("FORMULA", LoanDeductionRules.promptFormula((String) c.get("DESCR")));
        row.put("IsAutoLoad", 1);
        row.put("DeducGroup", existing.isEmpty() ? null : existing.get(0).get("DEDUCGROUP")); // the group the type's other lines carry
        String names = String.join(", ", row.keySet());
        String marks = String.join(", ", row.keySet().stream().map((k) -> "?").toList());
        jdbc.update("INSERT INTO loansldeduc (" + names + ") VALUES (" + marks + ")", row.values().toArray());
        audit(auth, "loan.type.deduction.add", branch, code, null, shown(c), why);
        return list(code, auth);
    }

    private Map<String, Object> find(int branch, int code, long id) {
        return rows(branch, code).stream().filter((r) -> ((Number) r.get("ID")).longValue() == id).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This loan type has no such deduction."));
    }

    /** Changes a line's description, order, whether it prints, and (for the kinds this screen sets) where it goes. Its formula is left as it is. */
    @PutMapping("/{id}")
    @Transactional
    public List<Line> change(@PathVariable int code, @PathVariable long id, @RequestBody Form in, Authentication auth) {
        int branch = branch(auth);
        requireType(branch, code);
        String why = reason(in == null ? null : in.reason());
        Map<String, Object> was = find(branch, code, id);
        Line now = line(branch, was);
        Map<String, Object> c = basics(branch, code, in, id, now.kind().equals(LoanDeductionRules.NET_PROCEEDS));
        if (now.editableTarget() && in.kind() != null) c.putAll(target(branch, code, in));
        Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : c.entrySet()) {
            Object old = was.get(e.getKey());
            if (!String.valueOf(old).equals(String.valueOf(e.getValue()))) { before.put(e.getKey(), old); after.put(e.getKey(), e.getValue()); }
        }
        if (after.isEmpty()) return list(code, auth);
        List<Object> args = new ArrayList<>(after.values());
        args.add(id);
        jdbc.update("UPDATE loansldeduc SET " + String.join(", ", after.keySet().stream().map((k) -> k + " = ?").toList()) + " WHERE ID = ?", args.toArray());
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("id", id); next.put("description", c.get("DESCR")); next.putAll(after);
        audit(auth, "loan.type.deduction.change", branch, code, before, next, why);
        return list(code, auth);
    }

    /** Removes a line from the type. The net proceeds line stays: every application ends with it. */
    @DeleteMapping("/{id}")
    @Transactional
    public List<Line> remove(@PathVariable int code, @PathVariable long id, @RequestParam String reason, Authentication auth) {
        int branch = branch(auth);
        requireType(branch, code);
        String why = reason(reason);
        Map<String, Object> was = find(branch, code, id);
        if (!line(branch, was).removable()) throw bad("The net proceeds line cannot be removed: every application ends with it.");
        jdbc.update("DELETE FROM loansldeduc WHERE ID = ?", id);
        Map<String, Object> prev = shown(was);
        prev.put("FORMULA", was.get("FORMULA"));
        audit(auth, "loan.type.deduction.remove", branch, code, prev, null, why);
        return list(code, auth);
    }
}
