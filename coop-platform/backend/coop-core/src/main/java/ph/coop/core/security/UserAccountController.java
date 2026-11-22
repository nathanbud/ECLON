package ph.coop.core.security;


import ph.coop.core.audit.AuditLog;import ph.coop.core.db.Db;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    /** Roles an administrator may grant. MEMBERSHIP_OFFICER approves member changes; ACCOUNTANT works with journals and accounting periods; PURCHASER makes purchase requests, orders and suppliers; PURCHASE_APPROVER approves them; RECEIVER records goods received; STOREKEEPER keeps products and moves stock; STOCK_APPROVER approves stock adjustments and counts; POS_CASHIER sells and records returns; POS_SUPERVISOR sets prices and decides returns; ASSET_CUSTODIAN keeps the fixed asset register; DOCUMENT_CLERK looks after the documents kept for any record; MANAGER and CREDIT_COMMITTEE can be named as extra approvers in a workflow. */
    /** Held by a person who signed in with a temporary password: they can do nothing but replace it. */
    static final String PASSWORD_CHANGE_REQUIRED = "PWD_CHANGE_REQUIRED";

    static final Set<String> GRANTABLE_ROLES = Set.of("MEMBERSHIP_OFFICER", "DATA_PROTECTION_OFFICER", "ACCOUNTANT", "PURCHASER", "PURCHASE_APPROVER", "RECEIVER", "STOREKEEPER", "STOCK_APPROVER", "POS_CASHIER", "POS_SUPERVISOR", "ASSET_CUSTODIAN", "DOCUMENT_CLERK", "MANAGER", "CREDIT_COMMITTEE");

    private final JdbcTemplate jdbc;
    private final Db db;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ph.coop.core.feature.Features features;

    UserAccountController(JdbcTemplate jdbc, Db db, PasswordEncoder encoder, Clock clock, PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.jdbc = jdbc;
        this.db = db;
        this.encoder = encoder;
    }

    /** branchCode: the person's own branch in the old system (1 when they are not in it, as for the bootstrap account); screens start on it. */
    /** featuresOff: the areas this cooperative switched off (Settings > System > Features), for the screens to hide. */
    record Me(String username, String name, List<String> roles, boolean canEdit, boolean canApprove, boolean admin, boolean mustChangePassword, int branchCode, List<String> featuresOff) {}

    record Branch(int code, String name) {}

    /** The branches, so a screen offers a choice by name instead of asking for a branch number. */
    @GetMapping("/branches")
    List<Branch> branches() {
        return jdbc.query("SELECT BranchesID, BranchesDesc FROM branches ORDER BY BranchesID", (rs, i) -> new Branch(rs.getInt(1), rs.getString(2)));
    }

    @GetMapping("/me")
    Me me(Authentication auth) {
        List<String> authorities = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
        List<Object[]> found = jdbc.query("SELECT CONCAT_WS(' ', AccessFName, AccessLName), AccessBR_CODE FROM access WHERE AccessUserName = ? LIMIT 1",
            (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)}, auth.getName());
        String full = found.isEmpty() ? null : (String) found.get(0)[0];
        String name = full == null || full.isBlank() ? auth.getName() : full;
        int branch = found.isEmpty() || (Integer) found.get(0)[1] <= 0 ? 1 : (Integer) found.get(0)[1];
        return new Me(auth.getName(), name, authorities.stream().filter((a) -> a.startsWith("ROLE_")).map((a) -> a.substring(5)).toList(),
            authorities.contains("PERM_MEMBER_EDIT"), authorities.contains("ROLE_MEMBERSHIP_OFFICER"), authorities.contains("ROLE_ADMIN"),
            authorities.contains(PASSWORD_CHANGE_REQUIRED), branch, features == null ? List.of() : features.off());
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

    /** A new user: who they are, where and at what level they work, whose permissions to start from (optional), and how long the first password lasts. */
    record NewUser(String username, String firstName, String middleName, String lastName, Integer branchCode, String level, String copyFrom, Integer validityHours) {}

    /** The user made, what came from the user copied, and the temporary password (in this reply only, like a reissued one). */
    record Created(String username, long accessId, String level, int branchCode, String copiedFrom, int transactionRights, List<String> roles, TemporaryIssued password) {}

    /**
     * Creates a user (Settings > Users > New user): a row in the old system's access table, so the old system knows them too, and a temporary password in
     * ecis_user_credential that they replace at their first sign-in. With {@code copyFrom}, the new user starts with that person's permissions: every access
     * column but those in {@link UserCreateRules#NOT_COPIED}, their per-transaction-type rights (access_trans) and the application's roles; without it, with none.
     * Only a super administrator creates an administrator. One transaction; audited as USER_CREATED (never with the password).
     */
    @PostMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    Created create(@RequestBody NewUser in, Authentication auth) {
        Integer hours = PasswordPolicy.validity(in.validityHours());
        if (hours == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose how long the first password lasts: 1, 4, 24 or 72 hours.");
        List<String> mine = jdbc.query("SELECT AccessLevel FROM access WHERE AccessUserName = ? LIMIT 1", (rs, i) -> rs.getString(1), auth.getName());
        String callerLevel = mine.isEmpty() ? "SUPERADMIN" : mine.get(0); // the bootstrap account is not in the old system
        List<String> problems = UserCreateRules.problems(in.username(), in.firstName(), in.lastName(), in.middleName(), in.level(), callerLevel);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        String username = in.username().trim();
        String level = in.level().trim().toUpperCase();
        Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM access WHERE LOWER(AccessUserName) = LOWER(?)", Integer.class, username);
        Integer credentials = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_user_credential WHERE LOWER(username) = LOWER(?)", Integer.class, username);
        if ((taken != null && taken > 0) || (credentials != null && credentials > 0)) throw new ResponseStatusException(HttpStatus.CONFLICT, "The username " + username + " is already taken.");
        int branch = in.branchCode() == null ? 1 : in.branchCode();
        Integer branchFound = jdbc.queryForObject("SELECT COUNT(*) FROM branches WHERE BranchesID = ?", Integer.class, branch);
        if (branchFound == null || branchFound == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no branch " + branch + ".");

        // the first password goes in first: its table is keyed by username, so two administrators creating the same name at once cannot both succeed
        String password = PasswordPolicy.generateTemporary();
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime expires = now.plusHours(hours);
        try {
            jdbc.update("INSERT INTO ecis_user_credential (username, password_hash, set_by, set_at, must_change, expires_at) VALUES (?, ?, ?, ?, 1, ?)",
                username, encoder.encode(password), auth.getName(), Timestamp.valueOf(now), Timestamp.valueOf(expires));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The username " + username + " is already taken.");
        }

        // the access row: the copied person's columns (never who they are, their password or codes), then this person's own
        java.util.Map<String, Object> template = null;
        String copiedFrom = null;
        if (in.copyFrom() != null && !in.copyFrom().isBlank()) {
            List<java.util.Map<String, Object>> found = jdbc.queryForList("SELECT * FROM access WHERE AccessUserName = ? ORDER BY AccessID LIMIT 1", in.copyFrom().trim());
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no user " + in.copyFrom().trim() + " to copy permissions from.");
            template = found.get(0);
            copiedFrom = String.valueOf(template.entrySet().stream().filter((e) -> e.getKey().equalsIgnoreCase("AccessUserName")).findFirst().orElseThrow().getValue());
        }
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        Integer templateId = null, templateBranch = null;
        if (template != null) {
            for (var e : template.entrySet()) {
                String col = e.getKey().toLowerCase();
                if (col.equals("accessid")) templateId = ((Number) e.getValue()).intValue();
                if (col.equals("accessbr_code") && e.getValue() != null) templateBranch = ((Number) e.getValue()).intValue();
                if (UserCreateRules.NOT_COPIED.contains(col)) continue;
                row.put(e.getKey(), e.getValue());
            }
            // a point-of-sale location belongs to a branch: it is kept only when the new person works in the same one
            if (templateBranch == null || templateBranch != branch) row.keySet().removeIf((k) -> k.equalsIgnoreCase("AccessPOSLocation"));
        }
        // the column defaults to store location 1, which a database without store locations refuses; every existing user has none
        if (row.keySet().stream().noneMatch((k) -> k.equalsIgnoreCase("AccessPOSLocation"))) put(row, "AccessPOSLocation", null);
        put(row, "AccessUserName", username);
        put(row, "AccessFName", in.firstName().trim());
        put(row, "AccessMName", in.middleName() == null || in.middleName().isBlank() ? null : in.middleName().trim());
        put(row, "AccessLName", in.lastName().trim());
        put(row, "AccessLevel", level);
        put(row, "AccessBR_CODE", branch);
        put(row, "AccessIsDisabled", 0);
        if (row.keySet().stream().noneMatch((k) -> k.equalsIgnoreCase("AccessStartDay"))) put(row, "AccessStartDay", 0);
        if (row.keySet().stream().noneMatch((k) -> k.equalsIgnoreCase("AccessEndDay"))) put(row, "AccessEndDay", 0);
        String sql = "INSERT INTO access (" + String.join(", ", row.keySet()) + ") VALUES (" + String.join(", ", java.util.Collections.nCopies(row.size(), "?")) + ")";
        long accessId = db.insertReturningId(sql, "AccessID", row.values().toArray());

        int rights = 0;
        List<String> roles = new java.util.ArrayList<>();
        if (templateId != null) {
            rights = jdbc.update("""
                    INSERT INTO access_trans (br_code, userid, tr_code, can_find, can_new, can_edit, can_edit_entry, can_delete, can_tag_approved, can_post, can_reverse, can_cancel,
                      can_backdate, can_futuredate, can_post_auto, can_post_partialpay, can_post_overpay, can_post_uncleareddep, can_post_with_hold_dep, can_post_resultsnegative,
                      can_see_other_users_trans, can_backdate_closedbooks)
                    SELECT ?, ?, tr_code, can_find, can_new, can_edit, can_edit_entry, can_delete, can_tag_approved, can_post, can_reverse, can_cancel,
                      can_backdate, can_futuredate, can_post_auto, can_post_partialpay, can_post_overpay, can_post_uncleareddep, can_post_with_hold_dep, can_post_resultsnegative,
                      can_see_other_users_trans, can_backdate_closedbooks
                    FROM access_trans WHERE userid = ? AND br_code = ?
                    """, branch, accessId, templateId, templateBranch == null ? branch : templateBranch);
            for (String r : jdbc.queryForList("SELECT role FROM ecis_user_role WHERE username = ? ORDER BY role", String.class, copiedFrom)) {
                if (!GRANTABLE_ROLES.contains(r)) continue;
                jdbc.update("INSERT INTO ecis_user_role (username, role, granted_by, granted_at) VALUES (?, ?, ?, NOW())", username, r, auth.getName());
                roles.add(r);
            }
        }
        audit(auth.getName(), "USER_CREATED", username, level + ", branch " + branch + (copiedFrom == null ? ", no permissions" : ", permissions copied from " + copiedFrom
            + " (" + rights + " transaction types" + (roles.isEmpty() ? "" : ", roles " + String.join(", ", roles)) + ")") + "; temporary password for " + hours + " hours");
        return new Created(username, accessId, level, branch, copiedFrom, rights, roles, new TemporaryIssued(username, password, hours, expires));
    }

    /** Sets a column, replacing the same column however the database spells it. */
    private static void put(java.util.Map<String, Object> row, String column, Object value) {
        row.keySet().removeIf((k) -> k.equalsIgnoreCase(column));
        row.put(column, value);
    }

    record PasswordChange(String current, String next) {}

    /** A person replaces their own password: the temporary one at first sign-in, or their own any time. The current password is asked for again. */
    @PostMapping("/me/password")
    @org.springframework.transaction.annotation.Transactional
    void changeOwnPassword(@RequestBody PasswordChange body, Authentication auth) {
        List<String> hash = jdbc.query("SELECT password_hash FROM ecis_user_credential WHERE username = ?", (rs, i) -> rs.getString(1), auth.getName());
        if (hash.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This account has no password of its own to change.");
        if (body == null || body.current() == null || !encoder.matches(body.current(), hash.get(0))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The current password is not right, so the password was not changed. Type the password you signed in with, then the new one.");
        }
        String problem = PasswordPolicy.problem(body.next(), auth.getName(), body.current());
        if (problem != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        jdbc.update("UPDATE ecis_user_credential SET password_hash = ?, set_by = ?, set_at = ?, must_change = 0, expires_at = NULL WHERE username = ?",
            encoder.encode(body.next()), auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), auth.getName());
        audit(auth.getName(), "PASSWORD_CHANGED", auth.getName(), null);
    }

    static final String OFFICER = "MEMBERSHIP_OFFICER";

    /** On the Member tab but a preference, not a permission (whether the old system asks for a member ID first), so granting the role leaves it alone. */
    static final Set<String> NOT_SWITCHED_ON_WITH_OFFICER = Set.of("AccessIsClientIDDefaultPrompt");

    /**
     * The permission switches of the Member tab (Settings > Users > a person > Member), read from the tab itself so the two never drift apart:
     * every on/off setting there except {@link #NOT_SWITCHED_ON_WITH_OFFICER}. Amounts, the member types list and the own member number are not switches and are left as they are.
     */
    static List<UserProfileController.Spec> memberTabSwitches() {
        return UserProfileController.TABS.get("client").values().stream().flatMap(List::stream)
            .filter((s) -> s.kind().equals("bool") && !NOT_SWITCHED_ON_WITH_OFFICER.contains(s.column())).toList();
    }

    /** switchedOn: the Member tab permissions this grant switched on, by their label (empty when they were all on already, or for another role). */
    record Granted(String role, List<String> switchedOn) {}

    @PutMapping("/admin/users/{username}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    Granted grant(@PathVariable String username, @PathVariable String role, Authentication auth) {
        String r = checkRole(role);
        String name = canonicalName(username);
        // The role and the permissions that come with it are written together: if either fails, neither stays.
        return tx.execute((status) -> {
            List<String> switchedOn = r.equals(OFFICER) ? switchOnMemberPermissions(auth.getName(), name) : List.of();
            Integer has = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_user_role WHERE username = ? AND role = ?", Integer.class, name, r);
            if (has == null || has == 0) {
                try {
                    jdbc.update("INSERT INTO ecis_user_role (username, role, granted_by, granted_at) VALUES (?, ?, ?, NOW())", name, r, auth.getName());
                } catch (org.springframework.dao.DuplicateKeyException alreadyGranted) {
                    // granted at the same moment by someone else; the person has the role either way
                }
            }
            audit(auth.getName(), "ROLE_GRANTED", name, r);
            return new Granted(r, switchedOn);
        });
    }

    /**
     * A Membership Officer gets every permission on the Member tab: each switch that is off is switched on, with one UPDATE:<column> audit row per switch
     * (from its old value to 1), as when it is switched on by hand in the account setup. The same rules apply as for that setup: nobody changes their own
     * account, and only a super administrator changes an administrator's. Taking the role away later leaves the switches as they are.
     */
    private List<String> switchOnMemberPermissions(String by, String name) {
        List<UserProfileController.Spec> specs = memberTabSwitches();
        List<String> cols = specs.stream().map(UserProfileController.Spec::column).toList();
        Map<String, Object> row = jdbc.queryForMap("SELECT AccessLevel, " + String.join(", ", cols) + " FROM access WHERE AccessUserName = ? FOR UPDATE", name);
        Object level = valueOf(row, "AccessLevel");
        String problem = UserEditRules.problem(by, levelOf(by), name, level == null ? null : String.valueOf(level), false);
        if (problem != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, problem);

        List<UserProfileController.Spec> off = specs.stream().filter((s) -> !isOn(valueOf(row, s.column()))).toList();
        if (off.isEmpty()) return List.of();
        jdbc.update("UPDATE access SET " + String.join(", ", off.stream().map((s) -> s.column() + " = 1").toList()) + " WHERE AccessUserName = ?", name);
        for (var s : off) {
            Object was = valueOf(row, s.column());
            AuditLog.write(jdbc, by, "UPDATE:" + s.column(), "user", name, was == null ? null : String.valueOf(isOn(was) ? 1 : 0), "1",
                "Switched on with the Membership Officer role");
        }
        return off.stream().map(UserProfileController.Spec::label).toList();
    }

    /** A column of the locked row, whatever case the database gave its name. */
    private static Object valueOf(Map<String, Object> row, String column) {
        for (var e : row.entrySet()) if (e.getKey().equalsIgnoreCase(column)) return e.getValue();
        return null;
    }

    /** A flag as MySQL (tinyint(1): Boolean) or PostgreSQL (smallint: a number) returns it. */
    private static boolean isOn(Object flag) {
        if (flag instanceof Boolean b) return b;
        if (flag instanceof Number n) return n.intValue() != 0;
        return flag != null && "1".equals(String.valueOf(flag).trim());
    }

    /** The level of the person granting; the bootstrap administrator is not in the old list and counts as a super administrator. */
    private String levelOf(String username) {
        List<String> found = jdbc.query("SELECT AccessLevel FROM access WHERE AccessUserName = ? LIMIT 1", (rs, i) -> rs.getString(1), username);
        return found.isEmpty() ? "SUPERADMIN" : found.get(0);
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
        AuditLog.write(jdbc, by, action, "user", subject, null, detail, null);
    }
}
