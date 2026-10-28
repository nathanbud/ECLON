package ph.coop.core.security;

import ph.coop.core.db.Db;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Who is signed in, and the administration of app passwords and roles. */
@RestController
@RequestMapping("/api")
class UserAccountController {

    /** Roles an administrator may grant. MEMBERSHIP_OFFICER approves member changes; ACCOUNTANT works with journals and accounting periods; PURCHASER makes purchase requests, orders and suppliers; PURCHASE_APPROVER approves them; RECEIVER records goods received; STOREKEEPER keeps products and moves stock; STOCK_APPROVER approves stock adjustments and counts; POS_CASHIER sells and records returns; POS_SUPERVISOR sets prices and decides returns. */
    /** Held by a person who signed in with a temporary password: they can do nothing but replace it. */
    static final String PASSWORD_CHANGE_REQUIRED = "PWD_CHANGE_REQUIRED";

    static final Set<String> GRANTABLE_ROLES = Set.of("MEMBERSHIP_OFFICER", "ACCOUNTANT", "PURCHASER", "PURCHASE_APPROVER", "RECEIVER", "STOREKEEPER", "STOCK_APPROVER", "POS_CASHIER", "POS_SUPERVISOR");

    private final JdbcTemplate jdbc;
    private final Db db;
    private final PasswordEncoder encoder;
    private final Clock clock;

    UserAccountController(JdbcTemplate jdbc, Db db, PasswordEncoder encoder, Clock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.db = db;
        this.encoder = encoder;
    }

    record Me(String username, String name, List<String> roles, boolean canEdit, boolean canApprove, boolean admin, boolean mustChangePassword) {}

    @GetMapping("/me")
    Me me(Authentication auth) {
        List<String> authorities = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
        List<String> names = jdbc.query("SELECT CONCAT_WS(' ', AccessFName, AccessLName) FROM access WHERE AccessUserName = ? LIMIT 1",
            (rs, i) -> rs.getString(1), auth.getName());
        String name = names.isEmpty() || names.get(0) == null || names.get(0).isBlank() ? auth.getName() : names.get(0);
        return new Me(auth.getName(), name, authorities.stream().filter((a) -> a.startsWith("ROLE_")).map((a) -> a.substring(5)).toList(),
            authorities.contains("PERM_MEMBER_EDIT"), authorities.contains("ROLE_MEMBERSHIP_OFFICER"), authorities.contains("ROLE_ADMIN"),
            authorities.contains(PASSWORD_CHANGE_REQUIRED));
    }

