package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import ph.coop.core.loan.LoanApplicationController.Payoff;
import ph.coop.core.loan.LoanApplicationController.PayoffOption;
import ph.coop.core.loan.LoanApplicationWorkflowController;
import ph.coop.core.loan.LoanApplicationWorkflowController.Approved;
import ph.coop.core.loan.LoanBook;

/**
 * A new loan that pays off an earlier loan of the member (the old system's "Principal / Interest Paid" lines, behavior 4), and a restructured loan that
 * records the loan it replaces. Real code at a fixed date, rolled back. Needs V016 to V018 and three people with "new" rights on type 21; skips otherwise.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(LoanPayoffTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoanPayoffTest {

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
    @Autowired LoanBook book;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private Authentication encoder, verifier, approver;
    private int client;
    private PayoffOption old;

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
        encoder = new TestingAuthenticationToken((String) people.get(0).get("AccessUserName"), "x");
        verifier = new TestingAuthenticationToken((String) people.get(1).get("AccessUserName"), "x");
        approver = new TestingAuthenticationToken((String) people.get(2).get("AccessUserName"), "x");
        try {
            jdbc.update("UPDATE access SET AccessCanAddLoanApplication = 1, AccessCanVerifyLoanApplication = 1, AccessCanApproveLoanApplication = 1 WHERE AccessBR_CODE = 1 AND AccessID IN (?, ?, ?)",
                people.get(0).get("AccessID"), people.get(1).get("AccessID"), people.get(2).get("AccessID"));
        } catch (RuntimeException e) {
            assumeTrue(false, "the access flags cannot be changed by this database user");
        }
        List<Map<String, Object>> loans = jdbc.queryForList("""
                SELECT l.ClientIDLoan c FROM loan l WHERE l.LoanBR_CODE = 1 AND l.LoanSLC_CODE = 12 AND l.LoanSTATUS = 11 AND l.LoanTR_DATE >= '2026-03-01'
                  AND EXISTS (SELECT 1 FROM slmastersum m WHERE m.MBR_CODE = 1 AND m.MCLIENTID = l.ClientIDLoan AND m.MSLC_CODE = 29 AND m.MSLT_CODE = 1)
                ORDER BY l.LoanREF_NO DESC LIMIT 40
                """);
        for (Map<String, Object> r : loans) {
            int c = ((Number) r.get("c")).intValue();
            for (PayoffOption o : applications.template(1, c, 48).payoffs()) {
                if (client == 0 && o.status() == 11 && o.outstanding().signum() > 0 && o.outstanding().compareTo(new BigDecimal("500000")) < 0) { client = c; old = o; }
            }
            if (client != 0) break;
        }
        assumeTrue(client != 0, "no member with an open loan was found");
    }

    private BigDecimal enough(BigDecimal extra) {
        return old.outstanding().add(old.interestDue()).add(extra).setScale(2, RoundingMode.CEILING);
    }

    @Test
    void payingOffAnEarlierLoanBuildsAndPostsItsLines() {
        Created c = applications.create(1, client, new Create(48, enough(new BigDecimal("10000")), 5, 5, 2, 2, null, null, null, null, List.of(), List.of(),
            List.of(new Payoff(old.typeCode(), old.refNo(), old.outstanding(), old.interestDue(), BigDecimal.ZERO))), encoder);
        assertEquals(2, jdbc.queryForObject("SELECT LoanMCLASS FROM loan WHERE LoanBR_CODE = 1 AND LoanSLC_CODE = 12 AND LoanSLT_CODE = 48 AND LoanREF_NO = ?", Integer.class, c.refNo()));
        assertEquals(old.interestDue().signum() > 0 ? 2 : 1, jdbc.queryForObject("SELECT COUNT(*) FROM loandeduc WHERE BR_CODE = 1 AND LOANAPPID = ? AND SLBEHAVIOR = 4", Integer.class, c.applicationId()));
        Approved a = workflow.approve(c.applicationId(), approver);
        Map<String, Object> line = jdbc.queryForMap("SELECT DTACCT_CODE, DTAMT, DTCTS_NUM FROM transdt WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = ? AND DTTR_MODULE = 2 AND DTTR_CODE = 21 AND DTTR_CTLNO = ? AND DTSLE_CODE = 11 AND DTREF_NO = ? AND DTSLT_CODE = ?",
            a.year(), a.voucherControlNo(), old.refNo(), old.typeCode());
        assertEquals(0, old.outstanding().negate().compareTo((BigDecimal) line.get("DTAMT")));
        assertEquals(11, ((Number) line.get("DTCTS_NUM")).intValue());
        assertEquals(jdbc.queryForObject("SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = 1 AND GLControlSLC_CODE = 12 AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = 11 AND GLControlCTS_NUM = 11", String.class, old.typeCode()),
            line.get("DTACCT_CODE"));
        releases.approve(a.year(), a.voucherControlNo(), verifier);
        releases.post(a.year(), a.voucherControlNo(), approver);
        assertEquals(0, BigDecimal.ZERO.compareTo(book.terms(1, client, old.typeCode(), old.refNo()).get().outstanding()));
    }

    @Test
    void aRestructuredLoanRecordsTheLoanItReplaces() {
        BigDecimal amount = enough(new BigDecimal("10000"));
        assertThrows(ResponseStatusException.class, () -> applications.create(1, client, new Create(48, amount, 5, 5, 1, 3, null, null, null, null, List.of(), List.of()), encoder));
        Created c = applications.create(1, client, new Create(48, amount, 5, 5, 1, 3, null, null, null, null, List.of(), List.of(),
            List.of(new Payoff(old.typeCode(), old.refNo(), old.outstanding(), BigDecimal.ZERO, BigDecimal.ZERO))), encoder);
        Map<String, Object> loan = jdbc.queryForMap("SELECT LoanRESTRUCT, LoanXSLT_CODE, LoanXREF_NO FROM loan WHERE LoanBR_CODE = 1 AND LoanSLC_CODE = 12 AND LoanSLT_CODE = 48 AND LoanREF_NO = ?", c.refNo());
        assertEquals(1, ((Number) loan.get("LoanRESTRUCT")).intValue());
        assertEquals(old.typeCode(), ((Number) loan.get("LoanXSLT_CODE")).intValue());
        assertEquals(old.refNo(), ((Number) loan.get("LoanXREF_NO")).intValue());
    }

    @Test
    void cannotPayOffMoreThanTheLoanOwes() {
        BigDecimal amount = enough(new BigDecimal("50000"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> applications.create(1, client,
            new Create(48, amount, 5, 5, 1, 2, null, null, null, null, List.of(), List.of(), List.of(new Payoff(old.typeCode(), old.refNo(), old.outstanding().add(BigDecimal.ONE), BigDecimal.ZERO, BigDecimal.ZERO))), encoder));
        assertTrue(e.getReason().contains("owes only"));
    }
}
