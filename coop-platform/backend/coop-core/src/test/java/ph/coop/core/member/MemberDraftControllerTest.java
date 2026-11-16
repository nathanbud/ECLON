package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** New-member drafts: saved, listed, opened, changed and deleted by their owner only, with sensible limits, and audited without the typed values. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class MemberDraftControllerTest {

    @Autowired MemberDraftController ctl;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication who(String name) {
        Authentication a = new TestingAuthenticationToken(name, "x", "PERM_MEMBER_EDIT");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_member_draft"), "V019 / V056 has not been applied");
    }

    private static Map<String, String> fields(String last, String first) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("LName", last);
        f.put("FName", first);
        f.put("DateOfBirth", "1990-02-03");
        f.put("ResAddStreet", "   ");
        return f;
    }

    @Test
    void titleIsTheNameTypedSoFar() {
        assertEquals("Cruz, Ana Maria", MemberDraftController.titleOf(Map.of("LName", "Cruz", "FName", "Ana", "MName", "Maria")));
        assertEquals("Cruz", MemberDraftController.titleOf(Map.of("LName", "Cruz")));
        assertEquals("(no name yet)", MemberDraftController.titleOf(Map.of("Gender", "1")));
    }

    @Test
    void saveListOpenChangeAndDelete() {
        var a = who("draftmaker-a");
        var saved = ctl.create(new MemberDraftController.Save(1, 2, fields("Dela Cruz", "Juan")), a);
        assertEquals("Dela Cruz, Juan", saved.title());
        assertEquals(2, saved.clientType());

        var opened = ctl.open(saved.id(), a);
        assertEquals("1990-02-03", opened.fields().get("DateOfBirth"));
        assertTrue(!opened.fields().containsKey("ResAddStreet"), "blank values are not kept");

        ctl.update(saved.id(), new MemberDraftController.Save(1, 1, fields("Dela Cruz", "Juana")), a);
        assertEquals("Dela Cruz, Juana", ctl.mine(a).get(0).title());
        assertEquals(1, ctl.mine(a).size());

        ctl.delete(saved.id(), a);
        assertEquals(0, ctl.mine(a).size());
    }

    @Test
    void aDraftIsPrivateToItsOwner() {
        var a = who("draftmaker-a");
        var b = who("draftmaker-b");
        long id = ctl.create(new MemberDraftController.Save(1, 1, fields("Reyes", "Maria")), a).id();
        assertEquals(0, ctl.mine(b).size());
        assertThrows(ResponseStatusException.class, () -> ctl.open(id, b));
        assertThrows(ResponseStatusException.class, () -> ctl.update(id, new MemberDraftController.Save(1, 1, fields("X", "Y")), b));
        assertThrows(ResponseStatusException.class, () -> ctl.delete(id, b));
        assertEquals("Reyes, Maria", ctl.open(id, a).title());
    }

    @Test
    void emptyOrOversizedDraftsAreRefused() {
        var a = who("draftmaker-a");
        assertThrows(ResponseStatusException.class, () -> ctl.create(new MemberDraftController.Save(1, 1, Map.of()), a));
        assertThrows(ResponseStatusException.class, () -> ctl.create(new MemberDraftController.Save(1, 1, Map.of("LName", "  ")), a));
        assertThrows(ResponseStatusException.class, () -> ctl.create(new MemberDraftController.Save(1, 1, Map.of("LName", "x".repeat(MemberDraftController.MAX_VALUE + 1))), a));
        assertThrows(ResponseStatusException.class, () -> ctl.create(new MemberDraftController.Save(1, 1, Map.of("bad name; DROP", "x")), a));
    }

    @Test
    void savingIsAuditedWithoutTheTypedValues() {
        var a = who("draftmaker-a");
        long id = ctl.create(new MemberDraftController.Save(1, 1, fields("Santos", "Pedro")), a).id();
        var row = jdbc.queryForMap("SELECT action, new_value, reason FROM ecis_audit_log WHERE action = 'member.draft.save' AND entity_id = ? ORDER BY id DESC LIMIT 1", String.valueOf(id));
        assertTrue(String.valueOf(row.get("reason")).contains("draft"));
        assertTrue(!String.valueOf(row).contains("Santos"), "the name is not written to the audit log");
    }
}
