package ph.coop.core.loan;

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
import ph.coop.core.loan.LoanApplicationController.Create;
import ph.coop.core.loan.LoanApplicationController.Created;
import ph.coop.core.loan.LoanApplicationController.DeductionAmount;

/**
 * Creates a loan application through the real code at a fixed date, checks the five tables, then rolls back. Needs the V016 grants and a
 * person with AccessCanAddLoanApplication; skips when either is missing. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@Import(LoanApplicationCreateTest.FixedClock.class)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class LoanApplicationCreateTest {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;
    @Autowired LoanSteps steps;

    private Authentication clerk;
    private int client;

    @BeforeEach
    void setUp() {
        assumeTrue(db.canInsertInto("loanapp", "loan", "amortsched", "loandeduc", "comaker", "slmastersum"), "V016 (INSERT on the application tables) has not been applied");
        // a clerk who does both Profiling and Deduction, as these tests save the deductions with the application
        List<Map<String, Object>> who = jdbc.queryForList("SELECT AccessUserName u FROM access WHERE COALESCE(AccessIsDisabled, 0) = 0 AND AccessCanAddLoanApplication = 1 AND "
            + steps.deductionFlagSql("") + " = 1 AND AccessBR_CODE = 1 ORDER BY AccessID LIMIT 1");
        assumeTrue(!who.isEmpty(), "nobody may add loan applications");
        clerk = new TestingAuthenticationToken((String) who.get(0).get("u"), "x");
        List<Map<String, Object>> m = jdbc.queryForList("SELECT MCLIENTID c FROM slmastersum WHERE MBR_CODE = 1 AND MSLC_CODE = 29 AND MSLT_CODE = 1 ORDER BY MCLIENTID DESC LIMIT 1");
        assumeTrue(!m.isEmpty(), "no member with a vouchers-payable account");
        client = ((Number) m.get(0).get("c")).intValue();
    }

    @Test
    void printsTheScheduleOfTheFormBeforeAndAfterItIsSavedAndSavesNothingForIt() {
        Create form = new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, null, null, null, null, List.of(), List.of());
        Integer loansBefore = jdbc.queryForObject("SELECT COUNT(*) FROM loan WHERE LoanBR_CODE = 1 AND ClientIDLoan = ?", Integer.class, client);
        var before = applications.amortizationPdf(1, client, form, null, clerk);
        assertEquals(org.springframework.http.MediaType.APPLICATION_PDF, before.getHeaders().getContentType());
        assertEquals("%PDF", new String(before.getBody(), 0, 4));
        assertEquals(loansBefore, jdbc.queryForObject("SELECT COUNT(*) FROM loan WHERE LoanBR_CODE = 1 AND ClientIDLoan = ?", Integer.class, client), "printing saves no loan");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.application.amortization.print' AND entity_id LIKE ?", Integer.class,
            "1-" + client + "-To be assigned"));

        Created c = applications.create(1, client, form, clerk);
        applications.amortizationPdf(1, client, form, c.refNo(), clerk);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.application.amortization.print' AND entity_id = ?", Integer.class,
            String.format("1-%d-1248-%08d", client, c.refNo())), "once saved, the loan number is printed");
        // a number that is not this member's loan of that amount is not printed
        applications.amortizationPdf(1, client, new Create(48, new BigDecimal("5001.00"), 5, 5, 1, 1, null, null, null, null, List.of(), List.of()), c.refNo(), clerk);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.application.amortization.print' AND entity_id LIKE ?", Integer.class,
            "1-" + client + "-To be assigned"));
    }

    @Test
    void onlyAProfilerPrintsTheSchedule() {
        List<String> none = jdbc.queryForList("SELECT AccessUserName FROM access WHERE COALESCE(AccessCanAddLoanApplication, 0) = 0 AND COALESCE(AccessIsDisabled, 0) = 0 AND AccessBR_CODE = 1 ORDER BY AccessID LIMIT 1", String.class);
        assumeTrue(!none.isEmpty(), "everyone may add applications");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> applications.amortizationPdf(1, client,
            new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, null, null, null, null, List.of(), List.of()), null, new TestingAuthenticationToken(none.get(0), null)));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    void refusesATermLongerThanTheLoanTypeAllows() {
        Integer most = jdbc.queryForObject("SELECT SLTypeTERMS FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 12 AND SLTypeSLT_CODE = 48", Integer.class);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> applications.create(1, client,
            new Create(48, new BigDecimal("5000.00"), most + 1, 5, 1, 1, null, null, null, null, List.of(), List.of()), clerk));
        assertTrue(e.getReason().contains("at most " + most + " months"), e.getReason());
        // in years too: the type's months, counted in whole years
        assertThrows(ResponseStatusException.class, () -> applications.create(1, client,
            new Create(48, new BigDecimal("5000.00"), most / 12 + 1, 5, 1, 1, null, null, null, null, List.of(), List.of(), List.of(), 7), clerk));
        assertEquals(most, applications.create(1, client, new Create(48, new BigDecimal("5000.00"), most, 5, 1, 1, null, null, null, null, List.of(), List.of()), clerk).scheduleRows() - 1);
    }

    @Test
    void writesTheApplicationLikeTheOldSystem() {
        Created c = applications.create(1, client, new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, null, null, "SC 7000", null, List.of(), List.of()), clerk);
        assertEquals(6, c.scheduleRows());
        assertEquals(LocalDate.of(2026, 5, 25), c.firstDue());
        assertEquals(LocalDate.of(2026, 9, 25), c.maturity());
        Map<String, Object> app = jdbc.queryForMap("SELECT * FROM loanapp WHERE BR_CODE = 1 AND ID = ?", c.applicationId());
        assertEquals(1, ((Number) app.get("STATUSID")).intValue());
        assertEquals(String.format("%08d", c.applicationId()), app.get("DOC_NO"));
        assertEquals(c.refNo(), ((Number) app.get("REF_NO")).intValue());
        Map<String, Object> loan = jdbc.queryForMap("SELECT * FROM loan WHERE LoanBR_CODE = 1 AND LoanSLC_CODE = 12 AND LoanSLT_CODE = 48 AND LoanREF_NO = ?", c.refNo());
        assertEquals(0, new BigDecimal("5000.00").compareTo((BigDecimal) loan.get("LoanPAMT")));
        assertEquals(11, ((Number) loan.get("LoanSTATUS")).intValue());
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM amortsched WHERE BR_CODE = 1 AND SLC_CODE = 12 AND SLT_CODE = 48 AND REF_NO = ?", Integer.class, c.refNo()));
        assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(PRINDUE), 0) FROM amortsched WHERE REF_NO = ? AND SLT_CODE = 48 AND BR_CODE = 1", BigDecimal.class, c.refNo()).compareTo(new BigDecimal("5000.00")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.application.create' AND entity_id = ?", Integer.class, "1/" + c.applicationId()));
    }

    @Test
    void anAnnuityTypeGivesALevelPaymentSchedule() {
        jdbc.update("UPDATE sltype SET SLTypeAMORTYPE = 3, SLTypeND_FLAG = 1 WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 12 AND SLTypeSLT_CODE = 48");
        Created c = applications.create(1, client, new Create(48, new BigDecimal("12000.00"), 12, 5, 1, 1, null, null, null, null, List.of(), List.of()), clerk);
        assertEquals(13, c.scheduleRows());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT PRINDUE p, INTDUE i, PRINBAL b FROM amortsched WHERE BR_CODE = 1 AND SLC_CODE = 12 AND SLT_CODE = 48 AND REF_NO = ? ORDER BY DUEDATE", c.refNo());
        BigDecimal first = ((BigDecimal) rows.get(1).get("p")).add((BigDecimal) rows.get(1).get("i"));
        BigDecimal second = ((BigDecimal) rows.get(2).get("p")).add((BigDecimal) rows.get(2).get("i"));
        assertTrue(first.subtract(second).abs().compareTo(new BigDecimal("0.01")) <= 0, "the payment is the same every time");
        assertTrue(((BigDecimal) rows.get(2).get("p")).compareTo((BigDecimal) rows.get(1).get("p")) > 0, "the principal part grows");
        assertEquals(0, ((BigDecimal) rows.get(12).get("b")).signum());
        assertEquals(3, ((Number) jdbc.queryForObject("SELECT LoanAMORTYPE FROM loan WHERE LoanBR_CODE = 1 AND LoanSLC_CODE = 12 AND LoanSLT_CODE = 48 AND LoanREF_NO = ?", Integer.class, c.refNo())).intValue());
    }

    @Test
    void annuityTypesAreOfferedAndOtherKindsStillRefused() {
        Map<String, Object> d = new java.util.HashMap<>(Map.of("SLTypeTERMS", 12, "SLTypeINT_RATE", new BigDecimal("10"), "SLTypeTERM_PERD", 4, "SLTypePPMT_MODE", 4, "SLTypeAMORTYPE", 3));
        assertEquals(null, LoanApplicationController.unsupported(d));
        d.put("SLTypePPMT_MODE", 6); // half-yearly is mapped for annuity only
        assertEquals(null, LoanApplicationController.unsupported(d));
        d.put("SLTypeAMORTYPE", 1);
        assertTrue(LoanApplicationController.unsupported(d) != null);
        d.put("SLTypeAMORTYPE", 2); // straight line
        assertTrue(LoanApplicationController.unsupported(d).contains("annuity"));
    }

    @Test
    void countsATermGivenInYearsAsTwelveMonthsEach() {
        Created c = applications.create(1, client, new Create(48, new BigDecimal("5000.00"), 2, 5, 1, 1, null, null, null, null, List.of(), List.of(), List.of(), 7), clerk);
        assertEquals(25, c.scheduleRows()); // the release row and 24 monthly instalments
        assertEquals(LocalDate.of(2028, 4, 25), c.maturity());
        Map<String, Object> loan = jdbc.queryForMap("SELECT LoanTERMS, LoanTERM_PERD FROM loan WHERE LoanBR_CODE = 1 AND LoanSLC_CODE = 12 AND LoanSLT_CODE = 48 AND LoanREF_NO = ?", c.refNo());
        assertEquals(2, ((Number) loan.get("LoanTERMS")).intValue()); // kept as typed, with its unit, as the old system does
        assertEquals(7, ((Number) loan.get("LoanTERM_PERD")).intValue());
    }

    @Test
    void storesTheDeductionsAndNetProceeds() {
        List<Map<String, Object>> fee = jdbc.queryForList("SELECT ID FROM loansldeduc WHERE BR_CODE = 1 AND SLC_CODE = 12 AND SLT_CODE = 48 AND SLBEHAVIOR = 1 ORDER BY DEDUCORDER, ID LIMIT 1");
        assumeTrue(!fee.isEmpty(), "no fee row in the template");
        Created c = applications.create(1, client, new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, null, null, null, null, List.of(),
            List.of(new DeductionAmount(((Number) fee.get(0).get("ID")).longValue(), new BigDecimal("50.00")))), clerk);
        assertEquals(0, new BigDecimal("4950.00").compareTo(c.netProceeds()));
        Map<String, Object> d = jdbc.queryForMap("SELECT * FROM loandeduc WHERE BR_CODE = 1 AND LOANAPPID = ?", c.applicationId());
        assertEquals(1, ((Number) d.get("SEQNO")).intValue());
        assertEquals(c.refNo(), ((Number) d.get("REF_NO")).intValue());
    }

    @Test
    void refusesDeductionsThatEatTheLoan() {
        List<Map<String, Object>> fee = jdbc.queryForList("SELECT ID FROM loansldeduc WHERE BR_CODE = 1 AND SLC_CODE = 12 AND SLT_CODE = 48 AND SLBEHAVIOR = 1 ORDER BY DEDUCORDER, ID LIMIT 1");
        assumeTrue(!fee.isEmpty(), "no fee row in the template");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> applications.create(1, client,
            new Create(48, new BigDecimal("100.00"), 5, 5, 1, 1, null, null, null, null, List.of(), List.of(new DeductionAmount(((Number) fee.get(0).get("ID")).longValue(), new BigDecimal("100.00")))), clerk));
        assertTrue(e.getReason().contains("less than"));
    }

    @Test
    void opensTheVouchersPayableAccountOfAMemberWithoutOne() {
        assumeTrue(db.canInsertInto("osl"), "V018 (INSERT on osl) has not been applied");
        List<Map<String, Object>> none = jdbc.queryForList("SELECT ClientID c FROM client WHERE ClientIDBrCode = 1 AND NOT EXISTS (SELECT 1 FROM slmastersum WHERE MBR_CODE = 1 AND MCLIENTID = ClientID AND MSLC_CODE = 29 AND MSLT_CODE = 1) ORDER BY ClientID DESC LIMIT 1");
        assumeTrue(!none.isEmpty(), "every member has one");
        int who = ((Number) none.get(0).get("c")).intValue();
        int before = jdbc.queryForObject("SELECT MAX(MREF_NO) FROM slmastersum WHERE MBR_CODE = 1 AND MSLC_CODE = 29 AND MSLT_CODE = 1", Integer.class);
        Created c = applications.create(1, who, new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, null, null, null, null, List.of(), List.of()), clerk);
        Map<String, Object> osl = jdbc.queryForMap("SELECT * FROM osl WHERE OSLBR_CODE = 1 AND OSLSLC_CODE = 29 AND OSLSLT_CODE = 1 AND ClientIDOSL = ?", who);
        assertEquals(before + 1, ((Number) osl.get("OSLREF_NO")).intValue());
        assertEquals(15, ((Number) osl.get("OSLSTATUS")).intValue());
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) osl.get("OSLPAMT")));
        assertEquals(before + 1, jdbc.queryForObject("SELECT NPREF_NO FROM loanapp WHERE BR_CODE = 1 AND ID = ?", Integer.class, c.applicationId()));
    }

    @Test
    void aLoanTypeThatRequiresCollateralNeedsItsTypeValueAndDescription() {
        jdbc.update("UPDATE sltype SET SLTypeRequireCollateral = 1 WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 12 AND SLTypeSLT_CODE = 48");
        Integer type = jdbc.queryForObject("SELECT MIN(CollateralTypeID) FROM collateraltype", Integer.class);
        assumeTrue(type != null, "no collateral types");
        ResponseStatusException none = assertThrows(ResponseStatusException.class,
            () -> applications.create(1, client, new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, null, null, null, null, List.of(), List.of()), clerk));
        assertTrue(none.getReason().contains("requires collateral"), none.getReason());
        assertThrows(ResponseStatusException.class,
            () -> applications.create(1, client, new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, type, new BigDecimal("80000.00"), " ", null, List.of(), List.of()), clerk),
            "a description is needed too");
        Created c = applications.create(1, client, new Create(48, new BigDecimal("5000.00"), 5, 5, 1, 1, type, new BigDecimal("80000.00"), "TCT 1234, house and lot", null, List.of(), List.of()), clerk);
        assertTrue(c.applicationId() > 0);
    }
}
