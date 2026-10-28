package ph.coop.core.member;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/** Administrator-controlled membership policy, kept in {@code ecis_setting}. Every change is audited with a reason. */
@RestController
public class MembershipPolicyController {

    static final String RETURNING_PAYS_SHARE = "membership.returning_pays_share_capital";

    public record Policy(boolean returningPaysShareCapital) {}

    public record Change(boolean returningPaysShareCapital, String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;

    MembershipPolicyController(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /** Off unless an administrator switched it on (or the table does not exist yet). */
    public boolean returningPaysShareCapital() {
        if (!db.tableExists("ecis_setting")) return false;
        List<String> v = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, RETURNING_PAYS_SHARE);
        return !v.isEmpty() && v.get(0).equals("true");
    }

    @GetMapping("/api/membership-policy")
    public Policy get() {
        return new Policy(returningPaysShareCapital());
    }

    @PutMapping("/api/admin/membership-policy")
    @PreAuthorize("hasRole('ADMIN')")
    public Policy set(@RequestBody Change c, Authentication auth) {
        if (!db.allTablesExist("ecis_setting", "ecis_audit_log")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The settings table is not installed (V038).");
        if (c.reason() == null || c.reason().trim().length() < 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason for the change.");
        boolean before = returningPaysShareCapital();
        db.upsert("ecis_setting", java.util.Map.of("setting_key", RETURNING_PAYS_SHARE),
            new java.util.LinkedHashMap<>(java.util.Map.of("setting_value", Boolean.toString(c.returningPaysShareCapital()), "updated_by", auth.getName())));
        jdbc.update("UPDATE ecis_setting SET updated_at = NOW() WHERE setting_key = ?", RETURNING_PAYS_SHARE);
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (NOW(), ?, 'membership.policy.changed', 'setting', ?, ?, ?, ?)",
            auth.getName(), RETURNING_PAYS_SHARE, Boolean.toString(before), Boolean.toString(c.returningPaysShareCapital()), c.reason().trim());
        return new Policy(c.returningPaysShareCapital());
    }
}
