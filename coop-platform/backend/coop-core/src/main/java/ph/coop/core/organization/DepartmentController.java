package ph.coop.core.organization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;
import ph.coop.core.member.LookupController;
import ph.coop.core.organization.DepartmentRules.Dept;
import ph.coop.core.organization.DepartmentRules.Input;

/**
 * Departments (Settings > Organization > Departments, administrators): the old table {@code dept}. Members ({@code client.ClientDEPT}, department 2 when
 * none is chosen), users ({@code access.AccessDepartment}) and the books (ledger entries, transaction lines, cheques, loan applications, holidays and others)
 * point to it. Add, change the name, parent, TIN and address, switch off or on, and delete one nothing has used; the number never changes. Each change needs
 * a reason and is audited as {@code department.add|change|switch-off|switch-on|delete}. Needs PostgreSQL V036 / MySQL V073.
 */
@RestController
public class DepartmentController {

    /** The records outside members and users that point to a department: table, column, what they are in words. Checked only before a delete. */
    static final List<String[]> USED_BY = List.of(
        new String[] {"gltrans", "GLTransDept", "ledger entries"},
        new String[] {"transdt", "DTDept", "transaction lines"},
        new String[] {"transck", "CKDept", "cheques"},
        new String[] {"loanapp", "Dept", "loan applications"},
        new String[] {"conn", "ConnDept", "connections"},
        new String[] {"holidays", "DeptID", "holidays"},
        new String[] {"inv_itemtype", "Dept", "item types"},
        new String[] {"transtype_invoice", "DeptID", "invoice settings"},
        new String[] {"client_temp", "ClientDEPT", "members being entered in the old system"});

    /** {@code kind} is what the unit is (UnitRules.KINDS); {@code kindChosen} is false while it is only suggested from the name. */
    public record Row(int id, String name, Integer parent, String parentName, String tin, String address, boolean disabled, int members, int users, int children,
                      String kind, String kindName, boolean kindChosen) {}

    public record Departments(boolean editable, List<Row> departments, int nextNumber, int defaultForMembers, Map<String, Integer> lengths, boolean kindsEditable,
                              Map<String, String> kinds) {}

    public record Kind(String kind, String reason) {}

    public record Change(Integer id, String name, Integer parent, String tin, String address, String reason) {}

    public record Switch(boolean off, String reason) {}

