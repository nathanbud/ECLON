package ph.coop.core.member;

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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.member.SlControlController.Change;
import ph.coop.core.member.SlControlController.Save;

/** SL Controls against the database, rolled back: what is shown, a change is saved and audited, a registration then opens only what is switched on, and bad changes are refused. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class SlControlTest {

    @Autowired SlControlController controls;
    @Autowired MemberLifecycleController lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication as(String user, String... auths) {
        Authentication a = new TestingAuthenticationToken(user, "x", auths);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists(SlControlController.TABLE), "V031 (PostgreSQL) or V068 (MySQL) has not been applied");
        assumeTrue(db.tableExists("ecis_member_request"), "V037 has not been applied");
    }

    private static boolean on(SlControlController.Controls c, int slc, int slt, int memberType) {
        return c.classes().stream().flatMap((g) -> g.types().stream()).filter((t) -> t.slc() == slc && t.slt() == slt).findFirst().orElseThrow().on().get(memberType);
    }

    @Test
    void showsWhatOpensForEachMemberType() {
        var c = controls.get(as("tester", "ROLE_ADMIN"));
        assertTrue(c.installed());
        assertEquals(List.of("Regular Individual", "Associate"), c.memberTypes().stream().map(SlControlController.MemberType::name).toList());
        assertEquals(List.of(22, 29, 31), c.classes().stream().map(SlControlController.ClassGroup::slc).toList());
        assertTrue(on(c, 22, 1, 1) && on(c, 29, 5, 1) && on(c, 29, 6, 1) && on(c, 31, 1, 1));
        assertTrue(on(c, 22, 2, 2) && on(c, 31, 3, 2));
        assertFalse(on(c, 29, 6, 2), "HABP does not open for an Associate");
    }

    @Test
    void aSavedChangeIsAuditedAndAppliesToTheNextRegistration() {
        Authentication admin = as("tester", "ROLE_ADMIN");
        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'sl.control.changed'", Integer.class);
        var after = controls.save(new Save(List.of(new Change(1, 29, 6, false)), "HABP made optional by the board"), admin);
        assertFalse(on(after, 29, 6, 1));
        assertEquals(audits + 1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'sl.control.changed'", Integer.class));
        assertEquals("HABP made optional by the board", jdbc.queryForObject("SELECT reason FROM ecis_audit_log WHERE action = 'sl.control.changed' ORDER BY id DESC LIMIT 1", String.class));
        assertEquals(3, controls.accountsFor(1, 1).size());

        // a Regular member registered now gets three accounts, not four
        assumeTrue(db.canInsertInto("client", "sc", "sd", "ecis_member_request"), "the grants of V037 are missing");
        var req = lifecycle.register(new MemberLifecycleController.Register(1, 1, Map.of("LName", "Zzslctl", "FName", "Ana", "DateOfBirth", "1990-02-03", "Gender", "2", "CivilStatus", "1")),
            as("admin", "PERM_MEMBER_EDIT"));
        int id = lifecycle.approve(req.id(), null, as("officer", "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT")).clientId();
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM slmastersum WHERE MBR_CODE = 1 AND MCLIENTID = ?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM osl WHERE OSLBR_CODE = 1 AND ClientIDOSL = ?", Integer.class, id), "MBFP only");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sc WHERE SCBR_CODE = 1 AND ClientIDSC = ?", Integer.class, id));
    }

    @Test
    void aMemberTypeCannotLoseItsShareCapitalAndAReasonIsNeeded() {
        Authentication admin = as("tester", "ROLE_ADMIN");
        var e = assertThrows(ResponseStatusException.class, () -> controls.save(new Save(List.of(new Change(2, 31, 3, false)), "testing"), admin));
        assertTrue(e.getReason().contains("Associate members must keep one share capital account"), e.getReason());
        assertThrows(ResponseStatusException.class, () -> controls.save(new Save(List.of(new Change(1, 29, 6, false)), ""), admin));
        assertTrue(on(controls.get(admin), 29, 6, 1), "nothing was saved");
    }
}
