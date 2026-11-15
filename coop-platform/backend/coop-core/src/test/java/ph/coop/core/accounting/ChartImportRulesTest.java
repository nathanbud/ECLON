package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.ChartImportRules.Checked;
import ph.coop.core.accounting.ChartImportRules.Existing;
import ph.coop.core.accounting.ChartImportRules.State;
import ph.coop.core.member.SpreadsheetReader;

/** The rules a list of accounts is checked with before any of it is added to the chart. No database. */
class ChartImportRulesTest {

    private static final Map<String, Existing> CHART = Map.of(
        "1", new Existing("ASSETS", true, false, null),
        "10", new Existing("CURRENT ASSETS", true, false, "1"),
        "100", new Existing("CASH", true, false, "10"),
        "10001", new Existing("Cash on hand", false, false, "100"),
        "109", new Existing("OLD GROUP", true, true, "10"));

    private final List<SpreadsheetReader.Row> rows = new ArrayList<>();

    private void row(String number, String name, String kind, String under) {
        Map<String, String> v = new LinkedHashMap<>();
        if (!number.isEmpty()) v.put("Account number", number);
        if (!name.isEmpty()) v.put("Account name", name);
        if (!kind.isEmpty()) v.put("Kind", kind);
        if (!under.isEmpty()) v.put("Under", under);
        rows.add(new SpreadsheetReader.Row(rows.size() + 2, v));
    }

    private List<Checked> check(Map<String, Existing> chart) {
        return ChartImportRules.check(rows, chart).rows();
    }

    @Test void aNewAccountGoesUnderTheHeadingNamedOrTheLongestOneItsNumberStartsWith() {
        row("10002", "Cash in bank", "", "100");
        row("10003", "Petty cash", "Account", "");
        row("1019", "Other", "", "");
        List<Checked> r = check(CHART);
        assertEquals(State.NEW, r.get(0).state());
        assertEquals("100", r.get(0).parent());
        assertEquals("100", r.get(1).parent());
        assertEquals("10", r.get(2).parent());
        assertTrue(r.get(0).glOnly());
        assertEquals("New account under 100 CASH", r.get(0).note());
    }

    @Test void aHeadingInTheSameFileCanHoldAccountsWhateverTheOrderOfTheRows() {
        row("10101", "Loans receivable", "", "101");
        row("101", "LOANS", "Heading", "10");
        List<Checked> r = check(CHART);
        assertEquals(State.NEW, r.get(0).state());
        assertEquals("101", r.get(0).parent());
        assertTrue(r.get(1).summary());
        // the heading is written before the account under it
        assertEquals(List.of("101", "10101"), ChartImportRules.check(rows, CHART).toAdd().stream().map(Checked::code).toList());
    }

    @Test void aWholeChartLoadsIntoAnEmptyOne() {
        row("1", "ASSETS", "Heading", "");
        row("10", "CURRENT ASSETS", "S", "");
        row("1001", "Cash", "D", "");
        row("2001", "Payables", "", "");
        List<Checked> r = check(Map.of());
        assertEquals(State.NEW, r.get(0).state());
        assertNull(r.get(0).parent());
        assertEquals("New top-level heading", r.get(0).note());
        assertEquals("1", r.get(1).parent());
        assertEquals("10", r.get(2).parent());
        // an account needs a heading above it
        assertEquals(State.PROBLEM, r.get(3).state());
    }

    @Test void anAccountAlreadyInTheChartIsLeftAsItIs() {
        row("10001", "Cash on hand renamed", "", "");
        List<Checked> r = check(CHART);
        assertEquals(State.EXISTS, r.get(0).state());
        assertTrue(r.get(0).note().contains("Cash on hand"));
        assertTrue(ChartImportRules.check(rows, CHART).toAdd().isEmpty());
    }

    @Test void numbersNamesAndHeadingsAreChecked() {
        row("9001", "Bad first digit", "", "");
        row("10A", "Letters", "", "");
        row("", "No number", "", "");
        row("10004", "", "", "");
        row("10005", "x".repeat(51), "", "");
        row("10006", "Under an account", "", "10001");
        row("10901", "Under a switched-off heading", "", "109");
        row("10007", "Unknown heading", "", "1077");
        row("20001", "Wrong prefix", "", "100");
        row("10008", "Odd kind", "Maybe", "");
        row("10009", "cash ON hand", "", "100");
        row("10010", "Twice", "", "");
        row("10010", "Twice again", "", "");
        List<Checked> r = check(CHART);
        for (int i = 0; i < 11; i++) assertEquals(State.PROBLEM, r.get(i).state(), "row " + i + ": " + r.get(i).note());
        assertEquals(State.NEW, r.get(11).state());
        assertEquals(State.PROBLEM, r.get(12).state());
    }

    @Test void anAccountUnderAHeadingWithAProblemIsRefusedToo() {
        row("105", "", "Heading", "10");
        row("10501", "Prepaid rent", "", "105");
        List<Checked> r = check(CHART);
        assertEquals(State.PROBLEM, r.get(1).state());
        assertTrue(r.get(1).note().contains("line 2"));
    }

    @Test void otherColumnNamesAreUnderstoodAndUnknownOnesReported() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("COAID", "10002.0");
        v.put("COADesc", "Cash in bank");
        v.put("GL Only", "No");
        v.put("Remarks", "x");
        rows.add(new SpreadsheetReader.Row(2, v));
        var res = ChartImportRules.check(rows, CHART);
        assertEquals("10002", res.rows().get(0).code());
        assertEquals(State.NEW, res.rows().get(0).state());
        assertFalse(res.rows().get(0).glOnly());
        assertEquals(List.of("Remarks"), res.ignoredColumns());
    }
}
