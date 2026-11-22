package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/** Member defaults against the database, rolled back: what applies on coop, a change audited, and a registration with no default department refused clearly. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class MemberDefaultsTest {

    @Autowired MemberDefaultsController controller;
    @Autowired MemberDefaults defaults;
    @Autowired MemberLifecycleController lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private static Authentication as(String user, String... auths) {
        Authentication a = new TestingAuthenticationToken(user, "x", auths);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_setting"), "the settings table");
        assumeTrue(defaults.department() != null && defaults.department() == 2, "coop, whose defaults V037 stored");
    }

    @Test
    void coopKeepsWhatWasBuiltIn() {
        var a = controller.applies();
        assertEquals(2, a.department());
        assertEquals(1, a.office());
        assertEquals(List.of(1, 2), a.types().stream().map(MemberDefaultsController.Choice::code).toList());
        assertFalse(controller.setup(as("admin", "ROLE_ADMIN")).editable(), "an administrator only sees them");
        assertTrue(controller.setup(as("boss", "ROLE_ADMIN", "ROLE_SUPERADMIN")).editable());
    }

    @Test
    void aChangeIsAuditedAndAppliesToTheNextRegistration() {
        assumeTrue(db.canInsertInto("client", "sc", "sd", "ecis_member_request"), "the grants of V037 are missing");
        Authentication boss = as("boss", "ROLE_ADMIN", "ROLE_SUPERADMIN");
        int audits = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'membership.defaults.changed'", Integer.class);
        controller.save(new MemberDefaultsController.Change(null, 1, List.of(1), "The registration must choose the department"), boss);
        assertNull(defaults.department());
        assertEquals(Map.of(1, "Regular Individual"), defaults.types());
        assertEquals(audits + 2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'membership.defaults.changed'", Integer.class), "department and types changed");

        var e = assertThrows(ResponseStatusException.class, () -> lifecycle.register(new MemberLifecycleController.Register(1, 2,
            Map.of("LName", "Zzdefaults", "FName", "Ana", "DateOfBirth", "1990-02-03", "Gender", "2", "CivilStatus", "1")), as("admin", "PERM_MEMBER_EDIT")));
        assertTrue(e.getReason().contains("Choose Regular Individual"), "Associate is no longer registered here: " + e.getReason());

        var req = lifecycle.register(new MemberLifecycleController.Register(1, 1,
            Map.of("LName", "Zzdefaults", "FName", "Ana", "DateOfBirth", "1990-02-03", "Gender", "2", "CivilStatus", "1")), as("admin", "PERM_MEMBER_EDIT"));
        var refused = assertThrows(ResponseStatusException.class, () -> lifecycle.approve(req.id(), null, as("officer", "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT")));
        assertTrue(refused.getReason().contains("has not chosen the department"), refused.getReason());
    }

    @Test
    void choicesMustExistAndAtLeastOneMemberType() {
        Authentication boss = as("boss", "ROLE_ADMIN", "ROLE_SUPERADMIN");
        var e = assertThrows(ResponseStatusException.class, () -> controller.save(new MemberDefaultsController.Change(9999, 1, List.of(), "Test"), boss));
        assertTrue(e.getReason().contains("Department 9999 is not on this database") && e.getReason().contains("at least one member type"), e.getReason());
    }
}
