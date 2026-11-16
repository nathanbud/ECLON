package ph.coop.core.procurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
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
import ph.coop.core.procurement.GoodsReceiptController.NewReceipt;
import ph.coop.core.procurement.GoodsReceiptController.Reason;
import ph.coop.core.procurement.GoodsReceiptController.TakeIn;
import ph.coop.core.procurement.PurchaseOrderController.LineIn;
import ph.coop.core.procurement.PurchaseOrderController.NewOrder;
import ph.coop.core.procurement.SupplierController.SupplierIn;

/**
 * Receiving goods against an approved order: partial then complete receipts move the order APPROVED, PARTIAL, RECEIVED; more than expected is refused; the person who
 * made the order cannot receive it; a different approver can reverse a receipt, which takes the goods back off the order. Real code against the database, rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class GoodsReceiptTest {

    @Autowired SupplierController suppliers;
    @Autowired PurchaseOrderController orders;
    @Autowired GoodsReceiptController receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

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
        assumeTrue(db.tableExists("ecis_goods_receipt") && db.tableExists("ecis_purchase_order"), "V029 or V030 has not been applied");
        try {
            jdbc.update("UPDATE ecis_goods_receipt SET status = status WHERE id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V030 are missing");
        }
    }

    /** An approved order for 10 reams at 240 and 5 boxes at 90.50, made by "buyer". */
    private long approvedOrder() {
        Authentication buyer = as("buyer", "PURCHASER");
        long sup = suppliers.create(new SupplierIn("rcv-1", "Receiving Test Trading", null, null, null, null, null, 0, true, null), buyer).id();
        long order = orders.create(new NewOrder(sup, null, null, null, List.of(new LineIn("Bond paper A4", "ream", d("10"), d("240.00")), new LineIn("Ballpen", "box", d("5"), d("90.50")))), buyer).id();
        orders.approve(order, as("boss", "PURCHASE_APPROVER"));
        return order;
    }

    private String status(long order) {
        return jdbc.queryForObject("SELECT status FROM ecis_purchase_order WHERE id = ?", String.class, order);
    }

    @Test
    void partialAndCompleteReceiptsMoveTheOrderAndAReceiptCanBeReversed() {
        long order = approvedOrder();
        // the maker of an order cannot receive it
        assertThrows(ResponseStatusException.class, () -> receipts.create(new NewReceipt(order, "DR-1", null, List.of(new TakeIn(1, d("1")))), as("buyer", "RECEIVER")));
        Authentication store = as("store", "RECEIVER");
        assertThrows(ResponseStatusException.class, () -> receipts.create(new NewReceipt(order, null, null, List.of(new TakeIn(1, d("11")))), store), "more than ordered");
        assertThrows(ResponseStatusException.class, () -> receipts.create(new NewReceipt(order, null, null, List.of()), store), "nothing received");
        assertThrows(ResponseStatusException.class, () -> receipts.create(new NewReceipt(order, null, null, List.of(new TakeIn(1, d("1")))), as("clerk", "PURCHASER")), "not a receiver");

        as("store", "RECEIVER");
        var first = receipts.create(new NewReceipt(order, "DR-1001", "first truck", List.of(new TakeIn(1, d("6")))), store);
        assertEquals("RECEIVED", first.status());
        assertTrue(first.receiptNo().matches("GR-\\d{4}-\\d{4}"));
        assertEquals("PARTIAL", status(order));
        assertEquals(0, d("6").compareTo(orders.get(order).lines().get(0).received()));
        // the order can no longer be cancelled once goods came in
        assertThrows(ResponseStatusException.class, () -> orders.cancel(order, new PurchaseOrderController.Reason("changed"), as("buyer", "PURCHASER")));

        as("store", "RECEIVER");
        assertThrows(ResponseStatusException.class, () -> receipts.create(new NewReceipt(order, null, null, List.of(new TakeIn(1, d("4.001")))), store), "only 4 more are expected");
        var second = receipts.create(new NewReceipt(order, "DR-1002", null, List.of(new TakeIn(1, d("4")), new TakeIn(2, d("5")))), store);
        assertEquals("RECEIVED", status(order));
        assertEquals(2, receipts.get(second.id()).lines().size());
        assertThrows(ResponseStatusException.class, () -> receipts.create(new NewReceipt(order, null, null, List.of(new TakeIn(2, d("1")))), as("store", "RECEIVER")), "a complete order takes nothing more");
        assertEquals(2, receipts.list(order).size());

        // reversing: not by the receiver, not without a reason, not twice
        assertThrows(ResponseStatusException.class, () -> receipts.reverse(second.id(), new Reason("wrong count"), as("store", "RECEIVER", "PURCHASE_APPROVER")));
        assertThrows(ResponseStatusException.class, () -> receipts.reverse(second.id(), new Reason(" "), as("boss", "PURCHASE_APPROVER")));
        var undone = receipts.reverse(second.id(), new Reason("wrong count"), as("boss", "PURCHASE_APPROVER"));
        assertEquals("REVERSED", undone.status());
        assertEquals("boss", undone.reversedBy());
        assertEquals("PARTIAL", status(order));
        assertEquals(0, d("6").compareTo(orders.get(order).lines().get(0).received()));
        assertEquals(0, BigDecimal.ZERO.compareTo(orders.get(order).lines().get(1).received()));
        assertThrows(ResponseStatusException.class, () -> receipts.reverse(second.id(), new Reason("again"), as("boss", "PURCHASE_APPROVER")));
        // the first receipt can be reversed too, which sends the order back to approved
        receipts.reverse(first.id(), new Reason("never arrived"), as("boss", "PURCHASE_APPROVER"));
        assertEquals("APPROVED", status(order));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action LIKE 'procurement.receipt.%' AND entity_id IN (?, ?)", Integer.class, first.receiptNo(), second.receiptNo()),
            "two receipts made and two reversed");
    }
}
