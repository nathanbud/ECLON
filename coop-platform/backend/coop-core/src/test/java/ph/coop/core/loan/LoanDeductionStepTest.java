package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import ph.coop.core.loan.LoanApplicationController.Create;
import ph.coop.core.loan.LoanApplicationController.Created;
import ph.coop.core.loan.LoanApplicationController.DeductionAmount;
import ph.coop.core.loan.LoanApplicationController.DeductionsIn;

/**
 * Profiling and Deduction as separate permissions and steps: an application saved by someone who may only profile waits for its deductions, which
 * someone who may deduct fills in; verification waits until then; someone with both does both in one save. Every step is in the audit log. Rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(LoanDeductionStepTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoanDeductionStepTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(LocalDateTime.of(LocalDate.of(2026, 4, 25), java.time.LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired LoanApplicationController applications;
    @Autowired LoanApplicationWorkflowController workflow;
    @Autowired LoanSteps steps;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private Authentication profiler, deducter, verifier;
    private int client;
    private long feeLine;

    @BeforeEach
    void setUp() {
        assumeTrue(steps.available(), "V025 (the Deduction switch and step table) has not been applied");
        assumeTrue(db.canInsertInto("loanapp", "loan", "amortsched", "loandeduc", "comaker", "slmastersum"), "V016 has not been applied");
        List<String> staff = jdbc.queryForList("SELECT AccessUserName FROM access WHERE COALESCE(AccessIsDisabled, 0) = 0 AND AccessBR_CODE = 1 AND AccessLevel <> 'SUPERADMIN' ORDER BY AccessID LIMIT 3", String.class);
        assumeTrue(staff.size() == 3, "need three staff in branch 1");
        // three people, each given exactly one step (rolled back)
        set(staff.get(0), 1, 0, 0);
        set(staff.get(1), 0, 1, 0);
        set(staff.get(2), 0, 0, 1);
        profiler = new TestingAuthenticationToken(staff.get(0), "x");
        deducter = new TestingAuthenticationToken(staff.get(1), "x");
        verifier = new TestingAuthenticationToken(staff.get(2), "x");
        List<Map<String, Object>> m = jdbc.queryForList("SELECT MCLIENTID c FROM slmastersum WHERE MBR_CODE = 1 AND MSLC_CODE = 29 AND MSLT_CODE = 1 ORDER BY MCLIENTID DESC LIMIT 1");
        assumeTrue(!m.isEmpty(), "no member with a vouchers-payable account");
        client = ((Number) m.get(0).get("c")).intValue();
        List<Long> fee = jdbc.queryForList("SELECT ID FROM loansldeduc WHERE BR_CODE = 1 AND SLC_CODE = 12 AND SLT_CODE = 48 AND SLBEHAVIOR = 1 ORDER BY DEDUCORDER, ID LIMIT 1", Long.class);
        assumeTrue(!fee.isEmpty(), "loan type 48 has no deduction line");
        feeLine = fee.get(0);
    }

    private void set(String user, int profile, int deduct, int verify) {
        jdbc.update("UPDATE access SET AccessCanAddLoanApplication = ?, AccessCanDoLoanDeduction = ?, AccessCanVerifyLoanApplication = ?, AccessCanApproveLoanApplication = 0 WHERE AccessUserName = ?",
            profile, deduct, verify, user);
    }

    private Create loan(List<DeductionAmount> deductions) {
        return new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, null, null, null, null, List.of(), deductions);
    }

    private int audits(String action, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, "1/" + id);
    }

    @Test
    void profilingAloneWaitsForDeductionsWhichSomeoneElseFillsInBeforeVerification() {
        ResponseStatusException typed = assertThrows(ResponseStatusException.class, () -> applications.create(1, client, loan(List.of(new DeductionAmount(feeLine, new BigDecimal("50.00")))), profiler));
        assertTrue(typed.getReason().contains("Profiling but not Deduction"), typed.getReason());

        Created c = applications.create(1, client, loan(List.of()), profiler);
        assertTrue(c.waitingForDeductions());
        assertTrue(steps.waitingForDeductions(1, c.applicationId()));
        assertEquals(1, audits("loan.application.create", c.applicationId()));
        assertEquals(0, audits("loan.application.deduction", c.applicationId()));
        assertTrue(workflow.permissions(deducter).canDeduct());

        ResponseStatusException early = assertThrows(ResponseStatusException.class, () -> workflow.verify(c.applicationId(), verifier));
        assertTrue(early.getReason().contains("waiting for its deductions"), early.getReason());
        assertThrows(ResponseStatusException.class, () -> applications.deductions(1, client, c.applicationId(), new DeductionsIn(List.of(), List.of()), profiler),
            "the profiler may not do the deductions");

        var d = applications.deductions(1, client, c.applicationId(), new DeductionsIn(List.of(new DeductionAmount(feeLine, new BigDecimal("50.00"))), List.of()), deducter);
        assertEquals(0, new BigDecimal("50.00").compareTo(d.totalDeductions()));
        assertEquals(0, new BigDecimal("4950.00").compareTo(d.netProceeds()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM loandeduc WHERE BR_CODE = 1 AND LOANAPPID = ?", Integer.class, c.applicationId()));
        assertFalse(steps.waitingForDeductions(1, c.applicationId()));
        assertEquals(1, audits("loan.application.deduction", c.applicationId()));
        Map<String, Object> step = jdbc.queryForMap("SELECT profiled_by, deductions_by FROM ecis_loanapp_step WHERE branch_code = 1 AND loanapp_id = ?", c.applicationId());
        assertEquals(profiler.getName(), step.get("profiled_by"));
        assertEquals(deducter.getName(), step.get("deductions_by"));
        assertThrows(ResponseStatusException.class, () -> applications.deductions(1, client, c.applicationId(), new DeductionsIn(List.of(), List.of()), deducter), "only once");

        assertEquals(2, workflow.verify(c.applicationId(), verifier).status(), "verified once the deductions are in");
        assertEquals(1, audits("loan.application.verify", c.applicationId()));
    }

    @Test
    void someoneWithBothStepsDoesBothInOneSave() {
        set(profiler.getName(), 1, 1, 0);
        Created c = applications.create(1, client, loan(List.of(new DeductionAmount(feeLine, new BigDecimal("50.00")))), profiler);
        assertFalse(c.waitingForDeductions());
        assertFalse(steps.waitingForDeductions(1, c.applicationId()));
        assertEquals(1, audits("loan.application.create", c.applicationId()));
        assertEquals(1, audits("loan.application.deduction", c.applicationId()));
        Map<String, Object> step = jdbc.queryForMap("SELECT profiled_by, deductions_by FROM ecis_loanapp_step WHERE branch_code = 1 AND loanapp_id = ?", c.applicationId());
        assertEquals(step.get("profiled_by"), step.get("deductions_by"));
    }

    @Test
    void aRestructuredLoanNeedsBothSteps() {
        Create restructure = new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 3, null, null, null, null, List.of(), List.of());
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> applications.create(1, client, restructure, profiler));
        assertTrue(e.getReason().contains("restructured"), e.getReason());
    }
}
