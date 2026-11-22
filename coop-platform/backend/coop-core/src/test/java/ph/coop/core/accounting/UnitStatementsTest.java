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
