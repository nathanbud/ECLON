package ph.coop.core.accounting.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerRulesTest {

    private static Voucher.Line line(String account, String amount) { return new Voucher.Line(account, 1, Money.of(amount)); }

    @Test void aVoucherMustBalanceAndHaveTwoLinesNoneOfThemZero() {
        assertNull(LedgerRules.problem(List.of(line("50105", "12.34"), line("2010201", "-12.34"))));
        assertTrue(LedgerRules.problem(List.of(line("50105", "12.34"))).contains("at least two lines"));
        assertTrue(LedgerRules.problem(List.of(line("50105", "12.34"), line("2010201", "0"))).contains("cannot be zero"));
        assertEquals("The voucher does not balance: debits 12.34, credits 12.30.", LedgerRules.problem(List.of(line("50105", "12.34"), line("2010201", "-12.30"))));
    }

    @Test void documentNumbersFollowTheOldSystemsForms() {
        assertEquals("*0000123", LedgerRules.documentNo(Voucher.Style.GENERAL_JOURNAL, 123));
        assertEquals("00000007", LedgerRules.documentNo(Voucher.Style.CLOSING_ENTRY, 7));
    }

    @Test void moneyHasExactlyTwoDecimalsAndSplitsIntoTheGeneralLedgersSides() {
        assertEquals("5.50", Money.of("5.5").toString());
        assertThrows(IllegalArgumentException.class, () -> Money.of("1.005"), "never rounded silently");
        assertEquals(0, new BigDecimal("5.50").compareTo(Money.of("5.50").debit()));
        assertEquals(0, BigDecimal.ZERO.compareTo(Money.of("5.50").credit()));
        assertEquals(0, new BigDecimal("-5.50").compareTo(Money.of("-5.50").credit()), "credits are negative, as in gltrans");
    }

    @Test void theExplanationKeepsAtMostTwoHundredCharacters() {
        assertEquals(200, LedgerRules.explanation("x".repeat(250)).length());
        assertEquals("", LedgerRules.explanation(null));
    }
}