    /** passwordState: NONE (no password yet), ACTIVE (their own), TEMPORARY (issued, not yet replaced, still valid) or EXPIRED (issued and ran out). */
    record UserRow(String username, String name, String level, boolean enabled, boolean canEdit, boolean hasPassword, String passwordState, LocalDateTime tempExpiresAt, List<String> roles) {}

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    List<UserRow> users() {
        Map<String, List<String>> roles = new java.util.HashMap<>();
        jdbc.query("SELECT username, role FROM ecis_user_role ORDER BY username, role",
            (rs) -> { roles.computeIfAbsent(rs.getString(1), (k) -> new java.util.ArrayList<>()).add(rs.getString(2)); });
        return jdbc.query("""
                SELECT a.AccessUserName, CONCAT_WS(' ', a.AccessFName, a.AccessLName), a.AccessLevel,
                       COALESCE(a.AccessIsDisabled, 0) = 0, a.AccessCanEditClient = 1,
                       c.username IS NOT NULL, COALESCE(c.must_change, 0), c.expires_at
                FROM access a LEFT JOIN ecis_user_credential c ON c.username = a.AccessUserName
                ORDER BY a.AccessUserName
                """,
            (rs, i) -> {
                boolean has = rs.getBoolean(6);
                Timestamp exp = rs.getTimestamp(8);
                LocalDateTime expires = exp == null ? null : exp.toLocalDateTime();
                String state = !has ? "NONE" : rs.getInt(7) == 0 ? "ACTIVE" : expires != null && expires.isBefore(LocalDateTime.now(clock)) ? "EXPIRED" : "TEMPORARY";
                return new UserRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), has, state, rs.getInt(7) == 0 ? null : expires,
                    roles.getOrDefault(rs.getString(1), List.of()));
            });
    }

    record TemporaryRequest(Integer validityHours) {}

    /** The temporary password is in this reply and nowhere else: it is stored only as a hash and cannot be read again. */
    record TemporaryIssued(String username, String password, int validityHours, LocalDateTime expiresAt) {}

    /**
     * Issues a temporary password for a person (a first password, or a reset). The system makes it; the administrator reads it out once. The person must
     * replace it when they first sign in, and it stops working after the validity chosen. Nobody issues one for themselves, and only a super
     * administrator issues one for an administrator. Any sign-in already open for that person stops working at once.
     */
    @PutMapping("/admin/users/{username}/password")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    TemporaryIssued issueTemporaryPassword(@PathVariable String username, @RequestBody(required = false) TemporaryRequest body, Authentication auth) {
        Integer hours = PasswordPolicy.validity(body == null ? null : body.validityHours());
        if (hours == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose how long it lasts: 1, 4, 24 or 72 hours.");
        String name = canonicalName(username);
        List<String> level = jdbc.query("SELECT AccessLevel FROM access WHERE AccessUserName = ? LIMIT 1", (rs, i) -> rs.getString(1), name);
        List<String> mine = jdbc.query("SELECT AccessLevel FROM access WHERE AccessUserName = ? LIMIT 1", (rs, i) -> rs.getString(1), auth.getName());
        String problem = UserEditRules.problem(auth.getName(), mine.isEmpty() ? "SUPERADMIN" : mine.get(0), name, level.get(0), false);
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, problem.startsWith("You cannot change your own") ? "You cannot issue a temporary password for yourself. Ask another administrator." : problem.replace("change an administrator's account or anyone's level", "issue a password for an administrator"));
        }
        String password = PasswordPolicy.generateTemporary();
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime expires = now.plusHours(hours);
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("password_hash", encoder.encode(password));
        values.put("set_by", auth.getName());
        values.put("set_at", Timestamp.valueOf(now));
        values.put("must_change", 1);
        values.put("expires_at", Timestamp.valueOf(expires));
        db.upsert("ecis_user_credential", new java.util.LinkedHashMap<>(java.util.Map.of("username", name)), values);
        audit(auth.getName(), "PASSWORD_TEMP_ISSUED", name, hours + " hours, until " + expires);
        return new TemporaryIssued(name, password, hours, expires);
    }

    record PasswordChange(String current, String next) {}

    /** A person replaces their own password: the temporary one at first sign-in, or their own any time. The current password is asked for again. */
    @PostMapping("/me/password")
    @org.springframework.transaction.annotation.Transactional
    void changeOwnPassword(@RequestBody PasswordChange body, Authentication auth) {
        List<String> hash = jdbc.query("SELECT password_hash FROM ecis_user_credential WHERE username = ?", (rs, i) -> rs.getString(1), auth.getName());
        if (hash.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This account has no password of its own to change.");
        if (body == null || body.current() == null || !encoder.matches(body.current(), hash.get(0))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The current password is not right.");
        }
        String problem = PasswordPolicy.problem(body.next(), auth.getName(), body.current());
        if (problem != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        jdbc.update("UPDATE ecis_user_credential SET password_hash = ?, set_by = ?, set_at = ?, must_change = 0, expires_at = NULL WHERE username = ?",
            encoder.encode(body.next()), auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), auth.getName());
        audit(auth.getName(), "PASSWORD_CHANGED", auth.getName(), null);
    }

    @PutMapping("/admin/users/{username}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    void grant(@PathVariable String username, @PathVariable String role, Authentication auth) {
        String r = checkRole(role);
        String name = canonicalName(username);
        Integer has = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_user_role WHERE username = ? AND role = ?", Integer.class, name, r);
        if (has == null || has == 0) {
            try {
                jdbc.update("INSERT INTO ecis_user_role (username, role, granted_by, granted_at) VALUES (?, ?, ?, NOW())", name, r, auth.getName());
            } catch (org.springframework.dao.DuplicateKeyException alreadyGranted) {
                // granted at the same moment by someone else; the person has the role either way
            }
        }
        audit(auth.getName(), "ROLE_GRANTED", name, r);
    }

    @DeleteMapping("/admin/users/{username}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    void revoke(@PathVariable String username, @PathVariable String role, Authentication auth) {
        String r = checkRole(role);
        String name = canonicalName(username);
        jdbc.update("DELETE FROM ecis_user_role WHERE username = ? AND role = ?", name, r);
        audit(auth.getName(), "ROLE_REVOKED", name, r);
    }

    private static String checkRole(String role) {
        String r = role.toUpperCase();
        if (!GRANTABLE_ROLES.contains(r)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role '" + role + "'.");
        return r;
    }

    private String canonicalName(String username) {
        List<String> found = jdbc.query("SELECT AccessUserName FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
            (rs, i) -> rs.getString(1), username);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No enabled user '" + username + "'.");
        return found.get(0);
    }

    /** Administration is audited too; the password itself is never logged. */
    private void audit(String by, String action, String subject, String detail) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, 'user', ?, ?)",
            LocalDateTime.now(clock), by, action, subject, detail);
    }
}
