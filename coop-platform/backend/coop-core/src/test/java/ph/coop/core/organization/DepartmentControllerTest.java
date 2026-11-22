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

/** Departments against the database, rolled back: add, change, switch off and delete audited; a used department refused; a registration whose department is missing. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false", "coop.edit.enabled=true"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class DepartmentControllerTest {

    @Autowired DepartmentController departments;
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
        assumeTrue(departments.list().editable(), "V036 (PostgreSQL) or V073 (MySQL) has not been applied");
        assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM dept WHERE DeptID = 2", Integer.class) > 0, "a database with department 2 (coop)");
    }

    private int audits(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = ?", Integer.class, action);
    }

    @Test
    void addChangeSwitchOffAndDeleteAreAudited() {
        Authentication admin = as("tester", "ROLE_ADMIN");
        int id = departments.list().nextNumber();
        int add = audits("department.add"), change = audits("department.change"), off = audits("department.switch-off"), del = audits("department.delete");
        departments.add(new DepartmentController.Change(id, "ZZTEST SATELLITE", 2, null, null, "Opened for the test"), admin);
        departments.change(id, new DepartmentController.Change(null, "ZZTEST BRANCH", 2, "000-000-000-000", "Test street", "Renamed for the test"), admin);
        var row = departments.list().departments().stream().filter((d) -> d.id() == id).findFirst().orElseThrow();
        assertEquals("ZZTEST BRANCH", row.name());
        assertEquals("MAIN OFFICE", row.parentName());
        departments.switchOnOff(id, new DepartmentController.Switch(true, "Closed for the test"), admin);
        assertTrue(departments.list().departments().stream().filter((d) -> d.id() == id).findFirst().orElseThrow().disabled());
        departments.delete(id, new DepartmentController.Delete("Removed after the test"), admin);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dept WHERE DeptID = ?", Integer.class, id));
        assertEquals(add + 1, audits("department.add"));
        assertEquals(change + 1, audits("department.change"));
        assertEquals(off + 1, audits("department.switch-off"));
        assertEquals(del + 1, audits("department.delete"));
    }

    @Test
    void aDepartmentTheBooksUseCannotBeDeleted() {
        var used = departments.list().departments().stream().filter((d) -> d.id() == 3).findFirst();
        assumeTrue(used.isPresent(), "department 3 (coop)");
        var e = assertThrows(ResponseStatusException.class, () -> departments.delete(3, new DepartmentController.Delete("Test"), as("tester", "ROLE_ADMIN")));
        assertTrue(e.getReason().contains("Switch it off instead"), e.getReason());
    }

    @Test
    void aRegistrationWhoseDepartmentWasDeletedBeforeApprovalNamesItAndWhereToAddIt() {
        assumeTrue(db.canInsertInto("client", "sc", "sd", "ecis_member_request"), "the grants of V037 are missing");
        Authentication admin = as("tester", "ROLE_ADMIN");
        int id = departments.list().nextNumber();
        departments.add(new DepartmentController.Change(id, "ZZTEST DEPT", null, null, null, "For the test"), admin);
        var req = lifecycle.register(new MemberLifecycleController.Register(1, 1, Map.of("LName", "Zzdept", "FName", "Ana", "DateOfBirth", "1990-02-03", "Gender", "2",
            "CivilStatus", "1", "ClientDEPT", String.valueOf(id))), as("admin", "PERM_MEMBER_EDIT"));
        departments.delete(id, new DepartmentController.Delete("Removed before the approval"), as("tester", "ROLE_ADMIN"));
        var e = assertThrows(ResponseStatusException.class, () -> lifecycle.approve(req.id(), null, as("officer", "ROLE_MEMBERSHIP_OFFICER", "PERM_MEMBER_EDIT")));
        assertTrue(e.getReason().contains("no department with that number") && e.getReason().contains("Settings > Organization > Departments"), e.getReason());
    }
}
