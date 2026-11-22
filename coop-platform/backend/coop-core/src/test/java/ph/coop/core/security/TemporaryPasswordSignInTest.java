package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Signing in with a temporary password through the real security chain: expired is refused with a reason, valid opens nothing but the password change. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class TemporaryPasswordSignInTest {

    private static final String TEMP = "Tmp-pass-2468";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ph.coop.core.db.Db db;

    private String person;
    private int changesBefore;

    @BeforeEach
    void ready() {
        List<String> p = jdbc.queryForList("SELECT AccessUserName FROM access WHERE UPPER(AccessLevel) = 'USER' AND COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!p.isEmpty());
        person = p.get(0);
        changesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'PASSWORD_CHANGED' AND entity_id = ?", Integer.class, person);
    }

    private void issue(LocalDateTime expires) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("password_hash", encoder.encode(TEMP));
        values.put("set_by", "itest");
        values.put("set_at", Timestamp.valueOf(LocalDateTime.now()));
        values.put("must_change", 1);
        values.put("expires_at", Timestamp.valueOf(expires));
        db.upsert("ecis_user_credential", new java.util.LinkedHashMap<>(java.util.Map.of("username", person)), values);
    }

    @Test void anExpiredTemporaryPasswordIsRefusedWithAReason() throws Exception {
        issue(LocalDateTime.now().minusMinutes(5));
        mvc.perform(get("/api/me").with(httpBasic(person, TEMP))).andExpect(status().isUnauthorized()).andExpect(header().string("X-Auth-Reason", "TEMPORARY_PASSWORD_EXPIRED"));
    }

    @Test void aWrongPasswordGetsNoReason() throws Exception {
        issue(LocalDateTime.now().minusMinutes(5));
        mvc.perform(get("/api/me").with(httpBasic(person, "not-it"))).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("X-Auth-Reason"));
    }

    @Test void aValidTemporaryPasswordOpensNothingButTheChange() throws Exception {
        issue(LocalDateTime.now().plusHours(2));
        mvc.perform(get("/api/me").with(httpBasic(person, TEMP))).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/members?q=a&page=0&size=1").with(httpBasic(person, TEMP))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/accounts?branchCode=1").with(httpBasic(person, TEMP))).andExpect(status().isForbidden());
    }

    @Test void afterTheChangeEverythingOpensAgain() throws Exception {
        issue(LocalDateTime.now().plusHours(2));
        mvc.perform(post("/api/me/password").with(httpBasic(person, TEMP)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"current\":\"" + TEMP + "\",\"next\":\"My-own-secret-77\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/me").with(httpBasic(person, "My-own-secret-77"))).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(false));
        mvc.perform(get("/api/accounts?branchCode=1").with(httpBasic(person, "My-own-secret-77"))).andExpect(status().isOk());
        // the temporary one is dead
        mvc.perform(get("/api/me").with(httpBasic(person, TEMP))).andExpect(status().isUnauthorized());
        // one more than before: the person may have changed their password for real earlier
        assertEquals(changesBefore + 1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'PASSWORD_CHANGED' AND entity_id = ?", Integer.class, person));
    }
}
