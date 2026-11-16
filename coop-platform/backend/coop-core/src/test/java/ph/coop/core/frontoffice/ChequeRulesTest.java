package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.frontoffice.ChequeRules.Input;

class ChequeRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 24);

    private static Input cheque(String number, Integer bank, LocalDate date, Integer kind, String amount) {
        return new Input(number, bank, date, kind, amount == null ? null : new BigDecimal(amount));
    }

    @Test
    void numbersArePaddedToFifteenDigits() {
        assertEquals("000000001074958", ChequeRules.number("1074958"));
        assertEquals("000000001074958", ChequeRules.number(" 000000001074958 "));
        assertNull(ChequeRules.number(""));
        assertNull(ChequeRules.number("12AB"));
        assertNull(ChequeRules.number("1234567890123456"));
    }

    @Test
    void ledgerDaysAreTheKindDaysPlusTwoAndZeroForImmediateKinds() {
        assertEquals(5, ChequeRules.ledgerDays(3));
        assertEquals(9, ChequeRules.ledgerDays(7));
        assertEquals(17, ChequeRules.ledgerDays(15));
        assertEquals(0, ChequeRules.ledgerDays(0));
    }

    @Test
    void aGoodChequeHasNoProblems() {
        assertTrue(ChequeRules.check(cheque("1074958", 33, TODAY.plusDays(7), 1, "51590.69"), 1, TODAY, 3, true).isEmpty());
        assertTrue(ChequeRules.check(cheque("1074958", 33, TODAY.minusDays(179), 1, "10.00"), 1, TODAY, 3, true).isEmpty(), "the oldest in the real data");
    }

    @Test
    void eachFaultIsReportedWithTheChequePosition() {
        List<String> p = ChequeRules.check(cheque("ABC", null, null, null, "0"), 2, TODAY, null, false);
        assertEquals(5, p.size(), p.toString());
        assertTrue(p.stream().allMatch((x) -> x.startsWith("Cheque 2:")));
        assertTrue(ChequeRules.check(cheque("1", 1, TODAY.minusDays(181), 1, "5.00"), 1, TODAY, 3, true).get(0).contains("stale"));
        assertTrue(ChequeRules.check(cheque("1", 1, TODAY.plusDays(31), 1, "5.00"), 1, TODAY, 3, true).get(0).contains("ahead"));
        assertTrue(ChequeRules.check(cheque("1", 1, TODAY, 1, "5.005"), 1, TODAY, 3, true).get(0).contains("2 decimal places"));
    }

    @Test
    void theSameChequeTwiceOnOneReceiptIsCaught() {
        Input a = cheque("123", 1, TODAY, 1, "5.00"), b = cheque("0000123", 1, TODAY, 1, "6.00"), other = cheque("123", 2, TODAY, 1, "7.00");
        assertEquals(1, ChequeRules.repeats(List.of(a, b)).size());
        assertTrue(ChequeRules.repeats(List.of(a, other)).isEmpty(), "another bank is another cheque");
    }
}
