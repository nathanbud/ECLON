package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Figures are those of the real vouchers 39888 and 36867 (see loan-posting-map.md). */
class ReleasePlanTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void simpleReleaseCreditsTheFeeAndTheNetProceeds() {
        ReleasePlan.Plan p = ReleasePlan.build(bd("500000.00"), ReleasePlan.serviceFee(bd("500000.00"), bd("1")), List.of());
        assertTrue(p.ok());
        assertEquals(bd("5000.00"), p.totalDeductions());
        assertEquals(bd("495000.00"), p.netProceeds());
        assertEquals(3, p.lines().size());
        assertEquals(bd("-495000.00"), p.lines().get(2).amount());
    }

    @Test
    void deductionsReduceTheNetProceeds() {
        List<ReleasePlan.Deduction> d = List.of(
            new ReleasePlan.Deduction("Share capital", 31, 1, 1, 11, null, bd("1000.00")),
            new ReleasePlan.Deduction("Deposit for capital sub", 31, 2, 1, 11, null, bd("320.00")),
            new ReleasePlan.Deduction("Mutual assistance", 29, 5, 1, 11, null, bd("61.90")),
            new ReleasePlan.Deduction("Loan insurance", 0, 0, 0, 0, "2010504", bd("440.00")));
        ReleasePlan.Plan p = ReleasePlan.build(bd("44000.00"), ReleasePlan.serviceFee(bd("44000.00"), bd("1")), d);
        assertTrue(p.ok());
        assertEquals(bd("2261.90"), p.totalDeductions());
        assertEquals(bd("41738.10"), p.netProceeds());
        assertEquals(0, p.lines().stream().map(ReleasePlan.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add).signum());
    }

    @Test
    void deductionsOverTheLoanAreRefused() {
        ReleasePlan.Plan p = ReleasePlan.build(bd("1000.00"), bd("10.00"),
            List.of(new ReleasePlan.Deduction("Share capital", 31, 1, 1, 11, null, bd("2000.00"))));
        assertFalse(p.ok());
    }

    @Test
    void badDeductionsAreRefused() {
        assertFalse(ReleasePlan.build(bd("1000.00"), bd("10.00"), List.of(new ReleasePlan.Deduction("x", 31, 1, 1, 11, null, bd("0")))).ok());
        assertFalse(ReleasePlan.build(bd("1000.00"), bd("10.00"), List.of(new ReleasePlan.Deduction("x", 0, 0, 0, 0, null, bd("5.00")))).ok());
        assertFalse(ReleasePlan.build(bd("1000.00"), bd("10.00"), List.of(new ReleasePlan.Deduction("x", 31, 1, 1, 11, null, bd("5.001")))).ok());
        assertFalse(ReleasePlan.build(bd("0"), null, List.of()).ok());
    }
}
