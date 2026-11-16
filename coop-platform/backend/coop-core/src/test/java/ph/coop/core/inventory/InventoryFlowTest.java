package ph.coop.core.inventory;

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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.inventory.InventoryMasterController.ProductIn;
import ph.coop.core.inventory.InventoryMasterController.WarehouseIn;
import ph.coop.core.inventory.StockAdjustmentController.Decision;
import ph.coop.core.inventory.StockAdjustmentController.NewAdjustment;
import ph.coop.core.inventory.StockCountController.NewCount;
import ph.coop.core.inventory.StockMovementController.MoveIn;
import ph.coop.core.inventory.StockMovementController.TransferIn;

/**
 * Stock end to end: products and warehouses, receipts with average cost, issues that cannot go below zero, batches and expiry, transfers, approved adjustments and counts.
 * After every step the balance must equal the sum of the ledger. Real code against the database, rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class InventoryFlowTest {

    @Autowired InventoryMasterController master;
    @Autowired StockMovementController movements;
    @Autowired StockAdjustmentController adjustments;
    @Autowired StockCountController counts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private long wh1, wh2, plain, batched;

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
        assumeTrue(db.tableExists("ecis_inventory_txn") && db.tableExists("ecis_stock_count"), "V031 has not been applied");
        try {
            jdbc.update("UPDATE ecis_inventory_balance SET quantity = quantity WHERE product_id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V031 are missing");
        }
        Authentication keeper = as("keeper", "STOREKEEPER");
        wh1 = master.addWarehouse(new WarehouseIn("tst-a", "Test main store", null), keeper).id();
        wh2 = master.addWarehouse(new WarehouseIn("tst-b", "Test branch store", null), keeper).id();
        plain = master.addProduct(new ProductIn("tst-paper", "Test bond paper", null, "ream", d("20"), false, false, null), keeper).id();
        batched = master.addProduct(new ProductIn("tst-milk", "Test milk", null, "box", null, true, true, null), keeper).id();
    }

    private void balanced(long product) {
        BigDecimal sumLedger = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_txn WHERE product_id = ?", BigDecimal.class, product);
        BigDecimal sumBalance = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_balance WHERE product_id = ?", BigDecimal.class, product);
        assertEquals(0, sumLedger.compareTo(sumBalance), "the balance must equal the ledger");
    }

    private MoveIn receive(long product, long wh, String qty, String cost, String batch, LocalDate expiry) {
        return new MoveIn("RECEIVE", product, wh, batch, expiry, d(qty), d(cost), "DR-1", null);
    }

    @Test
    void receiptsCarryTheAverageCostAndIssuesCannotGoBelowZero() {
        Authentication keeper = as("keeper", "STOREKEEPER");
        var r1 = movements.move(receive(plain, wh1, "10", "100.00", null, null), keeper);
        assertTrue(r1.txnNo().matches("INV-\\d{4}-\\d{6}"));
        assertEquals(0, d("100").compareTo(master.product(plain).cost()));
        movements.move(receive(plain, wh1, "5", "130.00", null, null), keeper);
        assertEquals(0, d("110").compareTo(master.product(plain).cost()), "average of 10 at 100 and 5 at 130");
        assertEquals(0, d("15").compareTo(master.product(plain).onHand()));
        var issue = movements.move(new MoveIn("ISSUE", plain, wh1, null, null, d("4"), null, "REQ-1", "for the cashier"), keeper);
        assertEquals(0, d("110").compareTo(issue.unitCost()), "issues go out at the current cost");
        assertEquals(0, d("110").compareTo(master.product(plain).cost()), "an issue leaves the average alone");
        assertThrows(ResponseStatusException.class, () -> movements.move(new MoveIn("ISSUE", plain, wh1, null, null, d("11.001"), null, null, null), keeper), "only 11 left");
        assertThrows(ResponseStatusException.class, () -> movements.move(new MoveIn("ISSUE", plain, wh2, null, null, d("1"), null, null, null), keeper), "nothing in the other store");
        assertThrows(ResponseStatusException.class, () -> movements.move(new MoveIn("RECEIVE", plain, wh1, null, null, d("1"), null, null, null), keeper), "a receipt needs its cost");
        assertThrows(ResponseStatusException.class, () -> movements.move(new MoveIn("SALE", plain, wh1, null, null, d("1"), null, null, null), keeper), "sales come from the point of sale");
        assertThrows(ResponseStatusException.class, () -> movements.move(new MoveIn("ISSUE", plain, wh1, null, null, d("0"), null, null, null), keeper));
        balanced(plain);
        assertEquals(3, master.ledger(plain, null, 50).size());
        assertEquals(0, d("11").compareTo(master.stock(plain).get(0).quantity()));
        // somebody who is not a storekeeper cannot move stock
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> movements.move(receive(plain, wh1, "1", "1", null, null), as("clerk", "ACCOUNTANT")));
    }

    @Test
    void batchesExpiryAndTransfers() {
        Authentication keeper = as("keeper", "STOREKEEPER");
        LocalDate today = LocalDate.now();
        assertThrows(ResponseStatusException.class, () -> movements.move(receive(batched, wh1, "20", "50", null, null), keeper), "tracked by batch needs a batch");
        assertThrows(ResponseStatusException.class, () -> movements.move(receive(batched, wh1, "20", "50", "L1", null), keeper), "a new expiring batch needs a date");
        movements.move(receive(batched, wh1, "20", "50", "L1", today.plusDays(30)), keeper);
        movements.move(receive(batched, wh1, "10", "50", "L0", today.minusDays(5)), keeper);
        assertThrows(ResponseStatusException.class, () -> movements.move(new MoveIn("ISSUE", batched, wh1, "L0", null, d("1"), null, null, null), keeper), "an expired batch cannot be issued");
        var tr = movements.transfer(new TransferIn(batched, wh1, wh2, "L1", d("8"), "to the branch"), keeper);
        assertTrue(tr.outTxnNo().startsWith("INV-") && tr.inTxnNo().startsWith("INV-"));
        assertThrows(ResponseStatusException.class, () -> movements.transfer(new TransferIn(batched, wh1, wh1, "L1", d("1"), null), keeper), "same warehouse");
        assertThrows(ResponseStatusException.class, () -> movements.transfer(new TransferIn(batched, wh1, wh2, "L1", d("13"), null), keeper), "only 12 left in the first store");
        assertEquals(2, master.stock(batched).stream().filter((s) -> s.batchNo().equals("L1")).count(), "L1 is now in both stores");
        assertEquals(0, d("30").compareTo(master.product(batched).onHand()), "a transfer does not change the total");
        balanced(batched);
        // a warehouse that holds stock cannot be switched off, an empty one can
        assertThrows(ResponseStatusException.class, () -> master.updateWarehouse(wh1, new WarehouseIn(null, null, false), keeper));
        assertEquals(false, master.updateWarehouse(master.addWarehouse(new WarehouseIn("tst-c", "Empty store", null), keeper).id(), new WarehouseIn(null, null, false), keeper).active());
    }

    @Test
    void anAdjustmentChangesNothingUntilADifferentApproverApprovesIt() {
        Authentication keeper = as("keeper", "STOREKEEPER");
        movements.move(receive(plain, wh1, "10", "100", null, null), keeper);
        assertThrows(ResponseStatusException.class, () -> adjustments.create(new NewAdjustment(wh1, "DAMAGE", "wet", List.of(new StockAdjustmentController.LineIn(plain, null, d("2")))), keeper), "damage only takes stock out");
        assertThrows(ResponseStatusException.class, () -> adjustments.create(new NewAdjustment(wh1, "DAMAGE", " ", List.of(new StockAdjustmentController.LineIn(plain, null, d("-2")))), keeper), "needs a reason");
        var a = adjustments.create(new NewAdjustment(wh1, "DAMAGE", "flooded the shelf", List.of(new StockAdjustmentController.LineIn(plain, null, d("-3")))), keeper);
        assertEquals("SUBMITTED", a.status());
        assertEquals(0, d("10").compareTo(master.product(plain).onHand()), "still 10 until approved");
        assertThrows(ResponseStatusException.class, () -> adjustments.approve(a.id(), null, as("keeper", "STOREKEEPER", "STOCK_APPROVER")), "the maker cannot approve");
        assertThrows(ResponseStatusException.class, () -> adjustments.approve(a.id(), null, as("keeper2", "STOREKEEPER")), "not an approver");
        assertEquals("APPROVED", adjustments.approve(a.id(), new Decision("seen"), as("boss", "STOCK_APPROVER")).status());
        assertEquals(0, d("7").compareTo(master.product(plain).onHand()));
        assertEquals("DAMAGE", master.ledger(plain, null, 1).get(0).type());
        assertThrows(ResponseStatusException.class, () -> adjustments.approve(a.id(), null, as("boss", "STOCK_APPROVER")), "decided once");
        var b = adjustments.create(new NewAdjustment(wh1, "ADJUSTMENT", "found more", List.of(new StockAdjustmentController.LineIn(plain, null, d("2")))), as("keeper", "STOREKEEPER"));
        assertEquals("REJECTED", adjustments.reject(b.id(), new Decision("recount first"), as("boss", "STOCK_APPROVER")).status());
        assertEquals(0, d("7").compareTo(master.product(plain).onHand()), "a rejection changes nothing");
        // an approval that would take stock below zero fails whole
        var c = adjustments.create(new NewAdjustment(wh1, "EXPIRY", "expired", List.of(new StockAdjustmentController.LineIn(plain, null, d("-8")))), as("keeper", "STOREKEEPER"));
        assertThrows(ResponseStatusException.class, () -> adjustments.approve(c.id(), null, as("boss", "STOCK_APPROVER")));
        balanced(plain);
    }

    @Test
    void anApprovedCountPostsOnlyTheDifferences() {
        Authentication keeper = as("keeper", "STOREKEEPER");
        movements.move(receive(plain, wh1, "10", "100", null, null), keeper);
        movements.move(receive(batched, wh1, "6", "50", "L1", LocalDate.now().plusDays(90)), keeper);
        assertThrows(ResponseStatusException.class, () -> counts.create(new NewCount(wh1, List.of(new StockCountController.LineIn(plain, null, d("-1")))), keeper));
        assertThrows(ResponseStatusException.class, () -> counts.create(new NewCount(wh1, List.of(new StockCountController.LineIn(plain, null, d("9")), new StockCountController.LineIn(plain, null, d("8")))), keeper), "counted twice");
        var c = counts.create(new NewCount(wh1, List.of(new StockCountController.LineIn(plain, null, d("8")), new StockCountController.LineIn(batched, "L1", d("6")))), keeper);
        assertEquals(1, c.differences());
        assertEquals(0, d("-2").compareTo(counts.get(c.id()).lines().get(0).variance()));
        assertEquals(0, d("10").compareTo(master.product(plain).onHand()), "nothing changes before approval");
        assertThrows(ResponseStatusException.class, () -> counts.approve(c.id(), null, as("keeper", "STOREKEEPER", "STOCK_APPROVER")), "the counter cannot approve");
        assertEquals("APPROVED", counts.approve(c.id(), null, as("boss", "STOCK_APPROVER")).status());
        assertEquals(0, d("8").compareTo(master.product(plain).onHand()));
        assertEquals(0, d("6").compareTo(master.product(batched).onHand()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_inventory_txn WHERE ref_type = 'COUNT' AND ref_no = ?", Integer.class, c.countNo()), "only the line that differed");
        var r = counts.create(new NewCount(wh1, List.of(new StockCountController.LineIn(plain, null, d("20")))), as("keeper", "STOREKEEPER"));
        assertEquals("REJECTED", counts.reject(r.id(), new StockCountController.Decision("count again"), as("boss", "STOCK_APPROVER")).status());
        assertEquals(0, d("8").compareTo(master.product(plain).onHand()));
        balanced(plain);
        balanced(batched);
    }

    @Test
    void productsAreUniqueAndHistoryLocksTheirUnit() {
        Authentication keeper = as("keeper", "STOREKEEPER");
        assertThrows(ResponseStatusException.class, () -> master.addProduct(new ProductIn("TST-PAPER", "Dup", null, "ream", null, false, false, null), keeper));
        assertThrows(ResponseStatusException.class, () -> master.addProduct(new ProductIn("tst-x", "Bad unit", null, "furlong", null, false, false, null), keeper));
        assertThrows(ResponseStatusException.class, () -> master.addProduct(new ProductIn("tst-y", "Expiry without batch", null, "pc", null, false, true, null), keeper));
        movements.move(receive(plain, wh1, "3", "10", null, null), keeper);
        assertThrows(ResponseStatusException.class, () -> master.updateProduct(plain, new ProductIn(null, null, null, "box", null, null, null, null), keeper), "unit locked by history");
        assertThrows(ResponseStatusException.class, () -> master.updateProduct(plain, new ProductIn(null, null, null, null, null, null, null, false), keeper), "stock on hand");
        assertEquals("Renamed paper", master.updateProduct(plain, new ProductIn(null, "Renamed paper", null, null, d("5"), null, null, null), keeper).name());
        assertEquals(1, master.products("renamed", null, false).size());
        assertTrue(master.products(null, null, false).stream().filter((p) -> p.id() == plain).findFirst().orElseThrow().low(), "3 on hand against a reorder level of 5");
    }
}
