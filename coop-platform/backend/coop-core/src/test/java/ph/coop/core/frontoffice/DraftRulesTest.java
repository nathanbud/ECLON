package ph.coop.core.frontoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DraftRulesTest {

    private static final Set<Integer> TYPES = Set.of(10, 11);
    private static final Set<String> ACCOUNTS = Set.of("10002", "2010101");
    private static final Set<Integer> DENOMS = Set.of(1, 2, 7);

    private static DraftRules.Line debit(String acct, String amt) { return new DraftRules.Line(acct, "", "", amt, "0", null, null, null); }
    private static DraftRules.Line credit(String acct, String amt) { return new DraftRules.Line(acct, "", "", "0", amt, null, null, null); }

    private static DraftRules.Payload draft(List<DraftRules.Line> lines) {
        return new DraftRules.Payload(10, "2026-04-24", "20260424", 2, null, null, "", "per diem", lines, List.of());
    }

    private static DraftRules.Payload check(DraftRules.Payload p) { return DraftRules.validate(p, TYPES, ACCOUNTS, DENOMS); }

    @Test void aBalancedDraftIsAccepted() {
        var p = check(draft(List.of(debit("10002", "800.00"), credit("2010101", "800"))));
        var t = DraftRules.totals(p.lines());
        assertTrue(t.balanced());
        assertEquals(0, t.difference().signum());
    }

    @Test void anUnbalancedDraftIsStillSavedButReportedAsOutOfBalance() {
        var p = check(draft(List.of(debit("10002", "800.00"), credit("2010101", "700.00"))));
        var t = DraftRules.totals(p.lines());
        assertFalse(t.balanced());
        assertEquals("100.00", t.difference().toPlainString());
    }

    @Test void everyEntryNeedsExactlyOneSide() {
        assertThrows(IllegalArgumentException.class, () -> check(draft(List.of(new DraftRules.Line("10002", "", "", "5", "5", null, null, null), credit("2010101", "5")))));
        assertThrows(IllegalArgumentException.class, () -> check(draft(List.of(new DraftRules.Line("10002", "", "", "0", "0", null, null, null), credit("2010101", "5")))));
    }

    @Test void accountsMustExist() {
        var e = assertThrows(IllegalArgumentException.class, () -> check(draft(List.of(debit("99999", "5"), credit("2010101", "5")))));
        assertTrue(e.getMessage().contains("99999"));
    }

    @Test void amountsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> check(draft(List.of(debit("10002", "-5"), credit("2010101", "5")))));
        assertThrows(IllegalArgumentException.class, () -> check(draft(List.of(debit("10002", "1.234"), credit("2010101", "1.23")))));
        assertThrows(IllegalArgumentException.class, () -> check(draft(List.of(debit("10002", "lots"), credit("2010101", "5")))));
        assertEquals("1500.50", check(draft(List.of(debit("10002", "1,500.50"), credit("2010101", "1500.5")))).lines().get(0).debit());
    }

    @Test void typeAndDateMustBeValid() {
        var lines = List.of(debit("10002", "5"), credit("2010101", "5"));
        assertThrows(IllegalArgumentException.class, () -> check(new DraftRules.Payload(99, "2026-04-24", "", null, null, null, "", "", lines, List.of())));
        assertThrows(IllegalArgumentException.class, () -> check(new DraftRules.Payload(10, "24/04/2026", "", null, null, null, "", "", lines, List.of())));
        assertThrows(IllegalArgumentException.class, () -> check(draft(List.of())));
    }

    @Test void denominationsAreCheckedAndZeroCountsDropped() {
        var lines = List.of(debit("10002", "5"), credit("2010101", "5"));
        var ok = check(new DraftRules.Payload(10, "2026-04-24", "", null, null, null, "", "", lines,
            List.of(new DraftRules.Denomination(1, "2"), new DraftRules.Denomination(2, "0"))));
        assertEquals(1, ok.denominations().size());
        assertThrows(IllegalArgumentException.class, () -> check(new DraftRules.Payload(10, "2026-04-24", "", null, null, null, "", "", lines,
            List.of(new DraftRules.Denomination(55, "1")))));
        assertThrows(IllegalArgumentException.class, () -> check(new DraftRules.Payload(10, "2026-04-24", "", null, null, null, "", "", lines,
            List.of(new DraftRules.Denomination(1, "-3")))));
    }
}
