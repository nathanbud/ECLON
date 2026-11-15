package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Settings > Users > Receivables shows the loan permissions as the steps of the loan process, with what each allows here. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "itest", roles = {"ADMIN", "SUPERADMIN"})
class UserLoanProcessTest {

    @Autowired UserProfileController profiles;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theLoanPermissionsFollowTheStepsAndTheReleaseLineFollowsTheCashVoucherRight() {
        List<Map<String, Object>> u = jdbc.queryForList("""
                SELECT a.AccessUserName, a.AccessID, a.AccessBR_CODE FROM access a JOIN access_trans x ON x.BR_CODE = a.AccessBR_CODE AND x.USERID = a.AccessID AND x.TR_CODE = 21
                WHERE COALESCE(a.AccessIsDisabled, 0) = 0 ORDER BY a.AccessID LIMIT 1
                """);
        assumeTrue(!u.isEmpty(), "no enabled user with a Cash Voucher row");
        String name = (String) u.get(0).get("AccessUserName");
        int id = ((Number) u.get(0).get("AccessID")).intValue(), branch = ((Number) u.get(0).get("AccessBR_CODE")).intValue();
        var auth = SecurityContextHolder.getContext().getAuthentication();

        var receivables = profiles.profile(name, auth).getBody().tabs().get("receivables");
        assertEquals(List.of("Loan process", "Loan setup (old system)", "Loan applications (old system)", "Statements"), receivables.stream().map((s) -> s.title()).toList());
        var steps = receivables.get(0).fields();
        assertEquals(List.of("1 · Profiling", "2 · Deduction", "3 · Verification", "4 · Approval", "5 · Loan Release"), steps.stream().map((f) -> f.label()).toList());
        assertEquals(List.of("AccessCanAddLoanApplication", "AccessCanDoLoanDeduction", "AccessCanVerifyLoanApplication", "AccessCanApproveLoanApplication"), steps.subList(0, 4).stream().map((f) -> f.key()).toList(),
            "the same switches the loan steps check");
        steps.forEach((f) -> assertNotNull(f.hint(), f.label() + " says what it allows"));
        assertTrue(receivables.get(2).fields().stream().allMatch((f) -> f.hint().startsWith("Used by the old system only")));

        jdbc.update("UPDATE access_trans SET CAN_NEW = 0 WHERE BR_CODE = ? AND USERID = ? AND TR_CODE = 21", branch, id);
        var release = profiles.profile(name, auth).getBody().tabs().get("receivables").get(0).fields().get(4);
        assertEquals("note", release.kind());
        assertTrue(release.value().startsWith("Not allowed"), release.value());
        jdbc.update("UPDATE access_trans SET CAN_NEW = 1, CAN_FIND = 1 WHERE BR_CODE = ? AND USERID = ? AND TR_CODE = 21", branch, id);
        assertEquals("Allowed (Cash Voucher: New and Find).", profiles.profile(name, auth).getBody().tabs().get("receivables").get(0).fields().get(4).value());
    }
}
