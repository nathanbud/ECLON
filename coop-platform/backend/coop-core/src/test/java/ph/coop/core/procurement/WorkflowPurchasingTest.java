package ph.coop.core.procurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import ph.coop.core.procurement.PurchaseOrderController.LineIn;
import ph.coop.core.procurement.PurchaseOrderController.NewOrder;
import ph.coop.core.procurement.SupplierController.SupplierIn;
import ph.coop.core.workflow.WorkflowEngine;
import ph.coop.core.workflow.WorkflowEngine.Step;

/**
 * The workflow engine on purchase orders and requests, against the database, rolled back: an order under every limit is approved by the usual approver alone; one over a limit
 * waits for the extra approvers in turn; the maker and anyone who already approved cannot take a later step; a rejection cancels the order; changing the configuration
 * does not change an order already under way; a cancelled order leaves no chain waiting.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class WorkflowPurchasingTest {

    @Autowired WorkflowEngine engine;
    @Autowired PurchaseOrderController orders;
    @Autowired PurchaseRequestController requests;
    @Autowired SupplierController suppliers;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private long supplier;

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
        assumeTrue(db.tableExists("ecis_workflow_instance") && db.tableExists("ecis_purchase_order"), "V036 has not been applied");
        try {
            jdbc.update("UPDATE ecis_workflow_instance SET status = status WHERE id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V036 are missing");
        }
        supplier = suppliers.create(new SupplierIn("wf-1", "Workflow Test Trading", null, null, null, null, null, 0, true, null), as("buyer", "PURCHASER")).id();
        // purchase orders: the Accountant also approves from 100,000, the Manager as well from 1,000,000
        engine.save("PURCHASE_ORDER", true, List.of(new Step(0, "Accountant check", "ACCOUNTANT", d("100000")), new Step(0, "Manager approval", "MANAGER", d("1000000"))), "admin");
    }

    private long order(String price) {
        return orders.create(new NewOrder(supplier, null, null, null, List.of(new LineIn("Big item", "pc", d("1"), d(price)))), as("buyer", "PURCHASER")).id();
    }

    private String status(long order) {
        return jdbc.queryForObject("SELECT status FROM ecis_purchase_order WHERE id = ?", String.class, order);
    }

    @Test
    void anOrderUnderEveryLimitIsApprovedByTheUsualApproverAlone() {
        long small = order("99999.99");
        assertEquals("APPROVED", orders.approve(small, as("boss", "PURCHASE_APPROVER")).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_workflow_instance WHERE entity_type = 'PURCHASE_ORDER' AND entity_id = ?", Integer.class, String.valueOf(small)));
    }

    @Test
    void anOrderOverALimitWaitsForTheExtraApproversInTurn() {
        long big = order("1200000.00");
        var first = orders.approve(big, as("boss", "PURCHASE_APPROVER"));
        assertEquals("DRAFT", first.status(), "still waiting");
        assertEquals("Accountant check (accountant)", first.waiting());
        assertEquals("Accountant check (accountant)", orders.get(big).summary().waiting());
        assertThrows(ResponseStatusException.class, () -> orders.approve(big, as("boss", "PURCHASE_APPROVER")), "the first approval cannot be given twice");

        // who sees it waiting: the accountant, not the maker, not the first approver, not the manager yet
        assertTrue(engine.waitingFor(Set.of("ACCOUNTANT"), "acct").stream().anyMatch((w) -> w.entityId().equals(String.valueOf(big)) && w.step().equals("Accountant check")));
        assertTrue(engine.waitingFor(Set.of("ACCOUNTANT"), "buyer").stream().noneMatch((w) -> w.entityId().equals(String.valueOf(big))), "the maker");
        assertTrue(engine.waitingFor(Set.of("ACCOUNTANT"), "boss").stream().noneMatch((w) -> w.entityId().equals(String.valueOf(big))), "already approved");
        assertTrue(engine.waitingFor(Set.of("MANAGER"), "mgr").stream().noneMatch((w) -> w.entityId().equals(String.valueOf(big))), "not the manager's step yet");

        String id = String.valueOf(big);
        assertThrows(ResponseStatusException.class, () -> engine.decide("PURCHASE_ORDER", id, true, null, "clerk", Set.of("PURCHASER")), "wrong role");
        assertThrows(ResponseStatusException.class, () -> engine.decide("PURCHASE_ORDER", id, true, null, "buyer", Set.of("ACCOUNTANT")), "the maker");
        assertThrows(ResponseStatusException.class, () -> engine.decide("PURCHASE_ORDER", id, true, null, "boss", Set.of("ACCOUNTANT")), "already took the first step");
        var afterAcct = engine.decide("PURCHASE_ORDER", id, true, "figures check out", "acct", Set.of("ACCOUNTANT"));
        assertEquals("WAITING", afterAcct.status());
        assertEquals("Manager approval", afterAcct.waitingFor());
        assertEquals("DRAFT", status(big));
        assertThrows(ResponseStatusException.class, () -> engine.decide("PURCHASE_ORDER", id, true, null, "acct", Set.of("ACCOUNTANT", "MANAGER")), "the accountant cannot also be the manager");
        assertTrue(engine.waitingFor(Set.of("MANAGER"), "mgr").stream().anyMatch((w) -> w.entityId().equals(id)));

        var done = engine.decide("PURCHASE_ORDER", id, true, null, "mgr", Set.of("MANAGER"));
        assertEquals("APPROVED", done.status());
        assertEquals("APPROVED", status(big));
        assertEquals("mgr", jdbc.queryForObject("SELECT approved_by FROM ecis_purchase_order WHERE id = ?", String.class, big), "the last approver is recorded");
        assertEquals(List.of("boss", "acct", "mgr"), done.actions().stream().map(WorkflowEngine.Action::actor).toList());
        assertEquals(List.of(0, 1, 2), done.actions().stream().map(WorkflowEngine.Action::stepNo).toList());
        assertNull(orders.get(big).summary().waiting());
        assertThrows(ResponseStatusException.class, () -> engine.decide("PURCHASE_ORDER", id, true, null, "x", Set.of("MANAGER")), "nothing is waiting any more");
    }

    @Test
    void aRejectionCancelsTheOrderAndAnAdministratorMayTakeAnyStep() {
        long big = order("150000.00");
        orders.approve(big, as("boss", "PURCHASE_APPROVER"));
        String id = String.valueOf(big);
        assertThrows(ResponseStatusException.class, () -> engine.decide("PURCHASE_ORDER", id, false, " ", "acct", Set.of("ACCOUNTANT")), "a reason is needed");
        var rejected = engine.decide("PURCHASE_ORDER", id, false, "over the budget", "acct", Set.of("ACCOUNTANT"));
        assertEquals("REJECTED", rejected.status());
        assertEquals("CANCELLED", status(big));
        assertTrue(jdbc.queryForObject("SELECT remarks FROM ecis_purchase_order WHERE id = ?", String.class, big).contains("over the budget"));
        // an administrator can stand in for a role
        long other = order("200000.00");
        orders.approve(other, as("boss", "PURCHASE_APPROVER"));
        assertEquals("APPROVED", engine.decide("PURCHASE_ORDER", String.valueOf(other), true, null, "root", Set.of("ADMIN")).status());
        assertEquals("APPROVED", status(other));
    }

    @Test
    void changingTheConfigurationDoesNotChangeAnOrderUnderWayAndCancellingLeavesNothingWaiting() {
        long big = order("1500000.00");
        orders.approve(big, as("boss", "PURCHASE_APPROVER"));
        engine.save("PURCHASE_ORDER", true, List.of(), "admin");
        String id = String.valueOf(big);
        assertEquals("Accountant check (accountant)", orders.get(big).summary().waiting(), "the order keeps the steps it started with");
        engine.decide("PURCHASE_ORDER", id, true, null, "acct", Set.of("ACCOUNTANT"));
        assertEquals("APPROVED", engine.decide("PURCHASE_ORDER", id, true, null, "mgr", Set.of("MANAGER")).status());
        // new orders now need nothing extra
        assertEquals("APPROVED", orders.approve(order("9000000.00"), as("boss", "PURCHASE_APPROVER")).status());

        engine.save("PURCHASE_ORDER", true, List.of(new Step(1, "Accountant check", "ACCOUNTANT", d("1"))), "admin");
        long waiting = order("5000.00");
        orders.approve(waiting, as("boss", "PURCHASE_APPROVER"));
        orders.cancel(waiting, new PurchaseOrderController.Reason("not needed"), as("buyer", "PURCHASER"));
        assertEquals("CANCELLED", status(waiting));
        assertNull(engine.waitingFor("PURCHASE_ORDER", String.valueOf(waiting)));
        assertEquals("CANCELLED", engine.instance("PURCHASE_ORDER", String.valueOf(waiting)).status());
        // switched off, there is no gate at all
        engine.save("PURCHASE_ORDER", false, List.of(new Step(1, "Accountant check", "ACCOUNTANT", d("1"))), "admin");
        assertEquals("APPROVED", orders.approve(order("5000000.00"), as("boss", "PURCHASE_APPROVER")).status());
    }

    @Test
    void purchaseRequestsFollowTheSameChain() {
        engine.save("PURCHASE_REQUEST", true, List.of(new Step(1, "Accountant check", "ACCOUNTANT", d("200000"))), "admin");
        var r = requests.create(new PurchaseRequestController.NewRequest("New server room", null, List.of(new PurchaseRequestController.LineIn("Aircon", "pc", d("2"), d("150000")))), as("buyer", "PURCHASER"));
        var first = requests.approve(r.id(), null, as("boss", "PURCHASE_APPROVER"));
        assertEquals("SUBMITTED", first.status());
        assertEquals("Accountant check (accountant)", first.waiting());
        assertEquals("APPROVED", engine.decide("PURCHASE_REQUEST", String.valueOf(r.id()), true, null, "acct", Set.of("ACCOUNTANT")).status());
        assertEquals("APPROVED", requests.get(r.id()).summary().status());
        assertEquals("acct", requests.get(r.id()).summary().decidedBy());
        // a request the usual approver rejects while a chain waits closes the chain
        var r2 = requests.create(new PurchaseRequestController.NewRequest("Another", null, List.of(new PurchaseRequestController.LineIn("Aircon", "pc", d("2"), d("150000")))), as("buyer", "PURCHASER"));
        requests.approve(r2.id(), null, as("boss", "PURCHASE_APPROVER"));
        assertEquals("REJECTED", requests.reject(r2.id(), new PurchaseRequestController.Decision("changed our mind"), as("boss2", "PURCHASE_APPROVER")).status());
        assertNull(engine.waitingFor("PURCHASE_REQUEST", String.valueOf(r2.id())));
    }

    @Test
    void theConfigurationIsCheckedAndOnlyKnownKindsCanBeSet() {
        assertThrows(ResponseStatusException.class, () -> engine.save("PURCHASE_ORDER", true, List.of(new Step(1, "X", "JANITOR", d("1"))), "admin"));
        assertThrows(ResponseStatusException.class, () -> engine.save("CIC_SUBMISSION", true, List.of(), "admin"), "no module has joined for that yet");
        var def = engine.definitions().stream().filter((x) -> x.entityType().equals("PURCHASE_ORDER")).findFirst().orElseThrow();
        assertEquals(2, def.steps().size());
        assertEquals(List.of(1, 2), def.steps().stream().map(Step::no).toList(), "steps are numbered in the order given");
        assertTrue(engine.definitions().stream().anyMatch((x) -> x.entityType().equals("STOCK_ADJUSTMENT")));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'workflow.configure' AND entity_id = 'PURCHASE_ORDER' AND username = 'admin' AND new_value LIKE '%Manager approval%'", Integer.class) >= 1, "every configuration change is audited with before and after");
    }
}
