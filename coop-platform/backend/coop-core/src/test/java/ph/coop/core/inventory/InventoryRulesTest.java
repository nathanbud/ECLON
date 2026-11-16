package ph.coop.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InventoryRulesTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void eachKindOfMovementGoesItsWay() {
        for (String in : new String[] {"RECEIVE", "RETURN"}) assertEquals(1, InventoryRules.sign(in));
        for (String out : new String[] {"ISSUE", "SALE", "DAMAGE", "EXPIRY"}) assertEquals(-1, InventoryRules.sign(out));
        assertEquals(0, InventoryRules.sign("TRANSFER"));
        assertEquals(0, InventoryRules.sign("ADJUSTMENT"));
        assertEquals(8, InventoryRules.TYPES.size());
    }

    @Test
    void quantitiesAreAboveZeroWithAtMostThreeDecimals() {
        assertTrue(InventoryRules.quantityProblems(d("1")).isEmpty());
        assertTrue(InventoryRules.quantityProblems(d("0.001")).isEmpty());
        assertFalse(InventoryRules.quantityProblems(d("0")).isEmpty());
        assertFalse(InventoryRules.quantityProblems(d("-1")).isEmpty());
        assertFalse(InventoryRules.quantityProblems(d("0.0001")).isEmpty());
        assertFalse(InventoryRules.quantityProblems(null).isEmpty());
        assertFalse(InventoryRules.quantityProblems(d("1000000000")).isEmpty());
    }

    @Test
    void theAverageCostFollowsEachReceipt() {
        // 10 at 100.00 then 5 at 130.00 -> (1000 + 650) / 15 = 110.0000
        assertEquals(d("110.0000"), InventoryRules.movingAverage(d("10"), d("100"), d("5"), d("130")));
        // nothing on hand: the receipt sets the cost
        assertEquals(d("42.5000"), InventoryRules.movingAverage(BigDecimal.ZERO, d("100"), d("3"), d("42.5")));
        // rounds half up to four decimals: (1 * 10 + 2 * 10.0001) / 3 = 10.0000667
        assertEquals(d("10.0001"), InventoryRules.movingAverage(d("1"), d("10"), d("2"), d("10.0001")));
        assertEquals(d("1234.57"), InventoryRules.value(d("100"), d("12.3457")));
        assertEquals(d("0.01"), InventoryRules.value(d("0.5"), d("0.015")));
    }

    @Test
    void batchesAreRequiredOnlyWhereTheProductIsTrackedByBatch() {
        assertTrue(InventoryRules.batchProblems(false, false, null, null, false).isEmpty());
        assertFalse(InventoryRules.batchProblems(false, false, "B1", null, false).isEmpty(), "not tracked");
        assertFalse(InventoryRules.batchProblems(true, false, " ", null, false).isEmpty(), "tracked, no batch");
        assertTrue(InventoryRules.batchProblems(true, false, "B1", null, true).isEmpty());
        assertFalse(InventoryRules.batchProblems(true, true, "B1", null, true).isEmpty(), "a new expiring batch needs its date");
        assertTrue(InventoryRules.batchProblems(true, true, "B1", null, false).isEmpty(), "an existing batch already has it");
        assertTrue(InventoryRules.batchProblems(true, true, "B1", LocalDate.of(2027, 1, 1), true).isEmpty());
    }

    @Test
    void expiredBatchesAndShortagesAreRecognised() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        assertTrue(InventoryRules.expired(today.minusDays(1), today));
        assertFalse(InventoryRules.expired(today, today), "expires at the end of its day");
        assertFalse(InventoryRules.expired(null, today));
        assertNull(InventoryRules.shortage(d("5"), d("5")));
        assertNotNull(InventoryRules.shortage(d("4.5"), d("5")));
    }

    @Test
    void adjustmentLinesAndCountVariances() {
        assertTrue(InventoryRules.adjustmentLineProblems("ADJUSTMENT", d("3")).isEmpty());
        assertTrue(InventoryRules.adjustmentLineProblems("ADJUSTMENT", d("-3")).isEmpty());
        assertTrue(InventoryRules.adjustmentLineProblems("DAMAGE", d("-1")).isEmpty());
        assertFalse(InventoryRules.adjustmentLineProblems("DAMAGE", d("1")).isEmpty(), "damage cannot add stock");
        assertFalse(InventoryRules.adjustmentLineProblems("EXPIRY", d("2")).isEmpty());
        assertFalse(InventoryRules.adjustmentLineProblems("ADJUSTMENT", d("0")).isEmpty());
        assertEquals(d("-2"), InventoryRules.variance(d("12"), d("10")));
        assertEquals(d("3"), InventoryRules.variance(d("7"), d("10")));
        assertTrue(InventoryRules.low(d("5"), d("5")));
        assertFalse(InventoryRules.low(d("6"), d("5")));
        assertFalse(InventoryRules.low(BigDecimal.ZERO, BigDecimal.ZERO), "no level set");
    }

    @Test
    void transactionNumbersRunOnWithinAYear() {
        assertEquals("INV-2026-000001", InventoryRules.txnNumber(2026, 1));
        assertEquals(42, InventoryRules.txnSequence("INV-2026-000042", 2026));
        assertEquals(0, InventoryRules.txnSequence("INV-2025-000042", 2026));
    }
}
