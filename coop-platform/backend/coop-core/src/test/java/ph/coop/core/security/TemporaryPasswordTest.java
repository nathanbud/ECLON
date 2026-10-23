package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Temporary passwords: made by the system, time limited, replaced at first sign-in, never logged. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class TemporaryPasswordTest {

    @Autowired UserAccountController users;
    @Autowired UserDetailsService details;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    private String person;

    @BeforeEach
    void ready() {
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester' AND AccessLevel = 'SUPERADMIN'").isEmpty(), "no tester super administrator");
        List<String> p = jdbc.queryForList("SELECT AccessUserName FROM access WHERE UPPER(AccessLevel) = 'USER' AND COALESCE(AccessIsDisabled, 0) = 0 AND AccessUserName <> 'tester' LIMIT 1", String.class);
        assumeTrue(!p.isEmpty());
        person = p.get(0);
    }

    private Authentication me() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test void theSystemMakesItAndItLastsADayByDefault() {
        var t = users.issueTemporaryPassword(person, null, me());
        assertEquals(12, t.password().length());
        assertEquals(24, t.validityHours());
        assertTrue(Duration.between(LocalDateTime.now(), t.expiresAt()).toMinutes() > 24 * 60 - 2);
        var row = jdbc.queryForMap("SELECT password_hash, must_change, expires_at, set_by FROM ecis_user_credential WHERE username = ?", person);
        assertTrue(encoder.matches(t.password(), (String) row.get("password_hash")));
        assertEquals(true, row.get("must_change"));
        assertNotNull(row.get("expires_at"));
        assertEquals("tester", row.get("set_by"));
    }

    @Test void theAuditLogNeverHoldsThePassword() {
        var t = users.issueTemporaryPassword(person, new UserAccountController.TemporaryRequest(4), me());
        assertEquals(4, t.validityHours());
        List<String> rows = jdbc.queryForList("SELECT CONCAT_WS(' ', action, entity_id, new_value, prev_value, reason) FROM ecis_audit_log WHERE action = 'PASSWORD_TEMP_ISSUED' AND entity_id = ?", String.class, person);
        assertFalse(rows.isEmpty());
        rows.forEach((r) -> assertFalse(r.contains(t.password())));
    }

    @Test void onlyTheFourValiditiesAreOffered() {
        for (int bad : new int[] { 0, 2, 48, 168 }) {
            assertThrows(ResponseStatusException.class, () -> users.issueTemporaryPassword(person, new UserAccountController.TemporaryRequest(bad), me()));
        }
        for (int ok : PasswordPolicy.VALIDITY_HOURS) assertEquals(ok, users.issueTemporaryPassword(person, new UserAccountController.TemporaryRequest(ok), me()).validityHours());
    }

    @Test void nobodyIssuesOneForThemselves() {
        var e = assertThrows(ResponseStatusException.class, () -> users.issueTemporaryPassword("tester", null, me()));
        assertTrue(e.getReason().contains("yourself"));
    }

    @Test @WithMockUser(username = "tester", roles = "ADMIN") void anAdministratorsPasswordNeedsASuperAdministrator() {
        // tester is a SUPERADMIN in the legacy list, so find another administrator to act as an ordinary one
        List<String> admins = jdbc.queryForList("SELECT AccessUserName FROM access WHERE UPPER(AccessLevel) = 'ADMIN' AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 2", String.class);
        assumeTrue(admins.size() == 2, "needs two administrators");
        Authentication asAdmin = new TestingAuthenticationToken(admins.get(0), null, "ROLE_ADMIN");
        assertThrows(ResponseStatusException.class, () -> users.issueTemporaryPassword(admins.get(1), null, asAdmin));
    }

    @Test void aTemporaryPasswordOnlyLetsThePersonReplaceIt() {
        users.issueTemporaryPassword(person, null, me());
        var d = details.loadUserByUsername(person);
        assertTrue(d.isCredentialsNonExpired());
        assertTrue(d.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals(UserAccountController.PASSWORD_CHANGE_REQUIRED)));
    }

    @Test void itStopsWorkingOnceItHasExpired() {
        users.issueTemporaryPassword(person, null, me());
        jdbc.update("UPDATE ecis_user_credential SET expires_at = ? WHERE username = ?", Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), person);
        assertFalse(details.loadUserByUsername(person).isCredentialsNonExpired());
        var row = users.users().stream().filter((u) -> u.username().equals(person)).findFirst().orElseThrow();
        assertEquals("EXPIRED", row.passwordState());
    }

    @Test void theListShowsWhenItEndsAndOnlyForTemporaryOnes() {
        var t = users.issueTemporaryPassword(person, null, me());
        var row = users.users().stream().filter((u) -> u.username().equals(person)).findFirst().orElseThrow();
        assertEquals("TEMPORARY", row.passwordState());
        assertEquals(t.expiresAt(), row.tempExpiresAt());
    }

    @Test void replacingItEndsTheTemporaryState() {
        var t = users.issueTemporaryPassword(person, null, me());
        Authentication asPerson = new TestingAuthenticationToken(person, null);
        assertThrows(ResponseStatusException.class, () -> users.changeOwnPassword(new UserAccountController.PasswordChange("not-the-temporary", "A-better-one-2026"), asPerson));
        assertThrows(ResponseStatusException.class, () -> users.changeOwnPassword(new UserAccountController.PasswordChange(t.password(), t.password()), asPerson));
        assertThrows(ResponseStatusException.class, () -> users.changeOwnPassword(new UserAccountController.PasswordChange(t.password(), "short"), asPerson));
        users.changeOwnPassword(new UserAccountController.PasswordChange(t.password(), "A-better-one-2026"), asPerson);
        var row = jdbc.queryForMap("SELECT password_hash, must_change, expires_at, set_by FROM ecis_user_credential WHERE username = ?", person);
        assertTrue(encoder.matches("A-better-one-2026", (String) row.get("password_hash")));
        assertEquals(false, row.get("must_change"));
        assertNull(row.get("expires_at"));
        assertEquals(person, row.get("set_by"));
        assertTrue(details.loadUserByUsername(person).getAuthorities().stream().noneMatch((a) -> a.getAuthority().equals(UserAccountController.PASSWORD_CHANGE_REQUIRED)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'PASSWORD_CHANGED' AND entity_id = ?", Integer.class, person));
    }

    @Test @WithMockUser(username = "tester", roles = "USER") void onlyAdministratorsIssueThem() {
        assertThrows(AccessDeniedException.class, () -> users.issueTemporaryPassword(person, null, me()));
    }

    @Test void generatedPasswordsAreDifferentAndFreeOfLookAlikes() {
        String a = PasswordPolicy.generateTemporary(), b = PasswordPolicy.generateTemporary();
        assertTrue(!a.equals(b) && a.matches("[A-HJ-NP-Za-km-z2-9]{12}"));
    }

    @Test void chosenPasswordsFollowTheRules() {
        assertNotNull(PasswordPolicy.problem("short", "ana", "x"));
        assertNotNull(PasswordPolicy.problem("password123", "ana", "x"));
        assertNotNull(PasswordPolicy.problem("AnaCruz99", "anacruz99", "x"));
        assertNotNull(PasswordPolicy.problem("aaaaaaaa", "ana", "x"));
        assertNotNull(PasswordPolicy.problem("x".repeat(129), "ana", "y"));
        assertNotNull(PasswordPolicy.problem("onlyletters", "ana", "x"));
        assertNotNull(PasswordPolicy.problem("12345678901", "ana", "x"));
        assertNotNull(PasswordPolicy.problem("only letters!!", "ana", "x"));
        assertNull(PasswordPolicy.problem("letters2and3numbers", "ana", "x"));
        assertNull(PasswordPolicy.problem("Mabuhay2026", "ana", "x"));
        assertNull(PasswordPolicy.problem("a long phrase with 1 number", "ana", "x"));
    }
}
