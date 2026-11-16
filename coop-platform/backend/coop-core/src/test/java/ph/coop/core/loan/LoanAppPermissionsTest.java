package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/** The loan form asks up front whether the person may add an application: the old system's flags, read as they are. Read only. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class LoanAppPermissionsTest {

    @Autowired LoanApplicationWorkflowController workflow;
    @Autowired JdbcTemplate jdbc;

    private void check(int flag) {
        List<Map<String, Object>> u = jdbc.queryForList("""
                SELECT AccessUserName, COALESCE(AccessCanVerifyLoanApplication, 0) AS v, COALESCE(AccessCanApproveLoanApplication, 0) AS a FROM access
                WHERE COALESCE(AccessIsDisabled, 0) = 0 AND COALESCE(AccessCanAddLoanApplication, 0) = ? ORDER BY AccessID LIMIT 1
                """, flag);
        assumeTrue(!u.isEmpty(), "no enabled user with the flag " + flag);
        var p = workflow.permissions(new TestingAuthenticationToken(u.get(0).get("AccessUserName"), null));
        assertEquals(flag == 1, p.canAdd());
        assertEquals(((Number) u.get(0).get("v")).intValue() == 1, p.canVerify());
        assertEquals(((Number) u.get(0).get("a")).intValue() == 1, p.canApprove());
    }

    @Test void aPersonWhoMayAddIsToldSo() { check(1); }

    @Test void aPersonWhoMayNotIsToldSo() { check(0); }

    /** 5 · Loan Release is New on the Cash Voucher (type 21) with front office access, the right the release screen checks. */
    private void checkRelease(boolean may) {
        List<String> u = jdbc.queryForList("""
                SELECT e.AccessUserName FROM access e
                WHERE COALESCE(e.AccessIsDisabled, 0) = 0
                  AND (CASE WHEN COALESCE(e.AccessCanAccessFrontOffice, 0) = 1 AND EXISTS (SELECT 1 FROM access_trans t WHERE t.BR_CODE = e.AccessBR_CODE
                       AND t.USERID = e.AccessID AND t.TR_CODE = 21 AND t.CAN_NEW = 1) THEN 1 ELSE 0 END) = ?
                ORDER BY e.AccessID LIMIT 1
                """, String.class, may ? 1 : 0);
        assumeTrue(!u.isEmpty(), "no enabled user " + (may ? "with" : "without") + " the Cash Voucher right");
        assertEquals(may, workflow.permissions(new TestingAuthenticationToken(u.get(0), null)).canRelease());
    }

    @Test void aPersonWithTheCashVoucherRightMayRelease() { checkRelease(true); }

    @Test void aPersonWithoutItMayNotRelease() { checkRelease(false); }

    @Test void anAccountOutsideTheOldSystemIsRefused() {
        assertThrows(ResponseStatusException.class, () -> workflow.permissions(new TestingAuthenticationToken("no-such-person-xyz", null)));
    }
}
