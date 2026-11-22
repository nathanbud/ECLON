package ph.coop.core.security;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing in to an account an administrator switched off, through the real security chain: with the right password the refusal says why
 * (X-Auth-Reason: ACCOUNT_DISABLED), with a wrong one it says nothing more than for anyone else, so typing a name never reveals the account's state. Rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class DisabledAccountSignInTest {

    private static final String PASSWORD = "Own-secret-1357";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ph.coop.core.db.Db db;

    private String person;

    @BeforeEach
    void ready() {
        List<String> p = jdbc.queryForList("SELECT AccessUserName FROM access WHERE UPPER(AccessLevel) = 'USER' AND COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!p.isEmpty());
        person = p.get(0);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("password_hash", encoder.encode(PASSWORD));
        values.put("set_by", "itest");
        values.put("set_at", Timestamp.valueOf(LocalDateTime.now()));
        values.put("must_change", 0);
        values.put("expires_at", null);
        db.upsert("ecis_user_credential", new LinkedHashMap<>(Map.of("username", person)), values);
    }

    private void switchOff() {
        jdbc.update("UPDATE access SET AccessIsDisabled = 1 WHERE AccessUserName = ?", person);
    }

    @Test void anEnabledAccountSignsIn() throws Exception {
        mvc.perform(get("/api/me").with(httpBasic(person, PASSWORD))).andExpect(status().isOk());
    }

    @Test void aSwitchedOffAccountWithTheRightPasswordIsRefusedWithAReason() throws Exception {
        switchOff();
        mvc.perform(get("/api/me").with(httpBasic(person, PASSWORD))).andExpect(status().isUnauthorized()).andExpect(header().string("X-Auth-Reason", "ACCOUNT_DISABLED"));
    }

    @Test void aSwitchedOffAccountWithAWrongPasswordGetsNoReason() throws Exception {
        switchOff();
        mvc.perform(get("/api/me").with(httpBasic(person, "not-it"))).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("X-Auth-Reason"));
    }

    @Test void anUnknownNameGetsNoReason() throws Exception {
        mvc.perform(get("/api/me").with(httpBasic("no-such-person-xyz", PASSWORD))).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("X-Auth-Reason"));
    }

    @Test void switchingItOnAgainLetsThePersonBackIn() throws Exception {
        switchOff();
        mvc.perform(get("/api/me").with(httpBasic(person, PASSWORD))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE access SET AccessIsDisabled = 0 WHERE AccessUserName = ?", person);
        mvc.perform(get("/api/me").with(httpBasic(person, PASSWORD))).andExpect(status().isOk());
    }
}
