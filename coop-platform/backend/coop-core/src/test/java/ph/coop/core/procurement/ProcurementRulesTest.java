package ph.coop.core.procurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.procurement.ProcurementRules.Line;

class ProcurementRulesTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static Line line(String desc, String unit, String qty, String price) {
        return new Line(desc, unit, d(qty), d(price));
    }

    @Test
    void linesNeedADescriptionAUnitAQuantityAndAPrice() {
        assertTrue(ProcurementRules.lineProblems(List.of(line("Bond paper A4", "ream", "10", "250.00"))).isEmpty());
        assertFalse(ProcurementRules.lineProblems(List.of()).isEmpty());
        assertFalse(ProcurementRules.lineProblems(List.of(line(" ", "ream", "10", "250"))).isEmpty());
        assertFalse(ProcurementRules.lineProblems(List.of(line("Paper", "", "10", "250"))).isEmpty());
        assertFalse(ProcurementRules.lineProblems(List.of(line("Paper", "ream", "0", "250"))).isEmpty());
        assertFalse(ProcurementRules.lineProblems(List.of(line("Paper", "ream", "10", "-1"))).isEmpty());
        // a free item is allowed
        assertTrue(ProcurementRules.lineProblems(List.of(line("Sample", "pc", "1", "0"))).isEmpty());
        // every bad line is named
        List<String> p = ProcurementRules.lineProblems(List.of(line("A", "pc", "1", "1"), line("", "pc", "0", "1")));
        assertTrue(p.stream().allMatch((x) -> x.startsWith("Line 2")), p.toString());
    }

    @Test
    void theTotalRoundsEachLineHalfUp() {
        assertEquals(d("2500.00"), ProcurementRules.total(List.of(line("Paper", "ream", "10", "250.00"))));
        // 3 x 0.335 = 1.005 -> 1.01 each line
        assertEquals(d("2.02"), ProcurementRules.total(List.of(line("A", "pc", "3", "0.335"), line("B", "pc", "3", "0.335"))));
        assertEquals(d("37.50"), ProcurementRules.total(List.of(line("Rice", "kg", "0.5", "75"))));
    }

    @Test
    void numbersRunOnWithinAYear() {
        assertEquals("PR-2026-0001", ProcurementRules.number("PR", 2026, 1));
        assertEquals("PO-2026-0123", ProcurementRules.number("PO", 2026, 123));
        assertEquals(7, ProcurementRules.sequenceOf("PR-2026-0007", "PR", 2026));
        assertEquals(0, ProcurementRules.sequenceOf("PR-2025-0007", "PR", 2026));
        assertEquals(0, ProcurementRules.sequenceOf("PO-2026-0007", "PR", 2026));
        assertEquals(0, ProcurementRules.sequenceOf("PR-2026-abc", "PR", 2026));
    }

    @Test
    void nobodyApprovesTheirOwnWork() {
        assertNotNull(ProcurementRules.approverProblem("ana", "ANA", "request"));
        assertNull(ProcurementRules.approverProblem("ana", "ben", "request"));
    }

    @Test
    void anOrderNeedsAnActiveSupplierAndAnApprovedRequest() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        List<Line> lines = List.of(line("Paper", "ream", "1", "1"));
        assertTrue(ProcurementRules.orderProblems(true, null, today, null, lines).isEmpty());
        assertTrue(ProcurementRules.orderProblems(true, "APPROVED", today, today.plusDays(7), lines).isEmpty());
        assertFalse(ProcurementRules.orderProblems(false, null, today, null, lines).isEmpty());
        assertFalse(ProcurementRules.orderProblems(true, "SUBMITTED", today, null, lines).isEmpty());
        assertFalse(ProcurementRules.orderProblems(true, "ORDERED", today, null, lines).isEmpty());
        assertFalse(ProcurementRules.orderProblems(true, null, today, today.minusDays(1), lines).isEmpty());
    }

    @Test
    void aRequestNeedsAPurposeAndASensibleDate() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        List<Line> lines = List.of(line("Paper", "ream", "1", "1"));
        assertTrue(ProcurementRules.requestProblems("Office supplies", today, today.plusDays(3), lines).isEmpty());
        assertFalse(ProcurementRules.requestProblems("", today, null, lines).isEmpty());
        assertFalse(ProcurementRules.requestProblems("Supplies", today, today.minusDays(1), lines).isEmpty());
    }

    @Test
    void receivingIsLimitedToWhatIsStillExpected() {
        List<ProcurementRules.Open> lines = List.of(new ProcurementRules.Open(1, d("10"), d("4")), new ProcurementRules.Open(2, d("5"), d("0")));
        assertTrue(ProcurementRules.receiptProblems("APPROVED", lines, List.of(new ProcurementRules.Take(1, d("6")), new ProcurementRules.Take(2, d("5")))).isEmpty());
        assertTrue(ProcurementRules.receiptProblems("PARTIAL", lines, List.of(new ProcurementRules.Take(1, d("0.5")))).isEmpty());
        assertFalse(ProcurementRules.receiptProblems("APPROVED", lines, List.of(new ProcurementRules.Take(1, d("6.001")))).isEmpty(), "more than expected");
        assertFalse(ProcurementRules.receiptProblems("APPROVED", lines, List.of(new ProcurementRules.Take(3, d("1")))).isEmpty(), "no such line");
        assertFalse(ProcurementRules.receiptProblems("APPROVED", lines, List.of(new ProcurementRules.Take(1, d("1")), new ProcurementRules.Take(1, d("1")))).isEmpty(), "twice");
        assertFalse(ProcurementRules.receiptProblems("APPROVED", lines, List.of(new ProcurementRules.Take(1, d("0")))).isEmpty());
        assertFalse(ProcurementRules.receiptProblems("APPROVED", lines, List.of()).isEmpty());
        for (String status : new String[] {"DRAFT", "RECEIVED", "CANCELLED"}) assertFalse(ProcurementRules.receiptProblems(status, lines, List.of(new ProcurementRules.Take(1, d("1")))).isEmpty(), status);
    }

    @Test
    void theOrderMovesFromApprovedToPartialToReceived() {
        assertEquals("APPROVED", ProcurementRules.statusAfter(List.of(new ProcurementRules.Open(1, d("10"), d("0")), new ProcurementRules.Open(2, d("5"), d("0")))));
        assertEquals("PARTIAL", ProcurementRules.statusAfter(List.of(new ProcurementRules.Open(1, d("10"), d("10")), new ProcurementRules.Open(2, d("5"), d("1")))));
        assertEquals("RECEIVED", ProcurementRules.statusAfter(List.of(new ProcurementRules.Open(1, d("10"), d("10")), new ProcurementRules.Open(2, d("5"), d("5")))));
    }
}
