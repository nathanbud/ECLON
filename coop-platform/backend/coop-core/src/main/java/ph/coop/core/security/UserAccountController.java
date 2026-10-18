package ph.coop.core.security;

import ph.coop.core.db.Db;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Who is signed in, and the administration of app passwords and roles. */
@RestController
@RequestMapping("/api")
class UserAccountController {

    /** Roles an administrator may grant. MEMBERSHIP_OFFICER approves member changes; ACCOUNTANT works with journals and accounting periods. */
    static final Set<String> GRANTABLE_ROLES = Set.of("MEMBERSHIP_OFFICER", "ACCOUNTANT");

    static final int MIN_PASSWORD = 8;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final PasswordEncoder encoder;

    UserAccountController(JdbcTemplate jdbc, Db db, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.db = db;
        this.encoder = encoder;
    }

    record Me(String username, String name, List<String> roles, boolean canEdit, boolean canApprove, boolean admin) {}

    @GetMapping("/me")
    Me me(Authentication auth) {
        List<String> authorities = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
        List<String> names = jdbc.query("SELECT CONCAT_WS(' ', AccessFName, AccessLName) FROM access WHERE AccessUserName = ? LIMIT 1",
            (rs, i) -> rs.getString(1), auth.getName());
        String name = names.isEmpty() || names.get(0) == null || names.get(0).isBlank() ? auth.getName() : names.get(0);
        return new Me(auth.getName(), name, authorities.stream().filter((a) -> a.startsWith("ROLE_")).map((a) -> a.substring(5)).toList(),
            authorities.contains("PERM_MEMBER_EDIT"), authorities.contains("ROLE_MEMBERSHIP_OFFICER"), authorities.contains("ROLE_ADMIN"));
    }

    record UserRow(String username, String name, String level, boolean enabled, boolean canEdit, boolean hasPassword, List<String> roles) {}

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    List<UserRow> users() {
        Map<String, List<String>> roles = new java.util.HashMap<>();
        jdbc.query("SELECT username, role FROM ecis_user_role ORDER BY username, role",
            (rs) -> { roles.computeIfAbsent(rs.getString(1), (k) -> new java.util.ArrayList<>()).add(rs.getString(2)); });
        return jdbc.query("""
                SELECT a.AccessUserName, CONCAT_WS(' ', a.AccessFName, a.AccessLName), a.AccessLevel,
                       COALESCE(a.AccessIsDisabled, 0) = 0, a.AccessCanEditClient = 1,
                       EXISTS (SELECT 1 FROM ecis_user_credential c WHERE c.username = a.AccessUserName)
                FROM access a ORDER BY a.AccessUserName
                """,
            (rs, i) -> new UserRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6),
                roles.getOrDefault(rs.getString(1), List.of())));
    }

    record NewPassword(String password) {}

    /** Sets (or resets) a person's app password. Only people in the legacy user list, and not disabled, can be given one. */
    @PutMapping("/admin/users/{username}/password")
    @PreAuthorize("hasRole('ADMIN')")
    void setPassword(@PathVariable String username, @RequestBody NewPassword body, Authentication auth) {
        if (body.password() == null || body.password().length() < MIN_PASSWORD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The password must be at least " + MIN_PASSWORD + " characters.");
        }
        String name = canonicalName(username);
        db.upsert("ecis_user_credential", new java.util.LinkedHashMap<>(java.util.Map.of("username", name)),
            new java.util.LinkedHashMap<>(java.util.Map.of("password_hash", encoder.encode(body.password()), "set_by", auth.getName(),
                "set_at", new java.sql.Timestamp(System.currentTimeMillis()))));
        audit(auth.getName(), "PASSWORD_SET", name, null);
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
            LocalDateTime.now(), by, action, subject, detail);
    }
}
