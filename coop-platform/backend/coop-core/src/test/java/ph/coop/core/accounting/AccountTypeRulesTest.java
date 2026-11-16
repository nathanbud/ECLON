package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.AccountTypeController.Form;

/** What may be set on an account type of each class, and the checks on it. No database. */
class AccountTypeRulesTest {

    private static Form form(String name, String rate, String min) {
        return new Form(name, null, 5, rate == null ? null : new BigDecimal(rate), min == null ? null : new BigDecimal(min), false, "why");
    }

    @Test void eachClassOffersOnlyTheSettingsTheApplicationObeys() {
        assertEquals(List.of("interestRate", "minBalance", "maxBalance", "dormantAfterMonths"), AccountTypeRules.fields(22));
        assertEquals(List.of("minBalance"), AccountTypeRules.fields(24));
        assertEquals(List.of("minBalance"), AccountTypeRules.fields(31));
        assertTrue(AccountTypeRules.fields(13).isEmpty());
        assertTrue(AccountTypeRules.fields(26).isEmpty());
    }

    @Test void loansAndCashAreKeptElsewhere() {
        assertEquals("LOAN_SETUP", AccountTypeRules.managed(12));
        assertEquals("CASH", AccountTypeRules.managed(11));
        assertEquals("EDIT", AccountTypeRules.managed(22));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(12, form("Salary Loan", null, null)));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(11, form("Cash-on-Hand", null, null)));
    }

    @Test void aSavingsTypeTakesItsRateAndMinimumBalance() {
        Map<String, Object> c = AccountTypeRules.columns(22, form(" SD - Youth ", "1.5", "500"));
        assertEquals("SD - Youth", c.get("SLTypeM_DESC"));
        assertEquals("SD - Youth", c.get("SLTypeM_DESC2")); // the full name follows the name when left empty
        assertEquals(new BigDecimal("1.5"), c.get("SLTypeINT_RATE"));
        assertEquals(new BigDecimal("500"), c.get("SLTypeMIN_BAL"));
        assertEquals(5, c.get("SLTypePriority"));
        assertEquals(0, c.get("SLTypeIsDisabled"));
    }

    @Test void aSettingTheClassDoesNotHaveIsLeftAlone() {
        Map<String, Object> td = AccountTypeRules.columns(24, form("TD - Special", "9", "5000"));
        assertFalse(td.containsKey("SLTypeINT_RATE"));
        assertTrue(td.containsKey("SLTypeMIN_BAL"));
        Map<String, Object> ar = AccountTypeRules.columns(13, form("Rent Receivable", "9", "5000"));
        assertFalse(ar.containsKey("SLTypeINT_RATE"));
        assertFalse(ar.containsKey("SLTypeMIN_BAL"));
    }

    @Test void namesRatesAndAmountsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, form(" ", "1", "0")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, form("x".repeat(21), "1", "0")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, new Form("Ok", "y".repeat(31), null, null, null, null, "why")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, form("Ok", "-1", "0")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, form("Ok", "100.01", "0")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, form("Ok", "1.255", "0")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, form("Ok", "1", "-5")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, form("Ok", "1", "10.005")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, new Form("Ok", null, 1000, null, null, null, "why")));
        // an empty rate or minimum is stored as empty, as the old rows have it
        assertNull(AccountTypeRules.columns(22, form("Ok", null, null)).get("SLTypeINT_RATE"));
    }

    @Test void aSavingsTypeTakesAMaximumBalanceAndADormancyPeriod() {
        Map<String, Object> c = AccountTypeRules.columns(22, new Form("SD - Youth", null, 5, new BigDecimal("1"), new BigDecimal("500"), new BigDecimal("50000"), 24, false, "why"));
        assertEquals(new BigDecimal("50000"), c.get("SLTypeMAXBAL"));
        assertEquals(24, c.get("SLTypeDORM_TERM"));
        assertEquals(4, c.get("SLTypeDORM_PERD")); // counted in months
        // empty means no limit and never dormant, written the way the old system writes them
        Map<String, Object> none = AccountTypeRules.columns(22, form("SD - Youth", "1", "500"));
        assertEquals(new BigDecimal("999999999.00"), none.get("SLTypeMAXBAL"));
        assertNull(none.get("SLTypeDORM_TERM"));
        assertNull(none.get("SLTypeDORM_PERD"));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, new Form("Ok", null, 5, null, new BigDecimal("500"), new BigDecimal("100"), null, false, "why")), "less than the minimum");
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, new Form("Ok", null, 5, null, null, BigDecimal.ZERO, null, false, "why")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, new Form("Ok", null, 5, null, null, null, 0, false, "why")));
        assertThrows(IllegalArgumentException.class, () -> AccountTypeRules.columns(22, new Form("Ok", null, 5, null, null, null, 601, false, "why")));
        // other classes do not have them
        assertFalse(AccountTypeRules.columns(31, new Form("Ok", null, 5, null, null, new BigDecimal("5"), 12, false, "why")).containsKey("SLTypeMAXBAL"));
    }

    @Test void lookupPatternsSayWhichClassACodeBelongsTo() {
        assertTrue(AccountTypeRules.appliesTo("22xx,24xx,31xx,41xx,51xx,14xx,14xx,29xx,25xx,", 22));
        assertTrue(AccountTypeRules.appliesTo("11xx,1101,1102,1103", 11));
        assertFalse(AccountTypeRules.appliesTo("12xx,13xx,26xx,", 22));
        assertFalse(AccountTypeRules.appliesTo(null, 22));
        assertEquals(24, AccountTypeRules.dormancyMonths(24, 4));
        assertEquals(24, AccountTypeRules.dormancyMonths(2, 7));
        assertNull(AccountTypeRules.dormancyMonths(30, 1), "a period in days is not understood");
        assertNull(AccountTypeRules.dormancyMonths(null, 4));
        assertEquals(1, AccountTypeRules.balanceSide(1));
        assertEquals(2, AccountTypeRules.balanceSide(2));
        assertEquals(1, AccountTypeRules.balanceSide(5));
    }

    @Test void linksAreNamedInWords() {
        assertEquals("Deposit balance", AccountTypeRules.eventName(22, 11));
        assertEquals("Share capital balance", AccountTypeRules.eventName(31, 11));
        assertEquals("Withholding tax", AccountTypeRules.eventName(24, 73));
        assertEquals("Dormant", AccountTypeRules.statusName(16));
        assertNull(AccountTypeRules.statusName(99));
    }

    @Test void onlyATypeNothingUsedMayBeDeleted() {
        assertEquals(List.of(), AccountTypeRules.deleteProblems(22, Map.of("accounts", 0L, "posted entries", 0L)));
        List<String> used = AccountTypeRules.deleteProblems(22, new java.util.LinkedHashMap<>(Map.of("accounts", 1_204L)));
        assertEquals(1, used.size());
        assertTrue(used.get(0).startsWith("It is in use: 1,204 accounts."), used.get(0));
        assertTrue(AccountTypeRules.deleteProblems(12, Map.of()).get(0).contains("Loan Setup"));
        assertTrue(AccountTypeRules.deleteProblems(11, Map.of()).get(0).contains("Cash types"));
    }
}