    public record Delete(String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final LookupController lookups;
    private final Units units;

    DepartmentController(JdbcTemplate jdbc, Db db, LookupController lookups, Units units) {
        this.jdbc = jdbc;
        this.db = db;
        this.lookups = lookups;
        this.units = units;
    }

    private List<Dept> departments() {
        return jdbc.query("SELECT DeptID, DeptDesc, DeptParentID, DeptTIN, DeptAddress, COALESCE(DeptIsDisabled, 0) FROM dept ORDER BY DeptID",
            (rs, i) -> new Dept(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim(), rs.getObject(3) == null ? null : rs.getInt(3),
                rs.getString(4), rs.getString(5), rs.getInt(6) != 0));
    }

    private Map<String, Integer> lengths() {
        Map<String, Integer> all = db.textColumnLengths("dept");
        Map<String, Integer> out = new HashMap<>();
        for (String c : List.of("DeptDesc", "DeptTIN", "DeptAddress")) if (all.get(c) != null) out.put(c, all.get(c));
        return out;
    }

    private boolean editable() {
        return db.canInsertInto("dept") && db.canUpdateColumn("dept", "DeptDesc");
    }

    private Map<Integer, Integer> countBy(String sql) {
        Map<Integer, Integer> out = new HashMap<>();
        jdbc.query(sql, (rs) -> { out.put(rs.getInt(1), rs.getInt(2)); });
        return out;
    }

    @GetMapping("/api/admin/departments")
    @PreAuthorize("hasRole('ADMIN')")
    public Departments list() {
        List<Dept> all = departments();
        Map<Integer, Integer> members = countBy("SELECT ClientDEPT, COUNT(*) FROM client WHERE ClientDEPT IS NOT NULL GROUP BY ClientDEPT ORDER BY ClientDEPT");
        Map<Integer, Integer> users = countBy("SELECT AccessDepartment, COUNT(*) FROM access WHERE AccessDepartment IS NOT NULL GROUP BY AccessDepartment ORDER BY AccessDepartment");
        Map<Integer, Integer> children = new HashMap<>();
        for (Dept d : all) if (d.parent() != null) children.merge(d.parent(), 1, Integer::sum);
        Map<Integer, String> names = new HashMap<>();
        for (Dept d : all) names.put(d.id(), d.name());
        Map<Integer, Units.Unit> kinds = new HashMap<>();
        for (Units.Unit u : units.all()) kinds.put(u.id(), u);
        List<Row> rows = all.stream().map((d) -> {
            Units.Unit u = kinds.get(d.id());
            String kind = u == null ? UnitRules.OTHER : u.kind();
            return new Row(d.id(), d.name(), d.parent(), d.parent() == null ? null : names.get(d.parent()), d.tin(), d.address(),
                d.disabled(), members.getOrDefault(d.id(), 0), users.getOrDefault(d.id(), 0), children.getOrDefault(d.id(), 0),
                kind, UnitRules.kindName(kind), u != null && u.kindChosen());
        }).toList();
        return new Departments(editable(), rows, DepartmentRules.nextNumber(all), DepartmentRules.DEFAULT_FOR_MEMBERS, lengths(),
            units.kindsStored() && db.canInsertInto("ecis_department_kind"), UnitRules.KINDS);
    }

    @PostMapping("/api/admin/departments")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Departments add(@RequestBody Change c, Authentication auth) {
        requireEditable();
        Input in = new Input(c.id(), c.name(), c.parent(), c.tin(), c.address());
        problems(DepartmentRules.addProblems(in, departments(), lengths(), c.reason()));
        jdbc.update("INSERT INTO dept (DeptID, DeptDesc, DeptParentID, DeptTIN, DeptAddress, DeptIsDisabled) VALUES (?, ?, ?, ?, ?, 0)",
            c.id(), c.name().trim(), c.parent(), DepartmentRules.trim(c.tin()), DepartmentRules.trim(c.address()));
        AuditLog.write(jdbc, auth.getName(), "department.add", "department", String.valueOf(c.id()), null, describe(in), c.reason().trim());
        lookups.forget();
        return list();
    }

    /**
     * Records what a department is: main office, branch, satellite office, consolidation or another unit. Statements name each unit by it, so a branch and a
     * satellite office are not confused. Reason required, audited department.kind with the kind before (or the suggested one) and after.
     */
    @PutMapping("/api/admin/departments/{id}/kind")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Departments setKind(@PathVariable int id, @RequestBody Kind k, Authentication auth) {
        if (!units.kindsStored()) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("Recording what each department is (main office, branch, satellite office)"));
        Units.Unit before = units.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Department " + id + " is not in this database, so nothing was saved. Reload Settings > Organization > Departments and choose it from the list."));
        problems(UnitRules.kindProblems(k == null ? null : k.kind(), k == null ? null : k.reason()));
        if (before.kindChosen() && before.kind().equals(k.kind())) return list();
        db.upsert("ecis_department_kind", Map.of("dept_id", id), Map.of("kind", k.kind(), "updated_by", auth.getName(), "updated_at", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now())));
        AuditLog.write(jdbc, auth.getName(), "department.kind", "department", String.valueOf(id),
            (before.kindChosen() ? "" : "suggested: ") + UnitRules.kindName(before.kind()), UnitRules.kindName(k.kind()), k.reason().trim());
        return list();
    }

    @PutMapping("/api/admin/departments/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Departments change(@PathVariable int id, @RequestBody Change c, Authentication auth) {
        requireEditable();
        List<Dept> all = departments();
        Input in = new Input(id, c.name(), c.parent(), c.tin(), c.address());
        problems(DepartmentRules.changeProblems(id, in, all, lengths(), c.reason()));
        Dept before = all.stream().filter((d) -> d.id() == id).findFirst().orElseThrow();
        jdbc.update("UPDATE dept SET DeptDesc = ?, DeptParentID = ?, DeptTIN = ?, DeptAddress = ? WHERE DeptID = ?",
            c.name().trim(), c.parent(), DepartmentRules.trim(c.tin()), DepartmentRules.trim(c.address()), id);
        AuditLog.write(jdbc, auth.getName(), "department.change", "department", String.valueOf(id),
            describe(new Input(id, before.name(), before.parent(), before.tin(), before.address())), describe(in), c.reason().trim());
        lookups.forget();
        return list();
    }

    @PostMapping("/api/admin/departments/{id}/switch")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Departments switchOnOff(@PathVariable int id, @RequestBody Switch s, Authentication auth) {
        requireEditable();
        Dept d = existing(id, "switched on or off");
        problems(DepartmentRules.switchProblems(d, s.off(), s.reason()));
        jdbc.update("UPDATE dept SET DeptIsDisabled = ? WHERE DeptID = ?", s.off() ? 1 : 0, id);
        AuditLog.write(jdbc, auth.getName(), s.off() ? "department.switch-off" : "department.switch-on", "department", String.valueOf(id),
            d.name() + (d.disabled() ? " (off)" : " (on)"), d.name() + (s.off() ? " (off)" : " (on)"), s.reason().trim());
        lookups.forget();
        return list();
    }

    @DeleteMapping("/api/admin/departments/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Departments delete(@PathVariable int id, @RequestBody Delete del, Authentication auth) {
        requireEditable();
        Dept d = existing(id, "deleted");
        Row row = list().departments().stream().filter((r) -> r.id() == id).findFirst().orElseThrow();
        List<String> usedIn = new ArrayList<>();
        for (String[] u : USED_BY) {
            if (!db.columnExists(u[0], u[1])) continue;
            if (!jdbc.queryForList("SELECT 1 FROM " + u[0] + " WHERE " + u[1] + " = ? LIMIT 1", Integer.class, id).isEmpty()) usedIn.add(u[2]);
        }
        problems(DepartmentRules.deleteProblems(d, row.members(), row.users(), row.children(), usedIn, del == null ? null : del.reason()));
        jdbc.update("DELETE FROM dept WHERE DeptID = ?", id);
        AuditLog.write(jdbc, auth.getName(), "department.delete", "department", String.valueOf(id), d.name(), null, del.reason().trim());
        lookups.forget();
        return list();
    }

    private Dept existing(int id, String what) {
        return departments().stream().filter((x) -> x.id() == id).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Department number " + id + " is not on this database any more, so it cannot be " + what + ". Reload the screen to see the current departments."));
    }

    private static String describe(Input in) {
        StringBuilder b = new StringBuilder(in.name() == null ? "" : in.name().trim());
        if (in.parent() != null) b.append("; under ").append(in.parent());
        if (DepartmentRules.trim(in.tin()) != null) b.append("; TIN ").append(in.tin().trim());
        if (DepartmentRules.trim(in.address()) != null) b.append("; ").append(in.address().trim());
        return b.toString();
    }

    private void requireEditable() {
        if (!editable()) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("changing its departments"));
    }

    private static void problems(List<String> p) {
        if (!p.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", p));
    }
}
