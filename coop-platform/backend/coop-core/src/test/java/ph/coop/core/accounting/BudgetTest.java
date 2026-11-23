package ph.coop.core.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/** Budgets on coop: set with a reason and audited, read back, and set against the ledger. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
@WithMockUser(username = "tester", roles = "ACCOUNTANT")
class BudgetTest {

    @Autowired BudgetController budgets;
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_budget"), "V041 is not applied here");
    }

    @Test void aBudgetIsSetWithAReasonAuditedAndSetAgainstTheLedger() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var lines = budgets.list(1, 2025);
        var revenue = lines.stream().filter((l) -> l.type() == 4 && l.lastYearActual().signum() > 0).findFirst().orElse(lines.stream().filter((l) -> l.type() == 4).findFirst().orElseThrow());
        var after = budgets.save(1, 2025, new BudgetController.BudgetChange(List.of(new BudgetController.BudgetIn(revenue.accountCode(), new BigDecimal("1200000"))), "Approved budget 2025"), auth);
        assertEquals(0, new BigDecimal("1200000").compareTo(after.stream().filter((l) -> l.accountCode().equals(revenue.accountCode())).findFirst().orElseThrow().budget()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'accounting.budget.change' AND entity_id = ?", Integer.class, "1/2025/" + revenue.accountCode()));
        var v = budgets.variance(1, 2025, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31));
        assertEquals(3, v.months());
        var line = v.lines().stream().filter((l) -> l.accountCode().equals(revenue.accountCode())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("300000.00").compareTo(line.budget()));
        assertEquals(0, line.actual().subtract(line.budget()).compareTo(line.variance()));
        assertTrue(v.revenueBudget().compareTo(new BigDecimal("300000")) >= 0);
        // saving the same amount again changes nothing and says so
        assertThrows(ResponseStatusException.class, () -> budgets.save(1, 2025, new BudgetController.BudgetChange(List.of(new BudgetController.BudgetIn(revenue.accountCode(), new BigDecimal("1200000"))), "again"), auth));
        assertThrows(ResponseStatusException.class, () -> budgets.save(1, 2025, new BudgetController.BudgetChange(List.of(new BudgetController.BudgetIn("10001", BigDecimal.ONE)), "an asset"), auth));
    }

    @Test @WithMockUser(username = "tester", roles = "AUDITOR") void anAuditorReadsBudgetsButCannotChangeThem() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(budgets.list(1, 2025).size() > 0);
        assertThrows(AccessDeniedException.class, () -> budgets.save(1, 2025, new BudgetController.BudgetChange(List.of(), "x y z"), auth));
    }
}
