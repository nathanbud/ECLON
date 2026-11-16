package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import ph.coop.core.audit.AuditLog;

/** The signed-in person's access as their window sees it, and how a change by an administrator shows up. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class MyAccessTest {

    @Autowired MyAccessController access;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anAdministratorsChangeChangesTheFingerprintAndIsDescribedWithWhoMadeIt() {
        List<Map<String, Object>> u = jdbc.queryForList("""
                SELECT AccessUserName, AccessID FROM access WHERE COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessCanAddLoanApplication, 0) = 0
                  AND AccessLevel <> 'SUPERADMIN' ORDER BY AccessID LIMIT 1
                """);
        assumeTrue(!u.isEmpty(), "no enabled user without Add loan application");
        String name = (String) u.get(0).get("AccessUserName");
        Authentication me = new TestingAuthenticationToken(name, null);

        var first = access.access(null, me);
        assertNotNull(first.items());
        assertFalse(first.changed());
        var flag = first.items().stream().filter((i) -> i.key().equals("AccessCanAddLoanApplication")).findFirst().orElseThrow();
        assertEquals("No", flag.value());
        assertEquals("Receivables · Loan process", flag.group());
        assertEquals("1 · Profiling", flag.label());
        assertTrue(first.items().stream().anyMatch((i) -> i.key().startsWith("TRANS:")) || first.items().size() > 50, "transaction rights are listed too");

        var same = access.access(first.fingerprint(), me);
        assertNull(same.items(), "nothing changed: only the fingerprint comes back");

        jdbc.update("UPDATE access SET AccessCanAddLoanApplication = 1 WHERE AccessUserName = ?", name);
        AuditLog.write(jdbc, "admin-itest", "UPDATE:AccessCanAddLoanApplication", "user", name, "0", "1", null);
        var after = access.access(first.fingerprint(), me);
        assertTrue(after.changed());
        assertNotEquals(first.fingerprint(), after.fingerprint());
        assertEquals("Yes", after.items().stream().filter((i) -> i.key().equals("AccessCanAddLoanApplication")).findFirst().orElseThrow().value());
        assertEquals("admin-itest", after.lastChange().by());
    }

    @Autowired ph.coop.core.member.LookupController lookups;

    @Test
    void anAssignmentOrDepartmentChangeIsShownInWords() {
        var depts = lookups.all().get("department");
        assumeTrue(depts != null && depts.size() >= 2, "fewer than two departments");
        List<Map<String, Object>> u = jdbc.queryForList("SELECT AccessUserName, COALESCE(AccessDepartment, 0) AS d FROM access WHERE COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessID LIMIT 1");
        assumeTrue(!u.isEmpty(), "no enabled user");
        String name = (String) u.get(0).get("AccessUserName");
        int was = ((Number) u.get(0).get("d")).intValue();
        int other = depts.keySet().stream().filter((k) -> k != was).findFirst().orElseThrow();
        Authentication me = new TestingAuthenticationToken(name, null);
        var first = access.access(null, me);
        assertTrue(first.items().stream().anyMatch((i) -> i.key().equals("AccessCurrAssign")), "the assignment is listed");
        assertTrue(first.items().stream().noneMatch((i) -> i.key().equals("AccessUserName") || i.key().equals("AccessFName")), "who the person is is not access");

        jdbc.update("UPDATE access SET AccessDepartment = ? WHERE AccessUserName = ?", other, name);
        var after = access.access(first.fingerprint(), me);
        var dept = after.items().stream().filter((i) -> i.key().equals("AccessDepartment")).findFirst().orElseThrow();
        assertEquals(depts.get(other).trim(), dept.value(), "the department's name, not its number");
        assertEquals("General · Access", dept.group());
    }

    private static void assertNotNull(Object o) {
        assertTrue(o != null);
    }
}
