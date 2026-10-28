package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/** The loan setup screen's rules: only administrators, a reason for each change, a new type copies its ledger accounts and deductions, and every change is audited. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class LoanSetupTest {

    @Autowired LoanSetupController setup;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    private int source;

    @BeforeEach
    void grants() {
        assumeTrue(db.canInsertInto("sltype", "glcontrol", "loansldeduc", "loanpurpose", "collateraltype"), "V020 has not been applied");
        assumeTrue(!jdbc.queryForList("SELECT 1 FROM access WHERE AccessUserName = 'tester'").isEmpty(), "no tester account");
        // a type that has ledger accounts, so the copy can be checked
        source = jdbc.queryForObject("SELECT MIN(SLTypeSLT_CODE) FROM sltype WHERE SLTypeSLC_CODE = 12 AND SLTypeSLT_CODE IN (SELECT GLControlSLT_CODE FROM glcontrol WHERE GLControlSLC_CODE = 12)", Integer.class);
    }

    private Authentication me() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private LoanSetupController.TypeForm form(String name, String reason) {
        return new LoanSetupController.TypeForm(name, null, 12, 4, 4, 4, 1, new BigDecimal("9.50"), new BigDecimal("36.00"), new BigDecimal("1.00"), 2, true, 50, 365, false, reason);
    }

    @Test void aNewTypeCopiesTheLedgerAccountsAndDeductionsAndIsAudited() {
        var made = setup.create(form("ZZ Test Loan", "pilot product"), source, me());
        assertEquals("ZZ Test Loan", made.name());
        assertEquals(0, new BigDecimal("9.50").compareTo(made.interestRate()));
        assertTrue(made.requireCollateral());
        long srcGl = jdbc.queryForObject("SELECT COUNT(*) FROM glcontrol WHERE GLControlSLC_CODE = 12 AND GLControlSLT_CODE = ?", Long.class, source);
        long newGl = jdbc.queryForObject("SELECT COUNT(*) FROM glcontrol WHERE GLControlSLC_CODE = 12 AND GLControlSLT_CODE = ?", Long.class, made.code());
        assertEquals(srcGl, newGl);
        long srcDed = jdbc.queryForObject("SELECT COUNT(*) FROM loansldeduc WHERE SLC_CODE = 12 AND SLT_CODE = ?", Long.class, source);
        assertEquals(srcDed, jdbc.queryForObject("SELECT COUNT(*) FROM loansldeduc WHERE SLC_CODE = 12 AND SLT_CODE = ?", Long.class, made.code()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.type.create' AND entity = 'loan_type' AND reason = 'pilot product' AND username = 'tester'", Integer.class));
    }

    @Test void aTypeShowsWhereItsPrincipalInterestAndPenaltyPostInTheChart() {
        var wired = setup.accounts(source, me());
        long expected = jdbc.queryForObject("SELECT COUNT(*) FROM glcontrol WHERE GLControlSLC_CODE = 12 AND GLControlSLT_CODE = ?", Long.class, source);
        assertEquals(expected, wired.size());
        assertTrue(wired.stream().anyMatch((a) -> a.event() == 11 && a.statusName() != null), "principal is shown per loan status");
        assertTrue(wired.stream().anyMatch((a) -> a.event() == 23 && a.what().startsWith("Interest paid")));
        assertTrue(wired.stream().allMatch((a) -> a.account() != null && !a.account().isBlank()));
    }

    @Test void theDayCountIsSavedAndKeptWhenTheFormDoesNotSayAndRefusedWhenWrong() {
        var made = setup.create(form("ZZ Test Loan", "pilot"), source, me());
        var with = new LoanSetupController.TypeForm("ZZ Test Loan", null, 12, 4, 4, 4, 3, new BigDecimal("9.50"), new BigDecimal("36.00"), new BigDecimal("1.00"), 2, true, 50, 365, false, "annuity", 1);
        assertEquals(1, setup.update(made.code(), with, me()).dayCount());
        assertEquals(3, setup.update(made.code(), with, me()).amortization());
        var real = new LoanSetupController.TypeForm("ZZ Test Loan", null, 12, 4, 4, 4, 3, new BigDecimal("9.50"), new BigDecimal("36.00"), new BigDecimal("1.00"), 2, true, 50, 365, false, "real days", 2);
        assertEquals(2, setup.update(made.code(), real, me()).dayCount());
        // a form that does not carry it leaves it alone
        assertEquals(2, setup.update(made.code(), form("ZZ Test Loan", "no change to days"), me()).dayCount());
        var bad = new LoanSetupController.TypeForm("ZZ Test Loan", null, 12, 4, 4, 4, 3, new BigDecimal("9.50"), null, null, null, false, null, 365, false, "r", 3);
        assertThrows(ResponseStatusException.class, () -> setup.update(made.code(), bad, me()));
    }

    @Test void editingChangesOnlyWhatWasGivenAndRecordsBeforeAndAfter() {
        var made = setup.create(form("ZZ Test Loan", "pilot"), source, me());
        var f = new LoanSetupController.TypeForm("ZZ Test Loan", null, 12, 4, 4, 4, 1, new BigDecimal("11.00"), new BigDecimal("36.00"), new BigDecimal("1.00"), 2, true, 50, 365, false, "rate review");
        var changed = setup.update(made.code(), f, me());
        assertEquals(0, new BigDecimal("11.00").compareTo(changed.interestRate()));
        String prev = jdbc.queryForObject("SELECT prev_value FROM ecis_audit_log WHERE action = 'loan.type.update' ORDER BY id DESC LIMIT 1", String.class);
        String next = jdbc.queryForObject("SELECT new_value FROM ecis_audit_log WHERE action = 'loan.type.update' ORDER BY id DESC LIMIT 1", String.class);
        assertTrue(prev.contains("SLTypeINT_RATE") && prev.contains("9.5"), prev);
        assertTrue(next.contains("11"), next);
        assertFalse(next.contains("SLTypeM_DESC"), "the name did not change");
    }

    @Test void disablingHidesTheTypeFromNewApplications() {
        var made = setup.create(form("ZZ Test Loan", "pilot"), source, me());
        var off = new LoanSetupController.TypeForm("ZZ Test Loan", null, 12, 4, 4, 4, 1, new BigDecimal("9.50"), new BigDecimal("36.00"), new BigDecimal("1.00"), 2, true, 50, 365, true, "withdrawn");
        assertTrue(setup.update(made.code(), off, me()).disabled());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.type.disable'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sltype WHERE SLTypeSLC_CODE = 12 AND SLTypeSLT_CODE = ? AND SLTypeIsDisabled = 1", Integer.class, made.code()));
    }

    @Test void aReasonIsRequiredAndBadValuesAreRefused() {
        assertThrows(ResponseStatusException.class, () -> setup.create(form("ZZ Test Loan", " "), source, me()));
        assertThrows(ResponseStatusException.class, () -> setup.create(form("", "r"), source, me()));
        assertThrows(ResponseStatusException.class, () -> setup.create(form("A name that is far too long for the field", "r"), source, me()));
        var rate = new LoanSetupController.TypeForm("ZZ Test Loan", null, 12, 4, 4, 4, 1, new BigDecimal("150"), null, null, null, false, null, 365, false, "r");
        assertThrows(ResponseStatusException.class, () -> setup.create(rate, source, me()));
        var days = new LoanSetupController.TypeForm("ZZ Test Loan", null, 12, 4, 4, 4, 1, new BigDecimal("5"), null, null, null, false, null, 300, false, "r");
        assertThrows(ResponseStatusException.class, () -> setup.create(days, source, me()));
        var half = new LoanSetupController.TypeForm("ZZ Test Loan", null, 12, null, 4, 4, 1, new BigDecimal("5"), null, null, null, false, null, 365, false, "r");
        assertThrows(ResponseStatusException.class, () -> setup.create(half, source, me()), "a term needs its unit");
    }

    @Test void twoTypesCannotShareAName() {
        setup.create(form("ZZ Test Loan", "r"), source, me());
        assertThrows(ResponseStatusException.class, () -> setup.create(form("zz test loan", "r"), source, me()));
    }

    @Test void purposesAndCollateralTypesCanBeAddedRenamedAndDeletedWhenUnused() {
        var p = setup.addPurpose(new LoanSetupController.Named("ZZ Purpose", 1, "new use"), me());
        assertEquals("ZZ Purpose 2", setup.renamePurpose(p.id(), new LoanSetupController.Named("ZZ Purpose 2", null, "typo"), me()).name());
        setup.deletePurpose(p.id(), "not needed", me());
        assertTrue(setup.purposes().stream().noneMatch((x) -> x.id() == p.id()));
        var c = setup.addCollateralType(new LoanSetupController.Named("ZZ Collateral", null, "new"), me());
        setup.deleteCollateralType(c.id(), "not needed", me());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'loan.purpose.delete'", Integer.class));
        assertThrows(ResponseStatusException.class, () -> setup.addPurpose(new LoanSetupController.Named("Business Capital", 1, "dup"), me()));
    }

    @Test void aPurposeInUseCannotBeDeleted() {
        Integer used = jdbc.queryForObject("SELECT MIN(LoanPURPOSE) FROM loan WHERE LoanPURPOSE IS NOT NULL", Integer.class);
        assumeTrue(used != null);
        assertThrows(ResponseStatusException.class, () -> setup.deletePurpose(used, "try", me()));
    }

    @Test @WithMockUser(username = "tester", roles = "USER") void onlyAdministratorsMayUseIt() {
        assertThrows(AccessDeniedException.class, () -> setup.types(me()));
        assertThrows(AccessDeniedException.class, () -> setup.create(form("ZZ Test Loan", "r"), source, me()));
    }
}
