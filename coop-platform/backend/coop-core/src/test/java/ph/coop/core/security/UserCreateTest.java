package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.security.UserAccountController.NewUser;

/** Creating a user against the database, rolled back: the access row, copied permissions without the copied person's identity or secrets, the temporary password, the rules. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class UserCreateTest {

    @Autowired UserAccountController users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication as(String user, String... auths) {
        Authentication a = new TestingAuthenticationToken(user, "x", auths);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    /** The bootstrap account is not in the old system's users, so it counts as a super administrator. */
    private Authentication superAdmin() {
        return as("tester-boot", "ROLE_ADMIN");
    }

    private void ready() {
        assumeTrue(db.canInsertInto("access", "access_trans", "ecis_user_credential"), "the grants of V032 (PostgreSQL) or V069 (MySQL) are missing");
    }

    @Test
    void aNewUserCopiesPermissionsButNeverTheOtherPersonsIdentityOrSecrets() {
        ready();
        List<Map<String, Object>> t = jdbc.queryForList("""
            SELECT a.AccessUserName AS u, a.AccessID AS id, a.AccessBR_CODE AS br FROM access a
            WHERE a.AccessLevel = 'USER' AND COALESCE(a.AccessIsDisabled, 0) = 0 AND EXISTS (SELECT 1 FROM access_trans x WHERE x.userid = a.AccessID AND x.br_code = a.AccessBR_CODE)
            ORDER BY a.AccessID LIMIT 1""");
        assumeTrue(!t.isEmpty(), "no user with transaction rights to copy");
        String from = (String) t.get(0).get("u");
        int fromId = ((Number) t.get(0).get("id")).intValue(), branch = ((Number) t.get(0).get("br")).intValue();
        Integer theirRights = jdbc.queryForObject("SELECT COUNT(*) FROM access_trans WHERE userid = ? AND br_code = ?", Integer.class, fromId, branch);

        var made = users.create(new NewUser("zz.newteller", "Nina", null, "Zztest", branch, "user", from, 4), superAdmin());
        assertEquals("zz.newteller", made.username());
        assertEquals("USER", made.level());
        assertEquals(from, made.copiedFrom());
        assertEquals(theirRights, made.transactionRights());

        Map<String, Object> mine = jdbc.queryForMap("SELECT * FROM access WHERE AccessID = ?", made.accessId());
        Map<String, Object> theirs = jdbc.queryForMap("SELECT * FROM access WHERE AccessID = ?", fromId);
        assertEquals("Nina", get(mine, "AccessFName"));
        assertEquals("Zztest", get(mine, "AccessLName"));
        assertEquals("USER", get(mine, "AccessLevel"));
        assertEquals(0, ((Number) get(mine, "AccessIsDisabled")).intValue());
        assertNull(get(mine, "AccessPassword"), "the old password is never copied");
        assertNull(get(mine, "AccessOTPSecretKey"), "nor the one-time-code secret");
        assertNull(get(mine, "AccessClientID"), "nor the other person's member number");
        // a permission flag is copied
        assertEquals(get(theirs, "AccessCanEditClient"), get(mine, "AccessCanEditClient"));
        assertEquals(get(theirs, "AccessCanAddLoanApplication"), get(mine, "AccessCanAddLoanApplication"));

        // a temporary password, stored only as a hash, to be replaced at first sign-in
        Map<String, Object> cred = jdbc.queryForMap("SELECT password_hash, must_change FROM ecis_user_credential WHERE username = 'zz.newteller'");
        assertTrue(encoder.matches(made.password().password(), (String) cred.get("password_hash")));
        assertEquals(1, ((Number) cred.get("must_change")).intValue());
        assertEquals(4, made.password().validityHours());
        // audited, without the password
        String detail = jdbc.queryForObject("SELECT new_value FROM ecis_audit_log WHERE action = 'USER_CREATED' AND entity_id = 'zz.newteller' ORDER BY id DESC LIMIT 1", String.class);
        assertTrue(detail.contains("permissions copied from " + from), detail);
        assertFalse(detail.contains(made.password().password()));
        // and now listed
        assertTrue(users.users().stream().anyMatch((u) -> u.username().equals("zz.newteller") && u.passwordState().equals("TEMPORARY")));
    }

    @Test
    void aUserWithoutACopyStartsWithNoPermissions() {
        ready();
        var made = users.create(new NewUser("zz.blank", "Ben", "C", "Zztest", 1, "USER", null, null), superAdmin());
        assertNull(made.copiedFrom());
        assertEquals(0, made.transactionRights());
        Map<String, Object> mine = jdbc.queryForMap("SELECT * FROM access WHERE AccessID = ?", made.accessId());
        assertEquals("C", get(mine, "AccessMName"));
        Object edit = get(mine, "AccessCanEditClient");
        assertTrue(edit == null || ((Number) edit).intValue() == 0);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM access_trans WHERE userid = ?", Integer.class, made.accessId()));
    }

    @Test
    void takenNamesBadInputAndAdministratorsAreRefused() {
        ready();
        String existing = jdbc.queryForObject("SELECT AccessUserName FROM access ORDER BY AccessID LIMIT 1", String.class);
        var dup = assertThrows(ResponseStatusException.class, () -> users.create(new NewUser(existing.toUpperCase(), "A", null, "B", 1, "USER", null, null), superAdmin()));
        assertTrue(dup.getReason().contains("already taken"), dup.getReason());
        // the bootstrap sign-in's name (DEV_API_USER=itest here), in any case: it is matched first, so that person's own password would never work
        var boot = assertThrows(ResponseStatusException.class, () -> users.create(new NewUser("ITest", "A", null, "B", 1, "USER", null, null), superAdmin()));
        assertTrue(boot.getReason().contains("bootstrap sign-in"), boot.getReason());
        assertThrows(ResponseStatusException.class, () -> users.create(new NewUser("zz bad name", "A", null, "B", 1, "USER", null, null), superAdmin()));
        assertThrows(ResponseStatusException.class, () -> users.create(new NewUser("zz.nobranch", "A", null, "B", 9999, "USER", null, null), superAdmin()));
        assertThrows(ResponseStatusException.class, () -> users.create(new NewUser("zz.nocopy", "A", null, "B", 1, "USER", "no-such-person-zz", null), superAdmin()));
        assertThrows(ResponseStatusException.class, () -> users.create(new NewUser("zz.hours", "A", null, "B", 1, "USER", null, 5), superAdmin()));
        // a plain administrator cannot create an administrator
        List<String> admins = jdbc.queryForList("SELECT AccessUserName FROM access WHERE AccessLevel = 'ADMIN' ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!admins.isEmpty(), "no ADMIN-level user to act as");
        var e = assertThrows(ResponseStatusException.class, () -> users.create(new NewUser("zz.boss", "A", null, "B", 1, "ADMIN", null, null), as(admins.get(0), "ROLE_ADMIN")));
        assertTrue(e.getReason().contains("Only a super administrator"), e.getReason());
        // but can create a user
        assertEquals("USER", users.create(new NewUser("zz.byadmin", "A", null, "B", 1, "USER", null, null), as(admins.get(0), "ROLE_ADMIN")).level());
    }

    private static Object get(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> e : row.entrySet()) if (e.getKey().equalsIgnoreCase(column)) return e.getValue();
        return null;
    }
}
