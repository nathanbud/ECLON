package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.frontoffice.HandoverRules.Drawer;

class HandoverRulesTest {

    private static final Map<Integer, BigDecimal> FACES = Map.of(1, new BigDecimal("1000.00"), 2, new BigDecimal("500.00"), 10, new BigDecimal("0.25"));
    private static final Drawer VAULT = new Drawer(50, 99);
    private static final Drawer HEAD = new Drawer(52, 37);
    private static final Drawer TELLER91 = new Drawer(53, 91);

    private static List<String> check(Drawer from, Drawer to, String amount, Map<Integer, Integer> counts, boolean head) {
        return HandoverRules.check(from, to, new BigDecimal(amount), counts, FACES, head);
    }

    @Test
    void headCashierToTellerWithMatchingCountIsFine() {
        assertTrue(check(HEAD, TELLER91, "206000", Map.of(1, 206), true).isEmpty());
    }

    @Test
    void countMustAddUpToTheAmount() {
        List<String> p = check(HEAD, TELLER91, "206000", Map.of(1, 205, 2, 1), false);
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("add up to 205500.00"));
    }

    @Test
    void coinsCount() {
        assertTrue(check(HEAD, TELLER91, "1000.25", Map.of(1, 1, 10, 1), false).isEmpty());
    }

    @Test
    void onlyTheHeadCashierTouchesTheVault() {
        assertFalse(check(VAULT, HEAD, "1000", Map.of(1, 1), false).isEmpty());
        assertTrue(check(VAULT, HEAD, "1000", Map.of(1, 1), true).isEmpty());
        assertTrue(check(HEAD, VAULT, "1000", Map.of(1, 1), true).isEmpty());
    }

    @Test
    void tellersDoNotDealWithTheVault() {
        assertFalse(check(VAULT, TELLER91, "1000", Map.of(1, 1), true).isEmpty());
        assertFalse(check(TELLER91, VAULT, "1000", Map.of(1, 1), true).isEmpty());
    }

    @Test
    void cannotGiveToYourself() {
        assertFalse(check(HEAD, HEAD, "1000", Map.of(1, 1), true).isEmpty());
    }

    @Test
    void amountMustBePositiveWithTwoDecimals() {
        assertFalse(check(HEAD, TELLER91, "0", Map.of(), false).isEmpty());
        assertFalse(check(HEAD, TELLER91, "-5", Map.of(), false).isEmpty());
        assertFalse(check(HEAD, TELLER91, "1000.255", Map.of(1, 1), false).isEmpty());
    }

    @Test
    void unknownDenominationAndNegativeCountAreRejected() {
        assertFalse(check(HEAD, TELLER91, "1000", Map.of(99, 1), false).isEmpty());
        assertFalse(check(HEAD, TELLER91, "1000", Map.of(1, -1), false).isEmpty());
    }

    @Test
    void typeAccountsAndTiming() {
        assertEquals(31, HandoverRules.trCode(VAULT));
        assertEquals(32, HandoverRules.trCode(HEAD));
        assertEquals("10001", HandoverRules.account(50));
        assertEquals("10002", HandoverRules.account(52));
        assertEquals("10003", HandoverRules.account(53));
        assertTrue(HandoverRules.postsAtOnce(VAULT, HEAD));
        assertTrue(HandoverRules.postsAtOnce(HEAD, VAULT));
        assertFalse(HandoverRules.postsAtOnce(HEAD, TELLER91));
    }

    @Test
    void totalOfACount() {
        assertEquals(0, new BigDecimal("1500.25").compareTo(HandoverRules.total(Map.of(1, 1, 2, 1, 10, 1), FACES)));
    }
}
