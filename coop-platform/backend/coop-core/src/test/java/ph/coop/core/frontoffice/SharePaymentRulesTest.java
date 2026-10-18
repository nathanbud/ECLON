package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SharePaymentRulesTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void anActivePaymentWithinTheCeilingIsFine() {
        assertTrue(SharePaymentRules.check(d("200000"), 15, d("327000"), d("9999999999999.00"), 1).isEmpty());
        assertTrue(SharePaymentRules.check(d("100.50"), 15, BigDecimal.ZERO, null, 1).isEmpty(), "no ceiling means no limit");
    }

    @Test
    void refusesNothingOrTooManyDecimals() {
        assertEquals(1, SharePaymentRules.check(null, 15, BigDecimal.ZERO, null, 1).size());
        assertEquals(1, SharePaymentRules.check(d("0"), 15, BigDecimal.ZERO, null, 1).size());
        assertEquals(1, SharePaymentRules.check(d("-5"), 15, BigDecimal.ZERO, null, 1).size());
        assertEquals(1, SharePaymentRules.check(d("10.005"), 15, BigDecimal.ZERO, null, 1).size());
        assertTrue(SharePaymentRules.check(d("10.500"), 15, BigDecimal.ZERO, null, 1).isEmpty(), "trailing zeros are not extra decimals");
    }

    @Test
    void refusesWhenTheBalanceWouldPassTheMaximum() {
        var p = SharePaymentRules.check(d("200000"), 15, d("327000"), d("500000"), 1);
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("527000.00") && p.get(0).contains("500000.00") && p.get(0).contains("override"));
        assertTrue(SharePaymentRules.check(d("173000"), 15, d("327000"), d("500000"), 1).isEmpty(), "exactly the maximum is allowed");
    }

    @Test
    void refusesInactiveAccountsAndClients() {
        assertEquals(1, SharePaymentRules.check(d("10"), 17, BigDecimal.ZERO, null, 1).size());
        assertEquals(1, SharePaymentRules.check(d("10"), 15, BigDecimal.ZERO, null, 3).size());
        assertEquals(1, SharePaymentRules.check(d("10"), 15, BigDecimal.ZERO, null, 99).size());
        assertEquals(1, SharePaymentRules.check(d("10"), 15, BigDecimal.ZERO, null, 2).size() + 1, "a dormant client may still pay");
    }

    @Test
    void documentNumberIsTypedOrStarAndSevenDigits() {
        assertEquals("00091141", SharePaymentRules.documentNo("00091141", 352779));
        assertEquals("*0352779", SharePaymentRules.documentNo("", 352779));
        assertEquals("*0352779", SharePaymentRules.documentNo(null, 352779));
        assertEquals("A12", SharePaymentRules.documentNo(" A12 ", 1));
        assertThrows(IllegalArgumentException.class, () -> SharePaymentRules.documentNo("123456789", 1));
        assertThrows(IllegalArgumentException.class, () -> SharePaymentRules.documentNo("12 34", 1));
    }
}
