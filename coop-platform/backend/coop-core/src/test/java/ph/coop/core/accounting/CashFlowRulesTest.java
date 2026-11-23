package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.CashFlowRules.Account;

class CashFlowRulesTest {

    private static BigDecimal m(String v) {
        return new BigDecimal(v);
    }

    @Test void classesAreSuggestedFromTheHeadings() {
        assertEquals("CASH", CashFlowRules.suggest(1, "Cash in vault", List.of("CASH ON HAND", "CASH AND CASH EQUIVALENTS", "CURRENT ASSETS", "ASSETS")));
        assertEquals("OPERATING", CashFlowRules.suggest(1, "Loans receivable - current", List.of("LOANS AND RECEIVABLES", "CURRENT ASSETS", "ASSETS")));
        assertEquals("OPERATING", CashFlowRules.suggest(1, "Accumulated depreciation - building", List.of("PROPERTY AND EQUIPMENT", "NON-CURRENT ASSETS")));
        assertEquals("INVESTING", CashFlowRules.suggest(1, "Building", List.of("PROPERTY AND EQUIPMENT", "NON-CURRENT ASSETS", "ASSETS")));
        assertEquals("OPERATING", CashFlowRules.suggest(2, "Savings Deposit", List.of("SAVINGS DEPOSITS", "DEPOSIT LIABILITIES", "LIABILITIES")));
        assertEquals("FINANCING", CashFlowRules.suggest(2, "Loans payable - LBP", List.of("LOANS PAYABLE", "LIABILITIES")));
        assertEquals("FINANCING", CashFlowRules.suggest(3, "Paid-up share capital", List.of("SHARE CAPITAL", "EQUITY")));
        assertNull(CashFlowRules.suggest(4, "Interest income", List.of("REVENUES")));
    }

    /**
     * A small year that balances: members paid 1,000 of share capital (cash up), 400 was lent out (cash down), interest of 120 earned in cash and 20 of
     * salaries paid. Cash goes from 500 to 1,200; the statement must explain the 700.
     */
    @Test void theActivitiesExplainTheChangeInCash() {
        Map<String, Account> accounts = Map.of(
            "10001", new Account("10001", "Cash in vault", 1, List.of(), "CASH"),
            "12001", new Account("12001", "Loans receivable", 1, List.of(), "OPERATING"),
            "30101", new Account("30101", "Paid-up share capital", 3, List.of(), "FINANCING"),
            "40101", new Account("40101", "Interest income", 4, List.of(), null),
            "50101", new Account("50101", "Salaries", 5, List.of(), null));
        Map<String, BigDecimal> begin = Map.of("10001", m("500"), "30101", m("-500"));
        Map<String, BigDecimal> end = Map.of("10001", m("1200"), "12001", m("400"), "30101", m("-1500"), "40101", m("-120"), "50101", m("20"));
        var st = CashFlowRules.build(begin, end, Map.of(), accounts);
        assertEquals(m("100"), st.netSurplus());
        assertEquals(m("700"), st.netChange());
        assertEquals(m("500"), st.cashBeginning());
        assertEquals(m("1200"), st.cashEnding());
        var operating = st.activities().get(0);
        assertEquals("Net surplus for the period", operating.lines().get(0).description());
        assertEquals(m("-300"), operating.total());           // 100 surplus less 400 lent out
        assertEquals(m("1000"), st.activities().get(2).total()); // share capital paid in
        assertEquals(0, st.unexplained().signum());
    }

    @Test void theYearEndClosingCountsAsSurplusNotAsFinancing() {
        Map<String, Account> accounts = Map.of(
            "10001", new Account("10001", "Cash", 1, List.of(), "CASH"),
            "30198", new Account("30198", "Undivided net surplus", 3, List.of(), "FINANCING"),
            "40101", new Account("40101", "Interest income", 4, List.of(), null));
        // income of 300 earned in cash, then closed on 31 December into undivided net surplus
        Map<String, BigDecimal> begin = Map.of();
        Map<String, BigDecimal> end = Map.of("10001", m("300"), "30198", m("-300"), "40101", m("0"));
        var st = CashFlowRules.build(begin, end, Map.of("30198", m("-300")), accounts);
        assertEquals(m("300"), st.netSurplus());
        assertEquals(0, st.activities().get(2).total().signum());
        assertEquals(0, st.unexplained().signum());
    }

    @Test void whatTheEntriesDoNotExplainIsShownNotHidden() {
        Map<String, Account> accounts = Map.of("10001", new Account("10001", "Cash", 1, List.of(), "CASH"));
        var st = CashFlowRules.build(Map.of(), Map.of("10001", m("50")), Map.of(), accounts); // a one-sided entry
        assertEquals(m("50"), st.unexplained());
    }
}
