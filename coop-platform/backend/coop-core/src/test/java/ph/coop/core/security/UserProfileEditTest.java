package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Saving a person's account setup (Settings > Users): a switch such as Access disabled is written and audited. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = {"ADMIN", "SUPERADMIN"})
class UserProfileEditTest {

    @Autowired UserProfileController profiles;
    @Autowired JdbcTemplate jdbc;

    private String person;
    private final Authentication tester = new TestingAuthenticationToken("tester", null, "ROLE_ADMIN", "ROLE_SUPERADMIN");

    @BeforeEach
    void ready() {
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester' AND AccessLevel = 'SUPERADMIN'").isEmpty(), "no tester super administrator");
        List<String> p = jdbc.queryForList("SELECT AccessUserName FROM access WHERE UPPER(AccessLevel) = 'USER' AND COALESCE(AccessIsDisabled, 0) = 0 AND AccessUserName <> 'tester' ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!p.isEmpty());
        person = p.get(0);
    }

    private String stored(String column) {
        return jdbc.queryForObject("SELECT CAST(" + column + " AS CHAR(20)) FROM access WHERE AccessUserName = ?", String.class, person);
    }

    @Test void switchesAnAccountOffAndOnAgain() {
        String was = stored("AccessIsDisabled");
        profiles.edit(person, new UserProfileController.EditRequest(Map.of("AccessIsDisabled", new UserProfileController.Change(was, "1"))), tester);
        assertEquals(1, Integer.parseInt(stored("AccessIsDisabled").trim()));
        profiles.edit(person, new UserProfileController.EditRequest(Map.of("AccessIsDisabled", new UserProfileController.Change("1", "0"))), tester);
        assertEquals(0, Integer.parseInt(stored("AccessIsDisabled").trim()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'UPDATE:AccessIsDisabled' AND entity_id = ?", Integer.class, person));
    }

    /** The longest column names: their audit action (UPDATE:<column>) once overflowed the 40 characters the audit log held, and the save failed. */
    @Test void savesTheSettingsWithTheLongestNames() {
        for (String column : List.of("AccessCanEditClientSourcesOfIncome", "AccessCanOverrideModifyCheckStatusRemarks")) {
            String was = stored(column);
            String to = "1".equals(was == null ? null : was.trim()) ? "0" : "1";
            profiles.edit(person, new UserProfileController.EditRequest(Map.of(column, new UserProfileController.Change(was, to))), tester);
            assertEquals(to, stored(column).trim(), column);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = ? AND entity_id = ?", Integer.class, "UPDATE:" + column, person), column);
        }
    }

    @Autowired UserAccountController accounts;

    @Test void grantingMembershipOfficerSwitchesOnEveryMemberPermission() {
        List<String> cols = UserAccountController.memberTabSwitches().stream().map(UserProfileController.Spec::column).toList();
        assertTrue(cols.contains("AccessCanEditClient") && cols.contains("AccessCanApproveClient") && cols.contains("AccessCanEditClientSourcesOfIncome") && cols.contains("AccessCanModifyHold"));
        assertFalse(cols.contains("AccessIsClientIDDefaultPrompt"), "a preference, not a permission");
        jdbc.update("DELETE FROM ecis_user_role WHERE username = ? AND role = 'MEMBERSHIP_OFFICER'", person);
        jdbc.update("UPDATE access SET " + String.join(", ", cols.stream().map((c) -> c + " = 0").toList()) + ", AccessIsClientIDDefaultPrompt = 0 WHERE AccessUserName = ?", person);

        var granted = accounts.grant(person, "MEMBERSHIP_OFFICER", tester);

        assertEquals(cols.size(), granted.switchedOn().size());
        for (String c : cols) assertEquals("1", stored(c).trim(), c);
        assertEquals("0", stored("AccessIsClientIDDefaultPrompt").trim());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_user_role WHERE username = ? AND role = 'MEMBERSHIP_OFFICER'", Integer.class, person));
        assertEquals(cols.size(), jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE entity_id = ? AND action LIKE 'UPDATE:%' AND reason = 'Switched on with the Membership Officer role'", Integer.class, person));

        // granting again changes nothing; taking the role away leaves the permissions on
        assertTrue(accounts.grant(person, "MEMBERSHIP_OFFICER", tester).switchedOn().isEmpty());
        accounts.revoke(person, "MEMBERSHIP_OFFICER", tester);
        for (String c : cols) assertEquals("1", stored(c).trim(), c);
    }

    @Test void nobodyMakesThemselvesAnOfficerThisWay() {
        jdbc.update("DELETE FROM ecis_user_role WHERE username = 'tester' AND role = 'MEMBERSHIP_OFFICER'");
        var e = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> accounts.grant("tester", "MEMBERSHIP_OFFICER", tester));
        assertTrue(e.getReason().contains("your own account"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_user_role WHERE username = 'tester' AND role = 'MEMBERSHIP_OFFICER'", Integer.class));
    }

    @Test void otherRolesTouchNoPermission() {
        jdbc.update("DELETE FROM ecis_user_role WHERE username = ? AND role = 'ACCOUNTANT'", person);
        String was = stored("AccessCanEditClient");
        assertTrue(accounts.grant(person, "ACCOUNTANT", tester).switchedOn().isEmpty());
        assertEquals(was, stored("AccessCanEditClient"));
    }

    @Test void savesAnotherSwitch() {
        String was = stored("AccessCanEditClient");
        String to = "1".equals(was == null ? null : was.trim()) ? "0" : "1";
        profiles.edit(person, new UserProfileController.EditRequest(Map.of("AccessCanEditClient", new UserProfileController.Change(was, to))), tester);
        assertEquals(to, stored("AccessCanEditClient").trim());
    }
}
