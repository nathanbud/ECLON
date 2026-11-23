package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.organization.DepartmentController;
import ph.coop.core.organization.UnitRules;
import ph.coop.core.organization.Units;

/** Statements per unit on coop's own department tree (CONSOLIDATED ALL > CONSOLIDATED MAIN > MAIN OFFICE and the satellite offices), and department kinds. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ADMIN")
class UnitStatementsTest {

    private static final LocalDate FROM = LocalDate.of(2025, 3, 1);
    private static final LocalDate TO = LocalDate.of(2025, 3, 31);

    @Autowired FinancialStatementController statements;
    @Autowired DepartmentController departments;
    @Autowired Units units;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_department_kind"), "the department kinds change (V038) is not applied here");
        assumeTrue(units.find(15).isPresent() && units.find(1).isPresent(), "this database does not have coop's department tree");
    }

    private static BigDecimal ops(FinancialStatementController.Operations o) {
        return o.netSurplus();
    }

    @Test void aConsolidationsColumnsAddUpToItsOwnStatement() {
        var whole = statements.operations(1, FROM, TO, 15);
        assertEquals("CONSOLIDATED MAIN", whole.unit().name());
        assertTrue(whole.unit().units() > 1);
        var c = statements.consolidating(1, "operations", 15, null, FROM, TO);
        assertTrue(c.columns().stream().anyMatch((col) -> col.id() == 2), "the main office is a column");
        assertEquals(0, ops(whole).compareTo(c.bottomTotal()), "consolidated net surplus " + c.bottomTotal() + " against " + ops(whole));
        BigDecimal columns = c.bottom().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, columns.compareTo(c.bottomTotal()));
    }

    @Test void theTopConsolidationIsTheWholeBranchWhenEveryEntrysDepartmentIsUnderIt() {
        var tree = units.subtree(1);
        List<Integer> used = jdbc.queryForList("SELECT DISTINCT COALESCE(GLTransDEPT, 0) FROM gltrans WHERE GLTransBR_CODE = 1 AND GLTransTR_DATE BETWEEN ? AND ? ORDER BY 1",
            Integer.class, FROM, TO);
        assumeTrue(tree.containsAll(used), "some entries of the month are in departments outside CONSOLIDATED ALL");
        assertEquals(0, ops(statements.operations(1, FROM, TO, null)).compareTo(ops(statements.operations(1, FROM, TO, 1))));
    }

    @Test void aUnitWithNothingUnderItHasNoConsolidatingStatement() {
        var e = assertThrows(ResponseStatusException.class, () -> statements.consolidating(1, "operations", 3, null, FROM, TO));
        assertTrue(e.getReason().contains("no units under it"), e.getReason());
    }

    private static final LocalDate Q1_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate Q1_TO = LocalDate.of(2026, 3, 31);

    private boolean builtTo2025() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ecis_gl_year_built WHERE branch_code = 1 AND tr_year BETWEEN 2013 AND 2025", Integer.class) == 13;
    }

    @Test void aComparativeOperationsStatementShowsBothPeriodsAndTheChange() {
        var c = statements.comparative(1, "operations", null, null, null, FROM, TO, null, null);
        assertEquals("Change", c.totalName());
        assertEquals(0, ops(statements.operations(1, FROM, TO, null)).compareTo(c.bottom().get(0)));
        assertEquals(0, ops(statements.operations(1, FROM.minusYears(1), TO.minusYears(1), null)).compareTo(c.bottom().get(1)));
        assertEquals(0, c.bottom().get(0).subtract(c.bottom().get(1)).compareTo(c.bottomTotal()));
    }

    @Test void theChangesInEquityEndOnTheEquityOfTheStatementOfFinancialCondition() {
        assumeTrue(builtTo2025(), "the year balances to 2025 are not built here");
        var e = statements.changesInEquity(1, Q1_FROM, Q1_TO, null);
        var fc = statements.financialCondition(1, Q1_TO, null);
        BigDecimal equity = fc.sections().stream().filter((s) -> s.name().equals("EQUITY")).findFirst().orElseThrow().total();
        assertEquals(0, equity.compareTo(e.ending()), "equity " + equity + " against " + e.ending());
        var opening = statements.financialCondition(1, Q1_FROM.minusDays(1), null);
        assertEquals(0, opening.sections().get(2).total().compareTo(e.beginning()));
        assertTrue(e.rows().stream().anyMatch((r) -> r.description().startsWith("Net surplus not yet closed")));
    }

    @Test void theCashFlowActivitiesAddUpToTheChangeInCash() {
        assumeTrue(builtTo2025(), "the year balances to 2025 are not built here");
        var cf = statements.cashFlow(1, Q1_FROM, Q1_TO, null);
        BigDecimal explained = cf.activities().stream().map(CashFlowRules.Activity::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, cf.netChange().compareTo(explained.add(cf.unexplained())));
        assertEquals(0, cf.cashEnding().subtract(cf.cashBeginning()).compareTo(cf.netChange()));
        assertEquals(0, cf.netSurplus().compareTo(ops(statements.operations(1, Q1_FROM, Q1_TO, null))), "no year-end close falls in the quarter");
        assertTrue(cf.cashBeginning().signum() != 0, "coop has cash at the start of 2026");
    }

    @Test void aCashFlowClassIsChosenWithAReasonAndAudited() {
        assumeTrue(db.tableExists("ecis_cash_flow_class"), "V040 is not applied here");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var classes = statements.cashFlowClasses(1);
        var heading = classes.stream().filter((c) -> c.summary() && c.type() == 2).findFirst().orElseThrow();
        String other = "FINANCING".equals(heading.category()) ? "OPERATING" : "FINANCING";
        var after = statements.setCashFlowClass(1, heading.code(), new FinancialStatementController.CashFlowClassIn(other, "Borrowings are financing"), auth);
        assertEquals(other, after.stream().filter((c) -> c.code().equals(heading.code())).findFirst().orElseThrow().category());
        // an account under it with none of its own follows the heading
        after.stream().filter((c) -> heading.code().equals(c.parent()) && !c.chosen()).findFirst()
            .ifPresent((k) -> { assertEquals(other, k.category()); assertEquals(heading.code(), k.inheritedFrom()); });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'accounting.cashflow.class' AND entity_id = ?", Integer.class, "1/" + heading.code()));
        assertThrows(ResponseStatusException.class, () -> statements.setCashFlowClass(1, heading.code(), new FinancialStatementController.CashFlowClassIn("OTHER", "x y z"), auth));
    }

    @Test void whatADepartmentIsIsChosenWithAReasonAndAudited() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var before = units.find(3).orElseThrow();
        String other = UnitRules.BRANCH.equals(before.kind()) ? UnitRules.SATELLITE_OFFICE : UnitRules.BRANCH;
        var list = departments.setKind(3, new DepartmentController.Kind(other, "Checking the kinds"), auth);
        var row = list.departments().stream().filter((d) -> d.id() == 3).findFirst().orElseThrow();
        assertEquals(other, row.kind());
        assertTrue(row.kindChosen());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'department.kind' AND entity_id = '3' AND reason = 'Checking the kinds'", Integer.class));
        assertThrows(ResponseStatusException.class, () -> departments.setKind(3, new DepartmentController.Kind("SATELLITE_BRANCH", "x"), auth));
        assertFalse(list.kinds().isEmpty());
    }
}
