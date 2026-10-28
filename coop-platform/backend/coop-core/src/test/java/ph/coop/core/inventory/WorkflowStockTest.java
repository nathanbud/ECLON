package ph.coop.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.workflow.WorkflowEngine;
import ph.coop.core.workflow.WorkflowEngine.Step;

/**
 * The workflow engine on stock adjustments, against the database, rolled back: a small adjustment is approved by the stock approver alone; a valuable one waits for the extra
 * approver and writes the stock ledger only when the whole chain has agreed; a rejection leaves stock untouched.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class WorkflowStockTest {

    @Autowired WorkflowEngine engine;
    @Autowired StockAdjustmentController adjustments;
    @Autowired StockService stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private long warehouse, product;

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private Authentication as(String user, String... roles) {
        Authentication a = new TestingAuthenticationToken(user, "x", java.util.Arrays.stream(roles).map((r) -> "ROLE_" + r).toArray(String[]::new));
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_workflow_instance") && db.tableExists("ecis_stock_adjustment"), "V036 or V031 has not been applied");
        try {
            jdbc.update("UPDATE ecis_workflow_instance SET status = status WHERE id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V036 are missing");
        }
        warehouse = db.insertReturningId("INSERT INTO ecis_warehouse (branch_code, code, name, active) VALUES (1, 'WF-T', 'Workflow test store', 1)", "id");
        product = db.insertReturningId("INSERT INTO ecis_product (branch_code, sku, name, unit, cost, reorder_level, track_batch, track_expiry, active, created_by, created_at) VALUES (1, 'WF-P', 'Workflow item', 'pc', 100, 0, 0, 0, 1, 'test', NOW())", "id");
        stock.post(new StockService.Move("RECEIVE", product, warehouse, null, null, d("50"), d("100"), "TEST", "T", null), "keeper");
        engine.save("STOCK_ADJUSTMENT", true, List.of(new Step(1, "Manager sign-off", "MANAGER", d("1000"))), "admin");
    }

    private BigDecimal onHand() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_balance WHERE product_id = ?", BigDecimal.class, product);
    }

    private long adjustment(String qty) {
        return adjustments.create(new StockAdjustmentController.NewAdjustment(warehouse, "DAMAGE", "water damage", List.of(new StockAdjustmentController.LineIn(product, null, d(qty)))), as("keeper", "STOREKEEPER")).id();
    }

    @Test
    void aSmallAdjustmentNeedsOnlyTheStockApprover() {
        long small = adjustment("-5");
        assertEquals("APPROVED", adjustments.approve(small, null, as("boss", "STOCK_APPROVER")).status());
        assertEquals(0, d("45").compareTo(onHand()));
    }

    @Test
    void aValuableAdjustmentWaitsForTheManagerAndWritesStockOnlyAtTheEnd() {
        long big = adjustment("-20");
        var first = adjustments.approve(big, null, as("boss", "STOCK_APPROVER"));
        assertEquals("SUBMITTED", first.status());
        assertEquals("Manager sign-off (manager)", first.waiting());
        assertEquals(0, d("50").compareTo(onHand()), "nothing is written yet");
        assertThrows(ResponseStatusException.class, () -> adjustments.approve(big, null, as("boss", "STOCK_APPROVER")), "not twice");
        assertThrows(ResponseStatusException.class, () -> engine.decide("STOCK_ADJUSTMENT", String.valueOf(big), true, null, "keeper", Set.of("MANAGER")), "the maker cannot take the step");
        assertEquals("APPROVED", engine.decide("STOCK_ADJUSTMENT", String.valueOf(big), true, "seen", "mgr", Set.of("MANAGER")).status());
        assertEquals("APPROVED", adjustments.get(big).summary().status());
        assertEquals("mgr", adjustments.get(big).summary().decidedBy());
        assertEquals(0, d("30").compareTo(onHand()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_inventory_txn WHERE ref_type = 'ADJUSTMENT' AND txn_type = 'DAMAGE' AND product_id = ?", Integer.class, product));
    }

    @Test
    void aRejectionLeavesStockAlone() {
        long big = adjustment("-30");
        adjustments.approve(big, null, as("boss", "STOCK_APPROVER"));
        assertEquals("REJECTED", engine.decide("STOCK_ADJUSTMENT", String.valueOf(big), false, "count again first", "mgr", Set.of("MANAGER")).status());
        assertEquals("REJECTED", adjustments.get(big).summary().status());
        assertEquals(0, d("50").compareTo(onHand()));
        // the usual approver rejecting while a chain waits closes the chain too
        long other = adjustment("-30");
        adjustments.approve(other, null, as("boss", "STOCK_APPROVER"));
        adjustments.reject(other, new StockAdjustmentController.Decision("no"), as("boss2", "STOCK_APPROVER"));
        assertEquals("CANCELLED", engine.instance("STOCK_ADJUSTMENT", String.valueOf(other)).status());
    }
}
