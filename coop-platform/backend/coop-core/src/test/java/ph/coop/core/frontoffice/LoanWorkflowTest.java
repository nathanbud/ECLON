package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.loan.LoanApplicationController;
import ph.coop.core.loan.LoanApplicationController.Create;
import ph.coop.core.loan.LoanApplicationController.Created;
import ph.coop.core.loan.LoanApplicationController.DeductionAmount;
import ph.coop.core.loan.LoanApplicationWorkflowController;
import ph.coop.core.loan.LoanApplicationWorkflowController.Approved;
import ph.coop.core.loan.LoanApplicationWorkflowController.Reason;

/**
 * The whole loan chain through the real code at a fixed date, rolled back: create the application, verify it, approve it (which creates the release
 * voucher), approve and post the voucher. Three people play the parts; their access flags are set inside the transaction. Needs V016 and V017;
 * skips when either, or three people with "new" rights on type 21, are missing. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(LoanWorkflowTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoanWorkflowTest {

    static final LocalDate TODAY = LocalDate.of(2026, 4, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(TODAY, java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired LoanApplicationController applications;
    @Autowired LoanApplicationWorkflowController workflow;
    @Autowired LoanReleaseController releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private Authentication encoder, verifier, approver;
    private int[] ids = new int[3];
    private int client;
    private long feeRow;

    @BeforeEach
    void setUp() {
        assumeTrue(db.canInsertInto("loanapp", "loan", "amortsched", "loandeduc", "comaker", "slmastersum") && db.tableExists("ecis_single_hand"), "V016 or V017 has not been applied");
        try {
            jdbc.update("UPDATE loanapp SET STATUSID = STATUSID WHERE ID = -1");
            jdbc.update("UPDATE transdt SET DTPOSTBY = DTPOSTBY WHERE DTTR_YEAR = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the UPDATE grants (V015, V017) are missing");
        }
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT a.AccessID, a.AccessUserName FROM access a
                JOIN access_trans x ON x.USERID = a.AccessID AND x.BR_CODE = a.AccessBR_CODE AND x.TR_CODE = 21 AND x.CAN_NEW = 1
                WHERE a.AccessBR_CODE = 1 AND COALESCE(a.AccessIsDisabled, 0) = 0 AND COALESCE(a.AccessCanAccessFrontOffice, 0) = 1 AND COALESCE(a.AccessDepartment, 0) > 0
                ORDER BY a.AccessID LIMIT 3
                """);
        assumeTrue(people.size() == 3, "fewer than three people may add vouchers of type 21");
        for (int i = 0; i < 3; i++) ids[i] = ((Number) people.get(i).get("AccessID")).intValue();
        encoder = new TestingAuthenticationToken((String) people.get(0).get("AccessUserName"), "x");
        verifier = new TestingAuthenticationToken((String) people.get(1).get("AccessUserName"), "x");
        approver = new TestingAuthenticationToken((String) people.get(2).get("AccessUserName"), "x");
        try {
            // everyone may do every step of the application; who actually may is decided by the rules under test
            jdbc.update("UPDATE access SET AccessCanAddLoanApplication = 1, AccessCanVerifyLoanApplication = 1, AccessCanApproveLoanApplication = 1 WHERE AccessBR_CODE = 1 AND AccessID IN (?, ?, ?)", ids[0], ids[1], ids[2]);
        } catch (RuntimeException e) {
            assumeTrue(false, "the access flags cannot be changed by this database user");
        }
        List<Map<String, Object>> m = jdbc.queryForList("SELECT MCLIENTID c FROM slmastersum WHERE MBR_CODE = 1 AND MSLC_CODE = 29 AND MSLT_CODE = 1 ORDER BY MCLIENTID DESC LIMIT 1");
        assumeTrue(!m.isEmpty(), "no member with a vouchers-payable account");
        client = ((Number) m.get(0).get("c")).intValue();
        List<Map<String, Object>> fee = jdbc.queryForList("SELECT ID FROM loansldeduc WHERE BR_CODE = 1 AND SLC_CODE = 12 AND SLT_CODE = 48 AND SLBEHAVIOR = 1 ORDER BY DEDUCORDER, ID LIMIT 1");
        assumeTrue(!fee.isEmpty(), "no fee row in the template");
        feeRow = ((Number) fee.get(0).get("ID")).longValue();
    }

    private Created apply() {
        return applications.create(1, client, new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, null, null, null, null, List.of(), List.of(new DeductionAmount(feeRow, new BigDecimal("50.00")))), encoder);
    }

    @Test
    void fullChainWithThreePeople() {
        Created c = apply();
        workflow.verify(c.applicationId(), verifier);
        Approved a = workflow.approve(c.applicationId(), approver);
        assertEquals(String.format("%08d", a.voucherControlNo()), a.voucherDocumentNo());
        assertEquals(3, jdbc.queryForObject("SELECT STATUSID FROM loanapp WHERE BR_CODE = 1 AND ID = ?", Integer.class, c.applicationId()));
        assertEquals(a.voucherControlNo(), jdbc.queryForObject("SELECT TR_CTLNO FROM loanapp WHERE BR_CODE = 1 AND ID = ?", Long.class, c.applicationId()));

        List<Map<String, Object>> lines = jdbc.queryForList("SELECT DTACCT_CODE, DTAMT, DTUPD_TAG, DTENCDBY FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO",
            a.year(), a.voucherControlNo());
        assertEquals(3, lines.size());
        assertEquals(0, new BigDecimal("5000.00").compareTo((BigDecimal) lines.get(0).get("DTAMT")));
        assertEquals(0, new BigDecimal("-50.00").compareTo((BigDecimal) lines.get(1).get("DTAMT")));
        assertEquals(0, new BigDecimal("-4950.00").compareTo((BigDecimal) lines.get(2).get("DTAMT")));
        assertEquals(2, ((Number) lines.get(0).get("DTUPD_TAG")).intValue());
        assertEquals(ids[0], ((Number) lines.get(0).get("DTENCDBY")).intValue());

        // the voucher: the application's approver may not approve it; the verifier may, and then the approver may post it
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> releases.approve(a.year(), a.voucherControlNo(), approver));
        assertTrue(e.getReason().contains("approved the loan application"));
        releases.approve(a.year(), a.voucherControlNo(), verifier);
        releases.post(a.year(), a.voucherControlNo(), approver);
        assertEquals(1, jdbc.queryForObject("SELECT MIN(DTUPD_TAG) FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ?", Integer.class, a.year(), a.voucherControlNo()));
    }

    @Test
    void theEncoderCannotVerifyOrApproveOwnApplication() {
        Created c = apply();
        assertThrows(ResponseStatusException.class, () -> workflow.verify(c.applicationId(), encoder));
        assertThrows(ResponseStatusException.class, () -> workflow.approve(c.applicationId(), encoder));
        workflow.verify(c.applicationId(), verifier);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> workflow.approve(c.applicationId(), verifier));
        assertTrue(e.getReason().contains("verified"));
    }

    @Test
    void aPersonLetAloneByAnAdministratorCanDoEveryStep() {
        jdbc.update("INSERT INTO ecis_single_hand (username, reason, granted_by, granted_at) VALUES (?, 'test', 'admin', NOW())", encoder.getName());
        Created c = apply();
        workflow.verify(c.applicationId(), encoder);
        Approved a = workflow.approve(c.applicationId(), encoder);
        releases.approve(a.year(), a.voucherControlNo(), encoder);
        releases.post(a.year(), a.voucherControlNo(), encoder);
        assertEquals(1, jdbc.queryForObject("SELECT MIN(DTUPD_TAG) FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ?", Integer.class, a.year(), a.voucherControlNo()));
    }

    @Test
    void cancellingAnApplicationBlocksItsUnpostedVoucher() {
        Created c = apply();
        assertThrows(ResponseStatusException.class, () -> workflow.cancel(c.applicationId(), new Reason(" "), encoder));
        Approved a = workflow.approve(c.applicationId(), approver);
        workflow.cancel(c.applicationId(), new Reason("member changed their mind"), encoder);
        assertEquals(5, jdbc.queryForObject("SELECT STATUSID FROM loanapp WHERE BR_CODE = 1 AND ID = ?", Integer.class, c.applicationId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.application.cancel' AND entity_id = ?", Integer.class, "1/" + c.applicationId()));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> releases.approve(a.year(), a.voucherControlNo(), verifier));
        assertTrue(e.getReason().contains("cancelled"));
        assertThrows(ResponseStatusException.class, () -> workflow.cancel(c.applicationId(), new Reason("again"), encoder));
    }

    @Test
    void anApplicationWhoseVoucherIsPostedCannotBeCancelled() {
        Created c = apply();
        Approved a = workflow.approve(c.applicationId(), approver);
        releases.approve(a.year(), a.voucherControlNo(), verifier);
        releases.post(a.year(), a.voucherControlNo(), approver);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> workflow.cancel(c.applicationId(), new Reason("too late"), encoder));
        assertTrue(e.getReason().contains("already posted"));
    }
}
