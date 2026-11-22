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
import ph.coop.core.db.Db;
import ph.coop.core.member.LookupController;
import ph.coop.core.organization.OfficeRules.Office;

/**
 * Offices (Settings > Organization > Offices, administrators): the old table {@code restrik}. A member's office is {@code client.RestrikID} (a new member gets
 * office 1 when none is chosen) and a user's offices are {@code access.AccessRestrik}. Add an office, rename it, or delete one nothing uses; the number is never
 * changed. Each change needs a reason and is audited as {@code office.add}, {@code office.rename} or {@code office.delete}. Needs PostgreSQL V035 / MySQL V072.
 */
@RestController
public class OfficeController {

    static final String NOT_INSTALLED = ph.coop.core.connection.DatabaseChanges.notSetUp("changing its offices");

    public record Row(int id, String name, int members, int users) {}

    public record Offices(boolean editable, List<Row> offices, int nextNumber, int maxNameLength) {}

    public record Add(Integer id, String name, String reason) {}

    public record Rename(String name, String reason) {}

    public record Delete(String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final LookupController lookups;

    OfficeController(JdbcTemplate jdbc, Db db, LookupController lookups) {
        this.jdbc = jdbc;
        this.db = db;
        this.lookups = lookups;
    }

    private List<Office> offices() {
        return jdbc.query("SELECT RestrikID, RestrikDesc FROM restrik ORDER BY RestrikID",
            (rs, i) -> new Office(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim()));
    }

    private int maxName() {
        return db.textColumnLengths("restrik").getOrDefault("RestrikDesc", 200);
    }

    private boolean editable() {
        return db.canInsertInto("restrik") && db.canUpdateColumn("restrik", "RestrikDesc");
    }

    /** Members per office, counting the member list and the old system's members still being entered. */
    private Map<Integer, Integer> members() {
        Map<Integer, Integer> out = new HashMap<>();
        List<String> tables = new ArrayList<>(List.of("client"));
        if (db.tableExists("client_temp")) tables.add("client_temp");
        for (String t : tables) {
            jdbc.query("SELECT RestrikID, COUNT(*) FROM " + t + " WHERE RestrikID IS NOT NULL GROUP BY RestrikID ORDER BY RestrikID",
                (rs) -> { out.merge(rs.getInt(1), rs.getInt(2), Integer::sum); });
        }
        return out;
    }

    /** Users per office, from each user's office list. */
    private Map<Integer, Integer> users() {
        Map<Integer, Integer> out = new HashMap<>();
        jdbc.query("SELECT AccessRestrik FROM access WHERE AccessRestrik IS NOT NULL ORDER BY AccessID",
            (rs) -> { for (int id : OfficeRules.officesOf(rs.getString(1))) out.merge(id, 1, Integer::sum); });
        return out;
    }

    @GetMapping("/api/admin/offices")
    @PreAuthorize("hasRole('ADMIN')")
    public Offices list() {
        List<Office> all = offices();
        Map<Integer, Integer> m = members();
        Map<Integer, Integer> u = users();
        List<Row> rows = all.stream().map((o) -> new Row(o.id(), o.name(), m.getOrDefault(o.id(), 0), u.getOrDefault(o.id(), 0))).toList();
        return new Offices(editable(), rows, OfficeRules.nextNumber(all), maxName());
    }

    @PostMapping("/api/admin/offices")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Offices add(@RequestBody Add a, Authentication auth) {
        requireEditable();
        List<Office> all = offices();
        problems(OfficeRules.addProblems(a.id(), a.name(), all, maxName(), a.reason()));
        String name = a.name().trim();
        jdbc.update("INSERT INTO restrik (RestrikID, RestrikDesc) VALUES (?, ?)", a.id(), name);
        AuditLog.write(jdbc, auth.getName(), "office.add", "office", String.valueOf(a.id()), null, name, a.reason().trim());
        lookups.forget(); // the member screens' office list shows the change at once
        return list();
    }

    @PutMapping("/api/admin/offices/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Offices rename(@PathVariable int id, @RequestBody Rename r, Authentication auth) {
        requireEditable();
        List<Office> all = offices();
        problems(OfficeRules.renameProblems(id, r.name(), all, maxName(), r.reason()));
        String before = all.stream().filter((o) -> o.id() == id).map(Office::name).findFirst().orElse("");
        String name = r.name().trim();
        jdbc.update("UPDATE restrik SET RestrikDesc = ? WHERE RestrikID = ?", name, id);
        AuditLog.write(jdbc, auth.getName(), "office.rename", "office", String.valueOf(id), before, name, r.reason().trim());
        lookups.forget(); // the member screens' office list shows the change at once
        return list();
    }

    @DeleteMapping("/api/admin/offices/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Offices delete(@PathVariable int id, @RequestBody Delete d, Authentication auth) {
        requireEditable();
        Office o = offices().stream().filter((x) -> x.id() == id).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Office number " + id + " is not on this database any more, so there is nothing to delete. Reload the screen to see the current offices."));
        problems(OfficeRules.deleteProblems(o, members().getOrDefault(id, 0), users().getOrDefault(id, 0), d == null ? null : d.reason()));
        jdbc.update("DELETE FROM restrik WHERE RestrikID = ?", id);
        AuditLog.write(jdbc, auth.getName(), "office.delete", "office", String.valueOf(id), o.name(), null, d.reason().trim());
        lookups.forget(); // the member screens' office list shows the change at once
        return list();
    }

    private void requireEditable() {
        if (!editable()) throw new ResponseStatusException(HttpStatus.CONFLICT, NOT_INSTALLED);
    }

    private static void problems(List<String> p) {
        if (!p.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", p));
    }
}
