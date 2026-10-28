package ph.coop.core.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.inventory.StockService.BatchStock;
import ph.coop.core.pos.PosRules.Payment;

class PosRulesTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void linesAndPromotionsRoundToTheCentavo() {
        assertEquals(d("750.00"), PosRules.gross(d("3"), d("250.00")));
        assertEquals(d("1.01"), PosRules.gross(d("3"), d("0.335")), "half up");
        assertEquals(d("75.00"), PosRules.promoOff(d("3"), d("250.00"), d("10")));
        assertEquals(d("0.10"), PosRules.promoOff(d("1"), d("0.99"), d("10")), "0.099 rounds to 0.10");
        assertEquals(d("0.00"), PosRules.promoOff(d("1"), d("100"), d("0")));
    }

    @Test
    void aSaleDiscountIsAPercentOrAFixedAmountNeverMoreThanTheSale() {
        assertEquals(d("100.00"), PosRules.saleDiscount("PERCENT", d("20"), d("500.00")));
        assertEquals(d("50.00"), PosRules.saleDiscount("AMOUNT", d("50"), d("500.00")));
        assertEquals(d("30.00"), PosRules.saleDiscount("AMOUNT", d("50"), d("30.00")), "capped at the subtotal");
        assertEquals(d("0.07"), PosRules.saleDiscount("PERCENT", d("5"), d("1.35")), "0.0675 rounds up");
    }

    @Test
    void quantitiesAreAboveZeroWithAtMostThreeDecimals() {
        assertTrue(PosRules.quantityProblems(d("1")).isEmpty());
        assertTrue(PosRules.quantityProblems(d("0.125")).isEmpty());
        assertFalse(PosRules.quantityProblems(d("0")).isEmpty());
        assertFalse(PosRules.quantityProblems(d("0.0001")).isEmpty());
        assertFalse(PosRules.quantityProblems(null).isEmpty());
    }

    @Test
    void batchesAreSoldEarliestExpiryFirstAndExpiredOnesAreSkipped() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        List<BatchStock> batches = List.of(new BatchStock(1, "OLD", today.minusDays(2), d("5")), new BatchStock(2, "SOON", today.plusDays(5), d("4")),
            new BatchStock(3, "LATER", today.plusDays(90), d("10")), new BatchStock(4, "NODATE", null, d("3")));
        var a = PosRules.allocate(batches, d("6"), today);
        assertEquals(2, a.size());
        assertEquals("SOON", a.get(0).batchNo());
        assertEquals(0, d("4").compareTo(a.get(0).quantity()));
        assertEquals("LATER", a.get(1).batchNo());
        assertEquals(0, d("2").compareTo(a.get(1).quantity()));
        assertEquals(1, PosRules.allocate(batches, d("3"), today).size());
        assertEquals(3, PosRules.allocate(batches, d("17"), today).size(), "4 + 10 + 3 unexpired");
        assertNull(PosRules.allocate(batches, d("17.001"), today), "the expired 5 do not count");
    }

    @Test
    void paymentsMustCoverTheTotalAndOnlyCashGivesChange() {
        assertTrue(PosRules.paymentProblems(d("100"), List.of(new Payment("CASH", d("100")))).isEmpty());
        assertTrue(PosRules.paymentProblems(d("100"), List.of(new Payment("CASH", d("500")))).isEmpty());
        assertTrue(PosRules.paymentProblems(d("100"), List.of(new Payment("CARD", d("60")), new Payment("CASH", d("50")))).isEmpty());
        assertFalse(PosRules.paymentProblems(d("100"), List.of(new Payment("CASH", d("99.99")))).isEmpty(), "short");
        assertFalse(PosRules.paymentProblems(d("100"), List.of(new Payment("CARD", d("120")))).isEmpty(), "a card cannot be more than the total");
        assertFalse(PosRules.paymentProblems(d("100"), List.of(new Payment("CARD", d("90")), new Payment("CASH", d("5")), new Payment("OTHER", d("20")))).isEmpty(), "10 over but only 5 is cash");
        assertFalse(PosRules.paymentProblems(d("100"), List.of(new Payment("GCASH", d("100")))).isEmpty());
        assertFalse(PosRules.paymentProblems(d("100"), List.of()).isEmpty());
        assertTrue(PosRules.paymentProblems(d("0"), List.of()).isEmpty(), "a free sale needs no payment");
        assertEquals(d("400"), PosRules.change(d("100"), List.of(new Payment("CASH", d("500")))));
        assertEquals(0, PosRules.change(d("100"), List.of(new Payment("CASH", d("100")))).signum());
    }

    @Test
    void aRefundIsWhatWasPaidForThoseUnitsWithTheDiscountSharedOut() {
        // 4 units made 400.00 of a 1,000.00 subtotal that had a 100.00 discount: 1 unit is 100.00 x 900/1000
        assertEquals(d("90.00"), PosRules.refund(d("400.00"), d("4"), d("1"), d("1000.00"), d("100.00")));
        assertEquals(d("400.00"), PosRules.refund(d("400.00"), d("4"), d("4"), d("1000.00"), d("0.00")));
        assertEquals(d("33.33"), PosRules.refund(d("100.00"), d("3"), d("1"), d("100.00"), d("0")));
        assertTrue(PosRules.returnProblems(d("4"), d("1"), d("3")).isEmpty());
        assertFalse(PosRules.returnProblems(d("4"), d("1"), d("3.001")).isEmpty());
        assertFalse(PosRules.returnProblems(d("4"), d("0"), d("0")).isEmpty());
    }
}
