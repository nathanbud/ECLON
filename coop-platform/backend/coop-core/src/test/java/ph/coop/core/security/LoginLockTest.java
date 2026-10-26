package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ph.coop.core.config.LoginAttempts;

/** The counting rules on their own, then a real sign-in locked out through the security chain (rolled back; the in-memory lock is lifted afterwards). */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoginLockTest {

    private static final String PASSWORD = "Lock-test-pass-9182";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired LoginAttempts attempts;
    @Autowired ph.coop.core.db.Db db;

    private String person;

    @AfterEach
    void lift() {
        if (person != null) attempts.unlock(person);
    }

    /** A clock the test moves by hand. */
    private static final class Hand extends Clock {
        Instant now = Instant.parse("2026-04-24T08:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void fiveWrongPasswordsInTheWindowLockAndTheLockRunsOut() {
        Hand clock = new Hand();
        LoginAttempts a = new LoginAttempts(clock);
        for (int i = 0; i < 4; i++) assertFalse(a.failed("Maria"), "not yet");
        assertFalse(a.isLocked("maria"));
        assertTrue(a.failed("MARIA"), "the fifth locks, whatever case was typed");
        assertTrue(a.isLocked("maria"));
        assertEquals(15 * 60, a.secondsLeft("maria"));
        clock.now = clock.now.plusSeconds(14 * 60);
        assertTrue(a.isLocked("maria"));
        clock.now = clock.now.plusSeconds(61);
        assertFalse(a.isLocked("maria"), "the lock ran out");
        assertFalse(a.failed("maria"), "and the count starts again");
    }

    @Test
    void failuresOutsideTheWindowOrAfterARightPasswordDoNotAdd() {
        Hand clock = new Hand();
        LoginAttempts a = new LoginAttempts(clock);
        for (int i = 0; i < 4; i++) a.failed("jose");
        clock.now = clock.now.plusSeconds(16 * 60);
        assertFalse(a.failed("jose"), "the old ones fell out of the window");
        for (int i = 0; i < 3; i++) a.failed("jose");
        a.succeeded("jose");
        for (int i = 0; i < 4; i++) assertFalse(a.failed("jose"), "a right password cleared the count");
        assertTrue(a.failed("jose"));
        assertTrue(a.unlock("jose"));
        assertFalse(a.isLocked("jose"));
        assertFalse(a.unlock("jose"), "nothing left to lift");
    }

    @Test
    void aLockedNameIsRefusedEvenWithTheRightPasswordUntilAnAdministratorLiftsIt() throws Exception {
        List<String> p = jdbc.queryForList("SELECT AccessUserName FROM access WHERE UPPER(AccessLevel) = 'USER' AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1", String.class);
        assumeTrue(!p.isEmpty());
        person = p.get(0);
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("password_hash", encoder.encode(PASSWORD));
        values.put("set_by", "itest");
        values.put("set_at", Timestamp.valueOf(LocalDateTime.now()));
        values.put("must_change", 0);
        values.put("expires_at", null);
        db.upsert("ecis_user_credential", new java.util.LinkedHashMap<>(java.util.Map.of("username", person)), values);
        attempts.unlock(person);

        mvc.perform(get("/api/me").with(httpBasic(person, PASSWORD))).andExpect(status().isOk());
        for (int i = 0; i < LoginAttempts.MAX_FAILURES; i++)
            mvc.perform(get("/api/me").with(httpBasic(person, "wrong-" + i))).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("X-Auth-Reason"));
        mvc.perform(get("/api/me").with(httpBasic(person, PASSWORD))).andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Auth-Reason", "ACCOUNT_LOCKED")).andExpect(header().exists("Retry-After"));
        assertTrue(attempts.locked().containsKey(person.toLowerCase()));

        assertTrue(attempts.unlock(person));
        mvc.perform(get("/api/me").with(httpBasic(person, PASSWORD))).andExpect(status().isOk());
        // unknown names are counted the same way, so the lock does not show which names exist
        for (int i = 0; i < LoginAttempts.MAX_FAILURES; i++) mvc.perform(post("/api/me/password").with(httpBasic("nobody-of-that-name", "x" + i))).andExpect(status().isUnauthorized());
        assertTrue(attempts.locked().containsKey("nobody-of-that-name"));
        attempts.unlock("nobody-of-that-name");
    }
}
