package ph.coop.core.procurement;

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
import ph.coop.core.inventory.StockService;
import ph.coop.core.procurement.GoodsReceiptController.NewReceipt;
import ph.coop.core.procurement.GoodsReceiptController.Reason;
import ph.coop.core.procurement.GoodsReceiptController.TakeIn;
import ph.coop.core.procurement.PurchaseOrderController.LineIn;
import ph.coop.core.procurement.PurchaseOrderController.NewOrder;
import ph.coop.core.procurement.SupplierController.SupplierIn;

/**
 * Goods received on an order line that names a product go into stock in the chosen warehouse at the order's price (so the average cost follows what was paid), batches
 * and expiry are honoured, free-text lines are not stocked, and reversing a receipt takes the same stock back out unless it has been used. Rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class GoodsReceiptStockTest {

    @Autowired SupplierController suppliers;
    @Autowired PurchaseOrderController orders;
    @Autowired GoodsReceiptController receipts;
    @Autowired StockService stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private long warehouse, paper, milk;

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
        assumeTrue(db.tableExists("ecis_inventory_txn") && db.columnExists("ecis_purchase_order_line", "product_id") && db.columnExists("ecis_goods_receipt_line", "stocked"), "V031 or V032 has not been applied");
        try {
            jdbc.update("UPDATE ecis_inventory_balance SET quantity = quantity WHERE product_id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V031 are missing");
        }
        warehouse = db.insertReturningId("INSERT INTO ecis_warehouse (branch_code, code, name, active) VALUES (1, 'RCV-T', 'Receiving test store', 1)", "id");
        paper = db.insertReturningId("INSERT INTO ecis_product (branch_code, sku, name, unit, cost, reorder_level, track_batch, track_expiry, active, created_by, created_at) VALUES (1, 'RCV-PAPER', 'Test paper', 'ream', 0, 0, 0, 0, 1, 'test', NOW())", "id");
        milk = db.insertReturningId("INSERT INTO ecis_product (branch_code, sku, name, unit, cost, reorder_level, track_batch, track_expiry, active, created_by, created_at) VALUES (1, 'RCV-MILK', 'Test milk', 'box', 0, 0, 1, 1, 1, 'test', NOW())", "id");
    }

    private BigDecimal onHand(long product) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_balance WHERE product_id = ?", BigDecimal.class, product);
    }

    private BigDecimal cost(long product) {
        return jdbc.queryForObject("SELECT cost FROM ecis_product WHERE id = ?", BigDecimal.class, product);
    }

    /** An approved order: 10 reams of paper at 240, 12 boxes of milk at 50 (by batch), and a free-text service line. */
    private long approvedOrder() {
        Authentication buyer = as("buyer", "PURCHASER");
        long sup = suppliers.create(new SupplierIn("rcv-stock", "Stocked Receiving Trading", null, null, null, null, null, 0, true, null), buyer).id();
        long order = orders.create(new NewOrder(sup, null, null, null, List.of(
            new LineIn("Bond paper", "ignored", d("10"), d("240.00"), paper), new LineIn("Fresh milk", "box", d("12"), d("50.00"), milk), new LineIn("Delivery charge", "lot", d("1"), d("100.00")))), buyer).id();
        orders.approve(order, as("boss", "PURCHASE_APPROVER"));
        return order;
    }

    @Test
    void receivedProductLinesGoIntoStockAtTheOrderPriceAndAReversalTakesThemBack() {
        long order = approvedOrder();
        assertEquals("ream", orders.get(order).lines().get(0).unit(), "a product line takes the product's unit");
        assertEquals(paper, orders.get(order).lines().get(0).productId());
        assertTrue(orders.get(order).lines().get(1).trackBatch() && orders.get(order).lines().get(1).trackExpiry());
        Authentication store = as("store", "RECEIVER");
        LocalDate expiry = LocalDate.now().plusDays(60);

        assertThrows(ResponseStatusException.class, () -> receipts.create(new NewReceipt(order, null, "DR-1", null, List.of(new TakeIn(1, d("6")))), store), "stocked goods need a warehouse");
        assertThrows(ResponseStatusException.class, () -> receipts.create(new NewReceipt(order, warehouse, "DR-1", null, List.of(new TakeIn(2, d("4")))), as("store", "RECEIVER")), "milk needs a batch");
        assertEquals(0, onHand(paper).signum(), "a refused receipt leaves no stock behind");

        var first = receipts.create(new NewReceipt(order, warehouse, "DR-1", null, List.of(new TakeIn(1, d("6")), new TakeIn(2, d("12"), "M-1", expiry), new TakeIn(3, d("1")))), as("store", "RECEIVER"));
        assertEquals(0, d("6").compareTo(onHand(paper)));
        assertEquals(0, d("12").compareTo(onHand(milk)));
        assertEquals(0, d("240").compareTo(cost(paper)), "the cost is the order's price");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_inventory_txn WHERE ref_type = 'RECEIPT' AND ref_no = ?", Integer.class, first.receiptNo()), "the free-text service line is not stocked");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_goods_receipt_line WHERE receipt_id = ? AND stocked = 0", Integer.class, first.id()));

        // a second delivery of paper, taken back at once, leaves the stock as it was
        var second = receipts.create(new NewReceipt(order, warehouse, "DR-2", null, List.of(new TakeIn(1, d("4")))), as("store", "RECEIVER"));
        assertEquals(0, d("10").compareTo(onHand(paper)));
        assertEquals("REVERSED", receipts.reverse(second.id(), new Reason("wrong count"), as("boss", "PURCHASE_APPROVER")).status());
        assertEquals(0, d("6").compareTo(onHand(paper)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_inventory_txn WHERE ref_type = 'REVERSAL' AND ref_no = ?", Integer.class, second.receiptNo()));
        assertEquals("PARTIAL", jdbc.queryForObject("SELECT status FROM ecis_purchase_order WHERE id = ?", String.class, order));

        // once the paper is used, the first receipt cannot come back out, and nothing of it changes
        stock.post(new StockService.Move("ISSUE", paper, warehouse, null, null, d("-5"), null, "REQ", "R1", "used"), "store");
        assertThrows(ResponseStatusException.class, () -> receipts.reverse(first.id(), new Reason("sent back"), as("boss", "PURCHASE_APPROVER")), "only 1 ream left");
        assertEquals("RECEIVED", receipts.get(first.id()).summary().status());
        assertEquals(0, d("12").compareTo(onHand(milk)));
        // with the paper back in the store it reverses whole, paper and milk
        stock.post(new StockService.Move("RECEIVE", paper, warehouse, null, null, d("5"), d("240"), "MANUAL", "X", "found"), "store");
        assertEquals("REVERSED", receipts.reverse(first.id(), new Reason("sent back"), as("boss", "PURCHASE_APPROVER")).status());
        assertEquals(0, onHand(paper).signum());
        assertEquals(0, onHand(milk).signum());
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM ecis_purchase_order WHERE id = ?", String.class, order));
        for (long product : new long[] {paper, milk}) {
            assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_txn WHERE product_id = ?", BigDecimal.class, product).compareTo(onHand(product)), "the balance equals the ledger");
        }
    }
}
