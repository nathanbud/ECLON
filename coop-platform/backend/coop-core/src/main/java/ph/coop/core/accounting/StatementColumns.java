package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ph.coop.core.accounting.FinancialStatementController.Group;
import ph.coop.core.accounting.FinancialStatementController.Line;
import ph.coop.core.accounting.FinancialStatementController.Section;

/**
 * Lays the statements of several units side by side: every account any unit has, in chart order under its group, with one amount per unit (zero where a unit
 * has none) and the consolidated total, which is the sum of the columns. Pure logic.
 */
final class StatementColumns {

    private StatementColumns() {}

    /** A column: a unit (with everything under it), or the entries posted to the consolidation itself ({@code ownEntries}). */
    record Column(int id, String name, String kind, String kindName, boolean ownEntries) {}

    record CLine(String accountCode, String description, List<BigDecimal> amounts, BigDecimal total) {}

    record CGroup(String name, List<CLine> lines, List<BigDecimal> totals, BigDecimal total) {}

    record CSection(String name, List<CGroup> groups, List<BigDecimal> totals, BigDecimal total) {}

    /** {@code bottom} is the last line per column: total liabilities and equity, or net surplus. */
    record Consolidating(int branchCode, String statement, LocalDate asOf, LocalDate from, LocalDate to, Column unit, List<Column> columns,
                         List<CSection> sections, String bottomName, List<BigDecimal> bottom, BigDecimal bottomTotal) {}

    static Consolidating merge(int branchCode, String statement, LocalDate asOf, LocalDate from, LocalDate to, Column unit, List<Column> columns,
                               List<List<Section>> perColumn) {
        int n = columns.size();
        List<String> sectionNames = new ArrayList<>();
        for (List<Section> ss : perColumn) for (Section s : ss) if (!sectionNames.contains(s.name())) sectionNames.add(s.name());
        List<CSection> sections = new ArrayList<>();
        for (String sn : sectionNames) {
            // group name -> line key -> amounts per column; a group is placed by its first account in chart order
            Map<String, Map<String, BigDecimal[]>> groups = new LinkedHashMap<>();
            Map<String, String[]> lineInfo = new LinkedHashMap<>();
            Map<String, String> firstCode = new LinkedHashMap<>();
            for (int c = 0; c < n; c++) {
                for (Section s : perColumn.get(c)) {
                    if (!s.name().equals(sn)) continue;
                    for (Group g : s.groups()) {
                        Map<String, BigDecimal[]> lines = groups.computeIfAbsent(g.name(), (k) -> new LinkedHashMap<>());
                        for (Line l : g.lines()) {
                            String key = l.accountCode() + "|" + l.description();
                            lineInfo.put(key, new String[] {l.accountCode(), l.description()});
                            lines.computeIfAbsent(key, (k) -> zeros(n))[c] = l.amount();
                            String code = l.accountCode().isEmpty() ? "~" : l.accountCode();
                            firstCode.merge(g.name(), code, (a, b) -> a.compareTo(b) <= 0 ? a : b);
                        }
                    }
                }
            }
            List<String> order = new ArrayList<>(groups.keySet());
            order.sort((a, b) -> firstCode.getOrDefault(a, "~").compareTo(firstCode.getOrDefault(b, "~")));
            List<CGroup> out = new ArrayList<>();
            BigDecimal[] sectionTotals = zeros(n);
            for (String gn : order) {
                List<String> keys = new ArrayList<>(groups.get(gn).keySet());
                Collections.sort(keys, (a, b) -> {
                    String ca = lineInfo.get(a)[0], cb = lineInfo.get(b)[0];
                    if (ca.isEmpty() != cb.isEmpty()) return ca.isEmpty() ? 1 : -1;
                    return ca.compareTo(cb);
                });
                List<CLine> lines = new ArrayList<>();
                BigDecimal[] groupTotals = zeros(n);
                for (String k : keys) {
                    BigDecimal[] a = groups.get(gn).get(k);
                    for (int c = 0; c < n; c++) groupTotals[c] = groupTotals[c].add(a[c]);
                    lines.add(new CLine(lineInfo.get(k)[0], lineInfo.get(k)[1], List.of(a), sum(a)));
                }
                for (int c = 0; c < n; c++) sectionTotals[c] = sectionTotals[c].add(groupTotals[c]);
                out.add(new CGroup(gn, lines, List.of(groupTotals), sum(groupTotals)));
            }
            sections.add(new CSection(sn, out, List.of(sectionTotals), sum(sectionTotals)));
        }
        boolean fc = "financial-condition".equals(statement);
        BigDecimal[] bottom = zeros(n);
        for (int c = 0; c < n; c++) {
            for (CSection s : sections) {
                BigDecimal v = s.totals().get(c);
                if (fc) {
                    if (!s.name().equals("ASSETS")) bottom[c] = bottom[c].add(v);
                } else {
                    bottom[c] = s.name().equals("EXPENSES") ? bottom[c].subtract(v) : bottom[c].add(v);
                }
            }
        }
        return new Consolidating(branchCode, statement, asOf, from, to, unit, columns, sections, fc ? "Total liabilities and equity" : "Net surplus",
            List.of(bottom), sum(bottom));
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal sum(BigDecimal[] a) {
        BigDecimal t = BigDecimal.ZERO;
        for (BigDecimal v : a) t = t.add(v);
        return t;
    }
}
