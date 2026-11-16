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
import ph.coop.core.procurement.PurchaseOrderController.NewOrder;
import ph.coop.core.procurement.PurchaseRequestController.Decision;
import ph.coop.core.procurement.PurchaseRequestController.NewRequest;
import ph.coop.core.procurement.SupplierController.SupplierIn;

/**
 * Suppliers, purchase requests and purchase orders end to end: a purchaser asks, a different approver decides, the approved request is ordered and a different
 * approver approves the order; nobody decides their own work; cancelling an order frees its request. Real code against the database, rolled back.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class ProcurementFlowTest {

    @Autowired SupplierController suppliers;
    @Autowired PurchaseRequestController requests;
    @Autowired PurchaseOrderController orders;
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
        assumeTrue(db.tableExists("ecis_purchase_order"), "V029 has not been applied");
        try {
            jdbc.update("UPDATE ecis_purchase_order SET status = status WHERE id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V029 are missing");
        }
    }

    private long supplier(Authentication buyer) {
        return suppliers.create(new SupplierIn("tst-1", "Test Trading", "123-456", "Baguio", "0917", "t@example.com", "Ana", 30, true, null), buyer).id();
    }

    private PurchaseRequestController.Summary request(Authentication buyer) {
        return requests.create(new NewRequest("Office supplies for the cashier", null, List.of(
            new PurchaseRequestController.LineIn("Bond paper A4", "ream", d("10"), d("250.00")), new PurchaseRequestController.LineIn("Ballpen", "box", d("5"), d("90.50")))), buyer);
    }

    @Test
    void aRequestIsApprovedOrderedAndTheOrderApprovedByDifferentPeople() {
        Authentication buyer = as("buyer", "PURCHASER");
        long sup = supplier(buyer);
        var r = request(buyer);
        assertEquals("SUBMITTED", r.status());
        assertEquals(d("2952.50"), r.total());
        assertTrue(r.requestNo().matches("PR-\\d{4}-\\d{4}"));

        // the maker cannot decide; a purchaser without the approver role cannot either
        assertThrows(ResponseStatusException.class, () -> requests.approve(r.id(), null, as("buyer", "PURCHASER")));
        assertThrows(ResponseStatusException.class, () -> requests.approve(r.id(), null, as("buyer", "PURCHASER", "PURCHASE_APPROVER")));
        Authentication boss = as("boss", "PURCHASE_APPROVER");
        // an unapproved request cannot be ordered
        assertThrows(ResponseStatusException.class, () -> orders.create(new NewOrder(sup, r.id(), null, null, List.of(new PurchaseOrderController.LineIn("Bond paper A4", "ream", d("10"), d("240")))), as("buyer", "PURCHASER")));
        as("boss", "PURCHASE_APPROVER");
        assertEquals("APPROVED", requests.approve(r.id(), new Decision("ok"), boss).status());
        assertThrows(ResponseStatusException.class, () -> requests.approve(r.id(), null, boss), "decided twice");

        Authentication buyer2 = as("buyer", "PURCHASER");
        var o = orders.create(new NewOrder(sup, r.id(), null, "deliver to the head office", List.of(
            new PurchaseOrderController.LineIn("Bond paper A4", "ream", d("10"), d("240.00")), new PurchaseOrderController.LineIn("Ballpen", "box", d("5"), d("90.50")))), buyer2);
        assertEquals("DRAFT", o.status());
        assertEquals(d("2852.50"), o.total());
        assertEquals(30, o.termsDays(), "the supplier's terms are copied");
        assertEquals("ORDERED", requests.get(r.id()).summary().status());
        // the same request cannot be ordered twice while an order stands
        assertThrows(ResponseStatusException.class, () -> orders.create(new NewOrder(sup, r.id(), null, null, List.of(new PurchaseOrderController.LineIn("x", "pc", d("1"), d("1")))), buyer2));
        // the maker of the order cannot approve it, another approver can
        assertThrows(ResponseStatusException.class, () -> orders.approve(o.id(), as("buyer", "PURCHASER", "PURCHASE_APPROVER")));
        var approved = orders.approve(o.id(), as("boss", "PURCHASE_APPROVER"));
        assertEquals("APPROVED", approved.status());
        assertEquals("boss", approved.approvedBy());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action LIKE 'procurement.order.%' AND entity_id = ?", Integer.class, o.orderNo()));
    }

    @Test
    void cancellingAnOrderFreesItsRequestAndARejectionNeedsAReason() {
        Authentication buyer = as("buyer", "PURCHASER");
        long sup = supplier(buyer);
        var r = request(buyer);
        assertThrows(ResponseStatusException.class, () -> requests.reject(r.id(), new Decision(" "), as("boss", "PURCHASE_APPROVER")));
        requests.approve(r.id(), null, as("boss", "PURCHASE_APPROVER"));
        var o = orders.create(new NewOrder(sup, r.id(), null, null, List.of(new PurchaseOrderController.LineIn("Bond paper A4", "ream", d("10"), d("240")))), as("buyer", "PURCHASER"));
        assertThrows(ResponseStatusException.class, () -> orders.cancel(o.id(), new PurchaseOrderController.Reason(" "), as("buyer", "PURCHASER")));
        assertEquals("CANCELLED", orders.cancel(o.id(), new PurchaseOrderController.Reason("supplier ran out"), as("buyer", "PURCHASER")).status());
        assertEquals("APPROVED", requests.get(r.id()).summary().status(), "the request can be ordered again");
        assertThrows(ResponseStatusException.class, () -> orders.approve(o.id(), as("boss", "PURCHASE_APPROVER")), "a cancelled order is not approved");
        var r2 = request(as("buyer", "PURCHASER"));
        assertEquals("REJECTED", requests.reject(r2.id(), new Decision("not in the budget"), as("boss", "PURCHASE_APPROVER")).status());
        assertThrows(ResponseStatusException.class, () -> requests.cancel(r2.id(), new Decision("changed my mind"), as("buyer", "PURCHASER")));
    }

    @Test
    void suppliersAreUniqueSwitchedOffNotDeletedAndAnInactiveOneCannotBeOrderedFrom() {
        Authentication buyer = as("buyer", "PURCHASER");
        long sup = supplier(buyer);
        assertThrows(ResponseStatusException.class, () -> suppliers.create(new SupplierIn("TST-1", "Another", null, null, null, null, null, null, null, null), buyer));
        assertThrows(ResponseStatusException.class, () -> suppliers.create(new SupplierIn("x", "Bad code", null, null, null, null, null, null, null, null), buyer));
        assertThrows(ResponseStatusException.class, () -> suppliers.create(new SupplierIn("TST-2", "Nobody", null, null, null, null, null, null, null, null), as("clerk", "ACCOUNTANT")));
        as("buyer", "PURCHASER");
        assertEquals(false, suppliers.update(sup, new SupplierIn(null, "Test Trading", null, null, null, null, null, 15, null, false), buyer).active());
        assertEquals(15, suppliers.get(sup).termsDays());
        assertThrows(ResponseStatusException.class, () -> orders.create(new NewOrder(sup, null, null, null, List.of(new PurchaseOrderController.LineIn("x", "pc", d("1"), d("1")))), buyer));
        assertEquals(0, suppliers.list(null, true).stream().filter((s) -> s.id() == sup).count());
        assertEquals(1, suppliers.list("test trad", false).stream().filter((s) -> s.id() == sup).count());
    }
}
