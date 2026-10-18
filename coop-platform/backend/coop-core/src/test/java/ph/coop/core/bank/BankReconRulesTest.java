package ph.coop.core.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BankReconRulesTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void differenceIsStatementMinusBookLessOutstanding() {
        // book 1,000; a 200 deposit in transit (+) and a 50 cheque not yet cashed (-): outstanding 150, so the bank should show 850
        assertEquals(0, d("0").compareTo(BankReconRules.difference(d("850"), d("1000"), d("150"))));
        assertTrue(BankReconRules.reconciled(BankReconRules.difference(d("850.00"), d("1000.00"), d("150.00"))));
        assertEquals(0, d("25").compareTo(BankReconRules.difference(d("875"), d("1000"), d("150"))), "the bank shows 25 more than explained");
        assertFalse(BankReconRules.reconciled(d("-0.01")));
    }

    @Test
    void checksASaveRequest() {
        LocalDate today = LocalDate.of(2026, 10, 16);
        assertTrue(BankReconRules.checkSave(today, d("1"), today, null).isEmpty());
        assertEquals(1, BankReconRules.checkSave(null, d("1"), today, null).size());
        assertEquals(1, BankReconRules.checkSave(today.plusDays(1), d("1"), today, null).size());
        assertEquals(1, BankReconRules.checkSave(today, null, today, null).size());
        assertEquals(1, BankReconRules.checkSave(today, d("1.005"), today, null).size());
        assertEquals(1, BankReconRules.checkSave(today.minusDays(5), d("1"), today, today.minusDays(5)).size(), "not later than the last finalized statement");
        assertTrue(BankReconRules.checkSave(today.minusDays(4), d("1"), today, today.minusDays(5)).isEmpty());
    }

    @Test
    void finalizingNeedsAnotherPersonAndNoDifference() {
        assertTrue(BankReconRules.checkFinalize("ana", "ben", d("0.00"), "DRAFT").isEmpty());
        assertEquals(1, BankReconRules.checkFinalize("ana", "ana", d("0"), "DRAFT").size());
        assertEquals(1, BankReconRules.checkFinalize("ana", "ben", d("12.50"), "DRAFT").size());
        assertEquals(1, BankReconRules.checkFinalize("ana", "ben", d("0"), "FINAL").size());
        assertEquals(3, BankReconRules.checkFinalize("ana", "ana", d("1"), "FINAL").size());
    }
}
