package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * An approved application says where its release stands (the release voucher's state, and the day the loan was released), for everyone who may
 * work on applications, and the waiting list carries approved ones until a while after release. Read only.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class LoanReleaseStatusTest {

    @Autowired LoanApplicationWorkflowController workflow;
    @Autowired JdbcTemplate jdbc;

    /** A person who may work on applications, in the given branch. */
    private Authentication staff(int branch) {
        List<String> u = jdbc.queryForList("""
                SELECT AccessUserName FROM access WHERE COALESCE(AccessIsDisabled, 0) = 0 AND AccessBR_CODE = ?
                  AND (COALESCE(AccessCanAddLoanApplication, 0) = 1 OR COALESCE(AccessCanVerifyLoanApplication, 0) = 1 OR COALESCE(AccessCanApproveLoanApplication, 0) = 1)
                ORDER BY AccessID LIMIT 1
                """, String.class, branch);
        assumeTrue(!u.isEmpty(), "no one who may work on loan applications");
        return new TestingAuthenticationToken(u.get(0), null);
    }

    @Test
    void anApprovedApplicationWhoseVoucherIsPostedIsReleasedWithItsDay() {
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT a.BR_CODE, a.ID, a.APPRV_DATE FROM loanapp a
                WHERE a.STATUSID = 3 AND a.TR_CTLNO IS NOT NULL
                  AND EXISTS (SELECT 1 FROM transdt d WHERE d.DTTR_BRCODE = a.BR_CODE AND d.DTTR_MODULE = 2 AND d.DTTR_CODE = 21 AND d.DTTR_CTLNO = a.TR_CTLNO AND d.DTUPD_TAG = 1)
                ORDER BY a.ID DESC LIMIT 1
                """);
        assumeTrue(!a.isEmpty(), "no approved application with a posted release voucher");
        int branch = ((Number) a.get(0).get("BR_CODE")).intValue();
        var item = workflow.detail(((Number) a.get(0).get("ID")).longValue(), staff(branch)).item();
        assertEquals("POSTED", item.releaseStatus());
        assertEquals(a.get(0).get("APPRV_DATE").toString(), item.approvedOn());
        assertNotNull(item.releasedOn(), "the release day comes from the loan's release row in the member ledger");
        assertFalse(LocalDate.parse(item.releasedOn()).isBefore(LocalDate.parse(item.approvedOn())), "released on or after the day it was approved");
    }

    @Test
    void anApplicationNotYetApprovedHasNoReleaseState() {
        List<Map<String, Object>> a = jdbc.queryForList("SELECT BR_CODE, ID FROM loanapp WHERE STATUSID IN (1, 2) ORDER BY ID DESC LIMIT 1");
        assumeTrue(!a.isEmpty(), "no new or verified application");
        var item = workflow.detail(((Number) a.get(0).get("ID")).longValue(), staff(((Number) a.get(0).get("BR_CODE")).intValue())).item();
        assertNull(item.approvedOn());
        assertNull(item.releaseStatus());
        assertNull(item.releasedOn());
    }

    @Test
    void theListKeepsApprovedOnesOnlyUntilAWhileAfterRelease() {
        List<Integer> branches = jdbc.queryForList("SELECT DISTINCT BR_CODE FROM loanapp ORDER BY BR_CODE LIMIT 1", Integer.class);
        assumeTrue(!branches.isEmpty(), "no loan applications");
        LocalDate releasedSince = LocalDate.now().minusDays(LoanApplicationWorkflowController.RELEASED_SHOWN_DAYS);
        for (var it : workflow.pending(staff(branches.get(0)))) {
            assertTrue(it.status() == 1 || it.status() == 2 || it.status() == 3, "only applications on their way: " + it.status());
            if (it.status() != 3) continue;
            assertNotNull(it.approvedOn());
            if ("POSTED".equals(it.releaseStatus())) {
                assertNotNull(it.releasedOn());
                assertFalse(LocalDate.parse(it.releasedOn()).isBefore(releasedSince), "released more than " + LoanApplicationWorkflowController.RELEASED_SHOWN_DAYS + " days ago");
            }
        }
    }
}
