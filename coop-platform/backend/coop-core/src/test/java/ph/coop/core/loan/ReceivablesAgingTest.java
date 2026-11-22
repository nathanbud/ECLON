package ph.coop.core.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.loan.ReceivablesAging.Account;
import ph.coop.core.loan.ReceivablesAging.Basis;
import ph.coop.core.loan.ReceivablesAging.Line;
import ph.coop.core.loan.ReceivablesAging.Spec;

/** The aging rules without a database: buckets, both bases, the at-risk threshold, nested groups with subtotals, totals only and sorting. */
class ReceivablesAgingTest {

    static final LocalDate CUT = LocalDate.of(2026, 4, 24);

    static Account acct(int client, String name, String amount, long daysLate, LocalDate maturity, String dept) {
        return new Account(client, name, 12, 1, "Regular", client * 10, new BigDecimal(amount), new BigDecimal("10.00"), daysLate, LocalDate.of(2025, 1, 1), maturity, CUT,
            dept == null ? Map.of() : Map.of("dept", dept));
    }

    static final List<Account> BOOK = List.of(
        acct(1, "Cruz, Ana", "1000.00", 0, CUT.plusDays(100), "Finance"),
        acct(2, "Bautista, Ben", "2000.00", 15, CUT.plusDays(10), "Finance"),
        acct(3, "Abad, Carl", "3000.00", 45, CUT.minusDays(5), "Admin"),
        acct(4, "Diaz, Dina", "4000.00", 400, CUT.minusDays(200), null));

    @Test
    void bucketsFollowTheDays() {
        assertEquals(0, ReceivablesAging.bucket(0));
        assertEquals(1, ReceivablesAging.bucket(1));
        assertEquals(1, ReceivablesAging.bucket(30));
        assertEquals(2, ReceivablesAging.bucket(31));
        assertEquals(5, ReceivablesAging.bucket(365));
        assertEquals(6, ReceivablesAging.bucket(366));
    }

    @Test
    void parAgesByDaysLateAndCountsAtRiskFromTheThreshold() {
        List<Line> lines = ReceivablesAging.build(BOOK, new Spec(Basis.PAR, 30, List.of(), "name", false), CUT);
        Line total = lines.get(lines.size() - 1);
        assertEquals("total", total.kind());
        assertEquals(4, total.accounts());
        assertEquals(0, new BigDecimal("10000.00").compareTo(total.outstanding()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(total.buckets().get(0)));
        assertEquals(0, new BigDecimal("2000.00").compareTo(total.buckets().get(1)));
        assertEquals(0, new BigDecimal("3000.00").compareTo(total.buckets().get(2)));
        assertEquals(0, new BigDecimal("4000.00").compareTo(total.buckets().get(6)));
        assertEquals(0, new BigDecimal("7000.00").compareTo(total.atRisk()), "late 45 and 400 days");
        assertEquals(new BigDecimal("70.00"), total.riskPct());
        assertEquals(0, new BigDecimal("40.00").compareTo(total.pastDue()));
        // PAR @ 1 takes in the 15 days late one too
        Line par1 = last(ReceivablesAging.build(BOOK, new Spec(Basis.PAR, 1, List.of(), "name", false), CUT));
        assertEquals(0, new BigDecimal("9000.00").compareTo(par1.atRisk()));
    }

    @Test
    void maturityAgesByDaysPastMaturity() {
        Line total = last(ReceivablesAging.build(BOOK, new Spec(Basis.MATURITY, 1, List.of(), "name", false), CUT));
        assertEquals(0, new BigDecimal("3000.00").compareTo(total.buckets().get(0)), "not matured yet");
        assertEquals(0, new BigDecimal("3000.00").compareTo(total.buckets().get(1)), "5 days past");
        assertEquals(0, new BigDecimal("4000.00").compareTo(total.buckets().get(5)), "200 days past");
        assertEquals(0, new BigDecimal("7000.00").compareTo(total.atRisk()));
    }

    @Test
    void groupsNestWithSubtotalsAndNotSetLast() {
        List<Line> lines = ReceivablesAging.build(BOOK, new Spec(Basis.PAR, 30, List.of("dept", "aging"), "name", false), CUT);
        List<String> heads = lines.stream().filter((l) -> l.kind().equals("group") && l.level() == 0).map(Line::label).toList();
        assertEquals(List.of("Admin", "Finance", ReceivablesAging.NONE), heads);
        Line finance = lines.stream().filter((l) -> l.label().equals("Finance")).findFirst().orElseThrow();
        assertEquals(2, finance.accounts());
        assertEquals(0, new BigDecimal("3000.00").compareTo(finance.outstanding()));
        // inside Finance the aging buckets come in their own order, then the accounts
        int at = lines.indexOf(finance);
        assertEquals("Current", lines.get(at + 1).label());
        assertEquals("account", lines.get(at + 2).kind());
        assertEquals(2, lines.get(at + 2).level());
        assertEquals("1-30 days", lines.get(at + 3).label());
    }

    @Test
    void totalsOnlyLeavesTheAccountsOut() {
        List<Line> lines = ReceivablesAging.build(BOOK, new Spec(Basis.PAR, 30, List.of("dept"), "name", true), CUT);
        assertTrue(lines.stream().noneMatch((l) -> l.kind().equals("account")));
        assertEquals(4, lines.size(), "three groups and the total");
    }

    @Test
    void accountsSortAsAsked() {
        List<String> byName = accounts(ReceivablesAging.build(BOOK, new Spec(Basis.PAR, 30, List.of(), "name", false), CUT));
        assertEquals(List.of("Abad, Carl", "Bautista, Ben", "Cruz, Ana", "Diaz, Dina"), byName);
        List<String> byBalance = accounts(ReceivablesAging.build(BOOK, new Spec(Basis.PAR, 30, List.of(), "balance", false), CUT));
        assertEquals("Diaz, Dina", byBalance.get(0));
        List<String> byMaturity = accounts(ReceivablesAging.build(BOOK, new Spec(Basis.PAR, 30, List.of(), "maturity", false), CUT));
        assertEquals("Diaz, Dina", byMaturity.get(0));
        // an unknown sort falls back to the name
        assertEquals(byName, accounts(ReceivablesAging.build(BOOK, new Spec(Basis.PAR, 30, List.of(), "nonsense", false), CUT)));
    }

    @Test
    void badChoicesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Spec(Basis.PAR, 0, List.of(), "name", false));
        assertThrows(IllegalArgumentException.class, () -> new Spec(Basis.PAR, 30, List.of("dept", "gender", "geo", "purpose"), "name", false));
        assertThrows(IllegalArgumentException.class, () -> new Spec(Basis.PAR, 30, List.of("dept", "dept"), "name", false));
        assertThrows(IllegalArgumentException.class, () -> new Spec(Basis.PAR, 30, List.of("firm"), "name", false));
    }

    private static Line last(List<Line> lines) {
        return lines.get(lines.size() - 1);
    }

    private static List<String> accounts(List<Line> lines) {
        return lines.stream().filter((l) -> l.kind().equals("account")).map(Line::label).toList();
    }
}
