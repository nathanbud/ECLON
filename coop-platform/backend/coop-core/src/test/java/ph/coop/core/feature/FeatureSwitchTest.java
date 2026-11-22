package ph.coop.core.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ph.coop.core.db.Db;
import ph.coop.core.member.SlControlController;

/**
 * Switching an area off, against the database and rolled back: audited, refused through the real security chain with the reason, listed in /api/me, and new
 * members no longer get its accounts. Only a super administrator switches.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-pass", "coop.drawer-snapshot.on-start=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class FeatureSwitchTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureController controller;
    @Autowired Features features;
    @Autowired SlControlController slControls;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private static Authentication superadmin() {
        Authentication a = new TestingAuthenticationToken("boss", "x", "ROLE_ADMIN", "ROLE_SUPERADMIN");
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_setting"), "the settings table");
        assumeTrue(features.on("savings"), "savings is on in this database (coop)");
        features.forget();
    }

    @AfterEach
    void forgetAgain() {
        features.forget(); // the switch is rolled back with the test; the cache must not keep it
    }

    @Test
    void switchingSavingsOffHidesAndRefusesItAndNewMembersGetNoSavings() throws Exception {
        assumeTrue(slControls.classesFor(1, 1).contains(22), "Regular members get savings here");
        int audits = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'feature.switched'", Integer.class);
        controller.save(new FeatureController.Save(Map.of("savings", false), "This cooperative offers no savings"), superadmin());
        assertEquals(audits + 1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'feature.switched'", Integer.class));
        assertFalse(features.on("savings"));
        assertTrue(!slControls.classesFor(1, 1).contains(22), "no savings account opens");
        assertTrue(slControls.classesFor(1, 1).contains(31), "share capital still opens");

        mvc.perform(get("/api/members/1/1/savings").with(httpBasic("itest", "itest-pass")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(FeatureGuard.CODE)).andExpect(jsonPath("$.feature").value("savings"));
        mvc.perform(get("/api/me").with(httpBasic("itest", "itest-pass"))).andExpect(status().isOk()).andExpect(jsonPath("$.featuresOff[0]").value("savings"));
        mvc.perform(get("/api/members/1/1/shares").with(httpBasic("itest", "itest-pass"))).andExpect(status().is2xxSuccessful());
    }

    @Test
    void onlyASuperAdministratorSwitches() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", "x", "ROLE_ADMIN"));
        assertThrows(AccessDeniedException.class, () -> controller.save(new FeatureController.Save(Map.of("savings", false), "Not allowed"),
            SecurityContextHolder.getContext().getAuthentication()));
    }
}
