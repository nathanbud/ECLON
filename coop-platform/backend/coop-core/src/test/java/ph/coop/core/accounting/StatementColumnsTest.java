package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ph.coop.core.accounting.FinancialStatementController.Group;
import ph.coop.core.accounting.FinancialStatementController.Line;
import ph.coop.core.accounting.FinancialStatementController.Section;
import ph.coop.core.accounting.StatementColumns.Column;
import ph.coop.core.accounting.StatementColumns.Consolidating;

class StatementColumnsTest {

    private static BigDecimal m(String v) {
        return new BigDecimal(v);
    }

    private static Section section(String name, Line... lines) {
        BigDecimal t = BigDecimal.ZERO;
        for (Line l : lines) t = t.add(l.amount());
        return new Section(name, List.of(new Group("INCOME", List.of(lines), t)), t);
    }

    @Test void eachUnitHasItsColumnAndTheTotalIsTheirSumWithMissingAccountsAtZero() {
        List<Section> main = List.of(section("REVENUES", new Line("4010", "Interest", m("100.00")), new Line("4020", "Fees", m("10.00"))),
            new Section("EXPENSES", List.of(), BigDecimal.ZERO));
        List<Section> satellite = List.of(section("REVENUES", new Line("4010", "Interest", m("40.00"))), section("EXPENSES", new Line("5010", "Salaries", m("25.00"))));
        Consolidating c = StatementColumns.merge(1, "operations", null, null, null, new Column(15, "CONSOLIDATED MAIN", "CONSOLIDATION", "Consolidation", false),
            List.of(new Column(2, "MAIN OFFICE", "MAIN_OFFICE", "Main office", false), new Column(3, "BAUKO SATELLITE OFFICE", "SATELLITE_OFFICE", "Satellite office", false)),
            List.of(main, satellite));
        var revenue = c.sections().get(0);
        assertEquals("REVENUES", revenue.name());
        var interest = revenue.groups().get(0).lines().get(0);
        assertEquals(List.of(m("100.00"), m("40.00")), interest.amounts());
        assertEquals(m("140.00"), interest.total());
        var fees = revenue.groups().get(0).lines().get(1);
        assertEquals(List.of(m("10.00"), BigDecimal.ZERO), fees.amounts());
        assertEquals(List.of(m("110.00"), m("40.00")), revenue.totals());
        assertEquals("Net surplus", c.bottomName());
        assertEquals(List.of(m("110.00"), m("15.00")), c.bottom());
        assertEquals(m("125.00"), c.bottomTotal());
    }

    @Test void aStatementOfFinancialConditionEndsWithLiabilitiesAndEquity() {
        List<Section> a = List.of(section("ASSETS", new Line("1010", "Cash", m("50.00"))), section("LIABILITIES", new Line("2010", "Savings", m("30.00"))),
            section("EQUITY", new Line("3010", "Share capital", m("20.00"))));
        Consolidating c = StatementColumns.merge(1, "financial-condition", null, null, null, new Column(1, "ALL", "CONSOLIDATION", "Consolidation", false),
            List.of(new Column(2, "MAIN", "MAIN_OFFICE", "Main office", false)), List.of(a));
        assertEquals("Total liabilities and equity", c.bottomName());
        assertEquals(List.of(m("50.00")), c.bottom());
    }
}
