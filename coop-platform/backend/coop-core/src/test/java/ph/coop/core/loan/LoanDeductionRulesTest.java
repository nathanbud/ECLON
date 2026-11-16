package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.loan.LoanDeductionRules.Form;

/** The checks on a loan type's deduction lines. No database. */
class LoanDeductionRulesTest {

    private static Form form(String kind, Integer cls, Integer type, Integer event, String account) {
        return new Form("Mortuary aid", 5, kind, cls, type, event, account, false, "why");
    }

    @Test void aStoredLineIsRecognisedByWhereItGoes() {
        assertEquals("NET_PROCEEDS", LoanDeductionRules.kind(6, 29, 11, null));
        assertEquals("MEMBER_ACCOUNT", LoanDeductionRules.kind(3, 31, 11, null));
        assertEquals("LOAN_CHARGE", LoanDeductionRules.kind(1, null, 31, null));
        assertEquals("CHART_ACCOUNT", LoanDeductionRules.kind(null, null, null, "2010504"));
        assertEquals("OTHER", LoanDeductionRules.kind(4, 12, 11, null), "paying off another loan is not set here");
        assertEquals("OTHER", LoanDeductionRules.kind(8, null, null, null));
    }

    @Test void aLineToAMemberAccountAChargeOrAChartAccountGetsItsColumns() {
        Map<String, Object> m = LoanDeductionRules.target(form("MEMBER_ACCOUNT", 31, 1, null, null));
        assertEquals(31, m.get("DSLC_CODE"));
        assertEquals(1, m.get("DSLT_CODE"));
        assertEquals(11, m.get("DSLE_CODE"));
        assertEquals(3, m.get("SLBEHAVIOR"));
        assertNull(m.get("DACCT_CODE"));
        Map<String, Object> c = LoanDeductionRules.target(form("LOAN_CHARGE", null, null, 31, null));
        assertNull(c.get("DSLC_CODE"));
        assertEquals(31, c.get("DSLE_CODE"));
        assertEquals(1, c.get("SLBEHAVIOR"));
        Map<String, Object> g = LoanDeductionRules.target(form("CHART_ACCOUNT", null, null, null, " 2010504 "));
        assertEquals("2010504", g.get("DACCT_CODE"));
        assertNull(g.get("SLBEHAVIOR"));
        assertNull(g.get("DSLE_CODE"));
    }

    @Test void whatIsMissingOrNotAllowedIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.target(form("MEMBER_ACCOUNT", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.target(form("MEMBER_ACCOUNT", 12, 3, null, null)), "not into another loan");
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.target(form("LOAN_CHARGE", null, null, 11, null)), "principal is not a charge");
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.target(form("CHART_ACCOUNT", null, null, null, " ")));
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.target(form("NET_PROCEEDS", null, null, null, null)), "the net proceeds line is not added");
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.target(form(null, null, null, null, null)));
    }

    @Test void descriptionsAndOrderAreChecked() {
        assertEquals("Mortuary aid", LoanDeductionRules.description("  Mortuary aid "));
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.description(" "));
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.description("x".repeat(46)));
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.description("Say \"hello\""), "it goes inside the old system's formula");
        assertEquals(98, LoanDeductionRules.order(98));
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.order(99), "99 is the net proceeds line");
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.order(0));
        assertThrows(IllegalArgumentException.class, () -> LoanDeductionRules.order(null));
    }

    @Test void aNewLineAsksTheClerkForTheAmountTheWayTheOldSystemDoes() {
        assertEquals("PROMPT_AMOUNT (\"Enter Amount for Mortuary aid: \", \"0\", false)", LoanDeductionRules.promptFormula("Mortuary aid"));
    }
}
