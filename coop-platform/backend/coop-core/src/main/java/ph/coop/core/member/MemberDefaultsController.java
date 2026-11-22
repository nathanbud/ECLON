package ph.coop.core.member;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;

/**
 * Member defaults (Settings > Membership > Member defaults): the department and office a new member is given when none is chosen, and the member types
 * that may be registered, for this cooperative ({@link MemberDefaults}). Everyone signed in reads what applies ({@code GET /api/member-defaults}, for the
 * registration screens); administrators see the choices, and only a super administrator changes them, with a reason, audited as
 * {@code membership.defaults.changed} per setting.
 */
@RestController
public class MemberDefaultsController {

    public record Choice(int code, String name, boolean disabled) {}

    /** What applies: null where nothing is chosen. */
    public record Applies(Integer department, String departmentName, Integer office, String officeName, List<Choice> types) {}

    public record Setup(boolean editable, Applies applies, List<Choice> departments, List<Choice> offices, List<Choice> memberTypes) {}

    public record Change(Integer department, Integer office, List<Integer> types, String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final MemberDefaults defaults;

    MemberDefaultsController(JdbcTemplate jdbc, Db db, MemberDefaults defaults) {
        this.jdbc = jdbc;
        this.db = db;
        this.defaults = defaults;
    }

    private List<Choice> departments() {
        return jdbc.query("SELECT DeptID, DeptDesc, COALESCE(DeptIsDisabled, 0) FROM dept ORDER BY DeptID",
            (rs, i) -> new Choice(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim(), rs.getInt(3) != 0));
    }

    private List<Choice> offices() {
        return jdbc.query("SELECT RestrikID, RestrikDesc FROM restrik ORDER BY RestrikID", (rs, i) -> new Choice(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim(), false));
    }

    private List<Choice> memberTypes() {
        List<Choice> out = new ArrayList<>();
        defaults.allTypes().forEach((code, name) -> out.add(new Choice(code, name, false)));
        return out;
    }

    private static String nameOf(List<Choice> list, Integer code) {
        if (code == null) return null;
        return list.stream().filter((c) -> c.code() == code).map(Choice::name).findFirst().orElse(null);
    }

    @GetMapping("/api/member-defaults")
    public Applies applies() {
        List<Choice> types = new ArrayList<>();
        defaults.types().forEach((code, name) -> types.add(new Choice(code, name, false)));
        Integer d = defaults.department();
        Integer o = defaults.office();
        return new Applies(d, nameOf(departments(), d), o, nameOf(offices(), o), types);
    }

    @GetMapping("/api/admin/member-defaults")
    @PreAuthorize("hasRole('ADMIN')")
    public Setup setup(Authentication auth) {
        boolean superadmin = auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_SUPERADMIN"));
        return new Setup(superadmin && defaults.hasSettings(), applies(), departments(), offices(), memberTypes());
    }

    @PutMapping("/api/admin/member-defaults")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @Transactional
    public Setup save(@RequestBody Change c, Authentication auth) {
        if (!db.allTablesExist("ecis_setting", "ecis_audit_log")) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("member defaults"));
        List<Integer> types = c.types() == null ? List.of() : c.types().stream().filter(Objects::nonNull).distinct().toList();
        List<String> p = MemberDefaultsRules.problems(c.department(), c.office(), types, departments(), offices(), memberTypes(), c.reason());
        if (!p.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", p));
        String reason = c.reason().trim();
        Map<String, String> next = new LinkedHashMap<>();
        next.put(MemberDefaults.DEPARTMENT, c.department() == null ? "" : String.valueOf(c.department()));
        next.put(MemberDefaults.OFFICE, c.office() == null ? "" : String.valueOf(c.office()));
        next.put(MemberDefaults.TYPES, String.join(",", types.stream().map(String::valueOf).toList()));
        for (var e : next.entrySet()) {
            List<String> was = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, e.getKey());
            String before = was.isEmpty() ? "" : was.get(0);
            if (before.equals(e.getValue())) continue;
            db.upsert("ecis_setting", Map.of("setting_key", e.getKey()), new LinkedHashMap<>(Map.of("setting_value", e.getValue(), "updated_by", auth.getName())));
            jdbc.update("UPDATE ecis_setting SET updated_at = NOW() WHERE setting_key = ?", e.getKey());
            AuditLog.write(jdbc, auth.getName(), "membership.defaults.changed", "setting", e.getKey(), before, e.getValue(), reason);
        }
        return setup(auth);
    }
}
