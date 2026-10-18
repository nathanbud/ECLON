package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import ph.coop.core.frontoffice.SavingsRules.Check;

class SavingsRulesTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static Check withdraw(String amount, int status, String balance, String held, String min, int client, String limit) {
        return SavingsRules.checkWithdrawal(d(amount), status, d(balance), d(held), min == null ? null : d(min), client, d(limit));
    }

    @Test
    void anActiveDepositIsFine() {
        assertTrue(SavingsRules.checkDeposit(d("800"), 15, 1).isEmpty());
        assertTrue(SavingsRules.checkDeposit(d("0.01"), 15, 2).isEmpty(), "a dormant client may still deposit");
    }

    @Test
    void refusesBadAmountsAndInactiveAccountsForDeposits() {
        assertEquals(1, SavingsRules.checkDeposit(null, 15, 1).size());
        assertEquals(1, SavingsRules.checkDeposit(d("0"), 15, 1).size());
        assertEquals(1, SavingsRules.checkDeposit(d("10.005"), 15, 1).size());
        assertTrue(SavingsRules.checkDeposit(d("10"), 16, 1).get(0).contains("dormant"));
        assertTrue(SavingsRules.checkDeposit(d("10"), 17, 1).get(0).contains("closed"));
        assertEquals(1, SavingsRules.checkDeposit(d("10"), 15, 3).size());
    }

    @Test
    void aPlainWithdrawalNeedsNothing() {
        Check c = withdraw("1000", 15, "5000", "0", "500", 1, "50000");
        assertTrue(c.problems().isEmpty());
        assertTrue(c.needs().isEmpty());
    }

    @Test
    void leavingTheBalanceUnderTheMinimumNeedsASupervisor() {
        Check c = withdraw("4800", 15, "5000", "0", "500", 1, "50000");
        assertTrue(c.problems().isEmpty());
        assertEquals(1, c.needs().size());
        assertEquals(SavingsRules.MIN_BALANCE, c.needs().get(0).kind());
        assertTrue(c.needs().get(0).title().contains("200.00") && c.needs().get(0).title().contains("500.00"));
        // closing the account down to nothing is the common case
        assertEquals(SavingsRules.MIN_BALANCE, withdraw("5000", 15, "5000", "0", "500", 1, "50000").needs().get(0).kind());
        // exactly the minimum left is fine, and no minimum means no rule
        assertTrue(withdraw("4500", 15, "5000", "0", "500", 1, "50000").needs().isEmpty());
        assertTrue(withdraw("5000", 15, "5000", "0", "0", 1, "50000").needs().isEmpty());
        assertTrue(withdraw("5000", 15, "5000", "0", null, 1, "50000").needs().isEmpty());
    }

    @Test
    void goingOverTheCashiersLimitNeedsASupervisor() {
        Check c = withdraw("60000", 15, "100000", "0", "500", 1, "50000");
        assertTrue(c.problems().isEmpty());
        assertEquals(1, c.needs().size());
        assertEquals(SavingsRules.DISBURSEMENT_LIMIT, c.needs().get(0).kind());
        assertTrue(withdraw("50000", 15, "100000", "0", "500", 1, "50000").needs().isEmpty(), "exactly the limit is fine");
        assertEquals(1, withdraw("1", 15, "100000", "0", "500", 1, "0").needs().size(), "a cashier with no limit needs approval for everything");
    }

    @Test
    void bothRulesCanApplyAtOnce() {
        Check c = withdraw("60000", 15, "60100", "0", "500", 1, "50000");
        assertEquals(2, c.needs().size());
    }

    @Test
    void refusesWhatIsNotThereOrIsOnHold() {
        Check over = withdraw("6000", 15, "5000", "0", "500", 1, "50000");
        assertEquals(1, over.problems().size());
        assertTrue(over.problems().get(0).contains("5000.00"));
        Check held = withdraw("4000", 15, "5000", "2000", "500", 1, "50000");
        assertEquals(1, held.problems().size());
        assertTrue(held.problems().get(0).contains("on hold") && held.problems().get(0).contains("3000.00"));
        assertTrue(withdraw("3000", 15, "5000", "2000", "0", 1, "50000").problems().isEmpty(), "exactly what is available");
    }

    @Test
    void refusesDormantClosedAndBadClients() {
        assertTrue(withdraw("10", 16, "5000", "0", "500", 1, "50000").problems().get(0).contains("dormant"));
        assertTrue(withdraw("10", 17, "5000", "0", "500", 1, "50000").problems().get(0).contains("closed"));
        assertEquals(1, withdraw("10", 15, "5000", "0", "500", 99, "50000").problems().size());
        assertEquals(1, SavingsRules.checkWithdrawal(null, 15, d("5000"), d("0"), d("500"), 1, d("50000")).problems().size());
    }
}
