package ph.coop.core.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import ph.coop.core.member.MemberLifecycleController;

/** Offices against the database, rolled back: the list with who uses each office, add, rename and delete audited, refusals, and a registration naming a missing office. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class OfficeControllerTest {

    @Autowired OfficeController offices;
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
        as("tester", "ROLE_ADMIN");
        assumeTrue(offices.list().editable(), "V035 (PostgreSQL) or V072 (MySQL) has not been applied");
        assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM restrik WHERE RestrikID = 1", Integer.class) > 0, "a database with office 1 (coop)");
    }

    private int audits(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = ?", Integer.class, action);
    }

    @Test
    void listsTheOfficesWithTheMembersAndUsersOfEach() {
        var list = offices.list();
        var main = list.offices().stream().filter((o) -> o.id() == 1).findFirst().orElseThrow();
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE RestrikID = 1", Integer.class) + (db.tableExists("client_temp")
            ? jdbc.queryForObject("SELECT COUNT(*) FROM client_temp WHERE RestrikID = 1", Integer.class) : 0), main.members());
        assertTrue(list.nextNumber() > list.offices().stream().mapToInt(OfficeController.Row::id).max().orElse(0));
        assertTrue(list.maxNameLength() > 0);
    }

    @Test
    void addRenameAndDeleteAreAudited() {
        Authentication admin = as("tester", "ROLE_ADMIN");
        int add = audits("office.add"), rename = audits("office.rename"), delete = audits("office.delete");
        int id = offices.list().nextNumber();
        var after = offices.add(new OfficeController.Add(id, "  Zztest Satellite ", "Opened for the test"), admin);
        assertEquals("Zztest Satellite", after.offices().stream().filter((o) -> o.id() == id).findFirst().orElseThrow().name());
        assertEquals(add + 1, audits("office.add"));

        offices.rename(id, new OfficeController.Rename("Zztest Branch", "Renamed for the test"), admin);
        assertEquals("Zztest Branch", jdbc.queryForObject("SELECT RestrikDesc FROM restrik WHERE RestrikID = ?", String.class, id));
        assertEquals(rename + 1, audits("office.rename"));
        Map<String, Object> row = jdbc.queryForMap("SELECT prev_value, new_value, reason FROM ecis_audit_log WHERE action = 'office.rename' ORDER BY id DESC LIMIT 1");
        assertEquals("Zztest Satellite", row.get("prev_value"));
        assertEquals("Zztest Branch", row.get("new_value"));

        offices.delete(id, new OfficeController.Delete("Closed after the test"), admin);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM restrik WHERE RestrikID = ?", Integer.class, id));
        assertEquals(delete + 1, audits("office.delete"));
    }

    @Test
    void refusesATakenNumberAndAnOfficeInUse() {
        Authentication admin = as("tester", "ROLE_ADMIN");
        var taken = assertThrows(ResponseStatusException.class, () -> offices.add(new OfficeController.Add(1, "Another", "Test"), admin));
        assertTrue(taken.getReason().contains("already used by"), taken.getReason());
        var used = offices.list().offices().stream().filter((o) -> o.id() != 1 && (o.members() > 0 || o.users() > 0)).findFirst();
        assumeTrue(used.isPresent(), "an office other than 1 that members or users belong to");
        var inUse = assertThrows(ResponseStatusException.class, () -> offices.delete(used.get().id(), new OfficeController.Delete("Test"), admin));
        assertTrue(inUse.getReason().contains("still belong to it"), inUse.getReason());
    }

    @Test
    void aRegistrationWhoseOfficeWasDeletedBeforeApprovalIsRefusedClearly() {
        assumeTrue(db.canInsertInto("client", "sc", "sd", "ecis_member_request"), "the grants of V037 are missing");
        Authentication admin = as("tester", "ROLE_ADMIN");
        int office = offices.list().nextNumber();
        offices.add(new OfficeController.Add(office, "Zztest Satellite", "Opened for the test"), admin);
        var req = lifecycle.register(new MemberLifecycleController.Register(1, 1, Map.of("LName", "Zzoffice", "FName", "Ana", "DateOfBirth", "1990-02-03", "Gender", "2",
            "CivilStatus", "1", "RestrikID", String.valueOf(office))), as("admin", "PERM_MEMBER_EDIT"));
        // a waiting request does not hold the office, so it can be deleted before the request is approved
        offices.delete(office, new OfficeController.Delete("Closed before the approval"), as("tester", "ROLE_ADMIN"));
        var e = assertThrows(ResponseStatusException.class, () -> lifecycle.approve(req.id(), null, as("officer", "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT")));
        assertTrue(e.getReason().contains("no office with that number") && e.getReason().contains("Settings > Organization > Offices"), e.getReason());
    }
}
