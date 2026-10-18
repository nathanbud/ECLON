package ph.coop.core.loan;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Who may take every step of a loan alone. Normally the encoder, the verifier, the approver of an application and the approver and poster of its
 * release voucher must be different people. A system administrator can lift that for one named person (with a reason, audited, never for
 * themselves); the person still needs the legacy access flag for each step. Without the table (V017) nobody is exempt.
 */
@Component
@RestController
@RequestMapping("/api/admin/single-hand-loans")
public class SingleHandGrants {

    public record Grant(String username, String reason, String grantedBy, String grantedAt) {}

    public record Reason(String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;

    SingleHandGrants(JdbcTemplate jdbc, Db db, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
    }

    /** True when the person may do all the steps of a loan alone. */
    public boolean has(String username) {
        if (username == null || !db.tableExists("ecis_single_hand")) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_single_hand WHERE username = ?", Integer.class, username);
        return n != null && n > 0;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<Grant> list() {
        if (!db.tableExists("ecis_single_hand")) return List.of();
        return jdbc.query("SELECT username, reason, granted_by, granted_at FROM ecis_single_hand ORDER BY username",
            (rs, i) -> new Grant(rs.getString(1), rs.getString(2), rs.getString(3), String.valueOf(rs.getTimestamp(4).toLocalDateTime())));
    }

    @PutMapping("/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    Grant grant(@PathVariable String username, @RequestBody Reason in, Authentication auth) {
        if (in == null || in.reason() == null || in.reason().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason for letting this person do a loan alone.");
        if (!db.tableExists("ecis_single_hand")) throw new ResponseStatusException(HttpStatus.CONFLICT, "V017 has not been applied yet.");
        List<String> names = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0", String.class, username);
        if (names.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such enabled user.");
        String name = names.get(0);
        if (name.equalsIgnoreCase(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An administrator cannot grant this to themselves.");
        String reason = in.reason().trim();
        jdbc.update("DELETE FROM ecis_single_hand WHERE username = ?", name);
        Timestamp at = Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
        jdbc.update("INSERT INTO ecis_single_hand (username, reason, granted_by, granted_at) VALUES (?, ?, ?, ?)", name, reason, auth.getName(), at);
        audit(auth, "loan.single-hand.grant", name, reason, at);
        return new Grant(name, reason, auth.getName(), String.valueOf(at.toLocalDateTime()));
    }

    @DeleteMapping("/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    void revoke(@PathVariable String username, Authentication auth) {
        if (!db.tableExists("ecis_single_hand")) return;
        if (jdbc.update("DELETE FROM ecis_single_hand WHERE username = ?", username) > 0) audit(auth, "loan.single-hand.revoke", username, "", Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)));
    }

    private void audit(Authentication auth, String action, String target, String detail, Timestamp at) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, 'ecis_single_hand', ?, ?)", at, auth.getName(), action, target, detail);
    }
}
