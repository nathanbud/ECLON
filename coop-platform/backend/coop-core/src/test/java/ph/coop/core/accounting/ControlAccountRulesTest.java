package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.ControlAccountRules.Effect;
import ph.coop.core.accounting.SlReconciliation.Link;

/** Whether a journal may touch control accounts. Pure, no database. */
class ControlAccountRulesTest {

    // Salary Loan posts to current and past due; two savings types share Savings Deposit
    private static final SlReconciliation.Groups GROUPS = SlReconciliation.groups(
        List.of(new Link(12, 21, "10110129"), new Link(12, 21, "10110229"), new Link(22, 1, "2010101"), new Link(22, 2, "2010101")),
        Map.of("12/21", "Salary Loan", "22/1", "SD - Regular", "22/2", "Kiddies"), Map.of(12, "Loans Receivable", 22, "Savings Deposit"));
    private static final Map<String, String> NAMES = Map.of("10110129", "Salary loan - current", "10110229", "Salary loan - past due", "2010101", "Savings Deposit");

    private static String problem(Effect... lines) {
        return ControlAccountRules.problem(ControlAccountRules.unbalanced(List.of(lines), GROUPS.groupOf(), GROUPS.titles(), NAMES));
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test void ordinaryAccountsAreNoneOfItsConcern() {
        assertNull(problem(new Effect("50105", d("100")), new Effect("10101", d("-100"))));
    }

    @Test void movingALoanFromCurrentToPastDueIsAllowed() {
        assertNull(problem(new Effect("10110229", d("300")), new Effect("10110129", d("-300"))));
    }

    @Test void aJournalThatChangesAControlAccountsBalanceIsRefused() {
        // the 2026 kind: 200,000 put straight onto Savings Deposit against cash
        String p = problem(new Effect("2010101", d("-200000")), new Effect("10101", d("200000")));
        assertTrue(p != null && p.contains("2010101 Savings Deposit") && p.contains("Savings Deposit: SD - Regular, Kiddies") && p.contains("debit/credit memo"), p);
    }

    @Test void aMoveThatDoesNotNetToZeroIsRefused() {
        assertTrue(problem(new Effect("10110229", d("300")), new Effect("10110129", d("-250")), new Effect("50105", d("-50"))) != null);
    }

    @Test void eachGroupMustNetToZeroOnItsOwn() {
        // the loan group balances, the savings one does not
        var u = ControlAccountRules.unbalanced(List.of(new Effect("10110229", d("300")), new Effect("10110129", d("-300")), new Effect("2010101", d("-5")), new Effect("10101", d("5"))),
            GROUPS.groupOf(), GROUPS.titles(), NAMES);
        assertEquals(1, u.size());
        assertEquals(d("-5"), u.get(0).net());
        assertEquals(List.of("2010101 Savings Deposit"), u.get(0).accounts());
    }

    @Test void theGroupsFollowTheReconciliation() {
        assertEquals(GROUPS.groupOf().get("10110129"), GROUPS.groupOf().get("10110229"));
        assertTrue(!GROUPS.groupOf().get("10110129").equals(GROUPS.groupOf().get("2010101")));
        assertEquals("Loans Receivable: Salary Loan", GROUPS.titles().get(GROUPS.groupOf().get("10110129")));
    }
}
