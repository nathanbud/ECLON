package ph.coop.core.pos;

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
import ph.coop.core.pos.PosCatalogController.DiscountIn;
import ph.coop.core.pos.PosCatalogController.PriceIn;
import ph.coop.core.pos.PosCatalogController.PromotionIn;
import ph.coop.core.pos.SaleController.LineIn;
import ph.coop.core.pos.SaleController.NewSale;
import ph.coop.core.pos.SaleController.PayIn;
import ph.coop.core.pos.SaleReturnController.Decision;
import ph.coop.core.pos.SaleReturnController.NewReturn;

/**
 * Selling end to end against the database, rolled back: prices and promotions, stock leaving the warehouse (batches earliest expiry first), cost of goods from the
 * ledger, payments and change, a discount only a supervisor may give, refused sales leave nothing behind, and returns that a different supervisor approves.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class PosFlowTest {

    @Autowired PosCatalogController catalog;
    @Autowired SaleController sales;
    @Autowired SaleReturnController returns;
    @Autowired StockService stock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private long warehouse, paper, milk, unpriced;

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
        assumeTrue(db.tableExists("ecis_sale_return_line") && db.tableExists("ecis_inventory_txn"), "V031 or V033 has not been applied");
        try {
            jdbc.update("UPDATE ecis_sale SET paid = paid WHERE id = -1");
        } catch (RuntimeException e) {
            assumeTrue(false, "the grants of V033 are missing");
        }
        warehouse = db.insertReturningId("INSERT INTO ecis_warehouse (branch_code, code, name, active) VALUES (1, 'POS-T', 'POS test store', 1)", "id");
        paper = product("POS-PAPER", "POS paper", 0, 0);
        milk = product("POS-MILK", "POS milk", 1, 1);
        unpriced = product("POS-NOPRICE", "POS no price", 0, 0);
        as("keeper", "STOREKEEPER");
        stock.post(new StockService.Move("RECEIVE", paper, warehouse, null, null, d("20"), d("60.00"), "TEST", "T1", null), "keeper");
        stock.post(new StockService.Move("RECEIVE", milk, warehouse, "M-OLD", LocalDate.now().minusDays(3), d("5"), d("30.00"), "TEST", "T2", null), "keeper");
        stock.post(new StockService.Move("RECEIVE", milk, warehouse, "M-SOON", LocalDate.now().plusDays(10), d("4"), d("30.00"), "TEST", "T3", null), "keeper");
        stock.post(new StockService.Move("RECEIVE", milk, warehouse, "M-LATER", LocalDate.now().plusDays(90), d("10"), d("30.00"), "TEST", "T4", null), "keeper");
        stock.post(new StockService.Move("RECEIVE", unpriced, warehouse, null, null, d("5"), d("10.00"), "TEST", "T5", null), "keeper");
        catalog.setPrice(new PriceIn(paper, d("100.00")), as("boss", "POS_SUPERVISOR"));
        catalog.setPrice(new PriceIn(milk, d("50.00")), as("boss", "POS_SUPERVISOR"));
    }

    private long product(String sku, String name, int batch, int expiry) {
        return db.insertReturningId("INSERT INTO ecis_product (branch_code, sku, name, unit, cost, reorder_level, track_batch, track_expiry, active, created_by, created_at) VALUES (1, '" + sku + "', '" + name + "', 'pc', 0, 0, "
            + batch + ", " + expiry + ", 1, 'test', NOW())", "id");
    }

    private BigDecimal onHand(long product) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_balance WHERE product_id = ? AND warehouse_id = ?", BigDecimal.class, product, warehouse);
    }

    private NewSale sale(Long discount, List<LineIn> lines, List<PayIn> pays) {
        return new NewSale(warehouse, null, "Walk-in", discount, lines, pays);
    }

    @Test
    void aSaleTakesStockOutAtTheCurrentPriceAndKeepsTheCostOfWhatWasSold() {
        Authentication cashier = as("clerk", "POS_CASHIER");
        var s = sales.sell(sale(null, List.of(new LineIn(paper, d("3"))), List.of(new PayIn("CASH", d("500.00"), null))), cashier);
        assertTrue(s.saleNo().matches("SL-\\d{4}-\\d{4}"));
        assertEquals(0, d("300.00").compareTo(s.total()));
        assertEquals(0, d("200.00").compareTo(s.changeGiven()));
        assertEquals(0, d("17").compareTo(onHand(paper)));
        assertEquals(0, d("180.00").compareTo(jdbc.queryForObject("SELECT cost_total FROM ecis_sale WHERE id = ?", BigDecimal.class, s.id())), "3 x the 60.00 average cost");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_inventory_txn WHERE txn_type = 'SALE' AND ref_no = ?", Integer.class, s.saleNo()));
        assertEquals(1, sales.get(s.id()).payments().size());
        assertEquals(false, s.journaled());

        // a later price change does not touch the old sale, only the next
        catalog.setPrice(new PriceIn(paper, d("120.00")), as("boss", "POS_SUPERVISOR"));
        assertEquals(0, d("100.00").compareTo(sales.get(s.id()).lines().get(0).unitPrice()));
        var next = sales.sell(sale(null, List.of(new LineIn(paper, d("1"))), List.of(new PayIn("CARD", d("120.00"), "ref-1"))), as("clerk", "POS_CASHIER"));
        assertEquals(0, d("120.00").compareTo(next.total()));
        assertEquals(0, next.changeGiven().signum());
    }

    @Test
    void batchesSellEarliestExpiryFirstAndExpiredStockIsNotSold() {
        Authentication cashier = as("clerk", "POS_CASHIER");
        var s = sales.sell(sale(null, List.of(new LineIn(milk, d("6"))), List.of(new PayIn("CASH", d("300.00"), null))), cashier);
        var lines = sales.get(s.id()).lines();
        assertEquals(2, lines.size());
        assertEquals("M-SOON", lines.get(0).batchNo());
        assertEquals(0, d("4").compareTo(lines.get(0).quantity()));
        assertEquals("M-LATER", lines.get(1).batchNo());
        assertEquals(0, d("2").compareTo(lines.get(1).quantity()));
        assertEquals(0, d("13").compareTo(onHand(milk)), "5 expired + 8 later");
        // 8 are left that can be sold; the 5 expired ones cannot be
        assertThrows(ResponseStatusException.class, () -> sales.sell(sale(null, List.of(new LineIn(milk, d("9"))), List.of(new PayIn("CASH", d("450.00"), null))), as("clerk", "POS_CASHIER")));
        assertEquals(0, d("13").compareTo(onHand(milk)));
    }

    @Test
    void promotionsDiscountsAndPaymentsAreChecked() {
        Authentication boss = as("boss", "POS_SUPERVISOR");
        catalog.addPromotion(new PromotionIn("Paper week", paper, d("10"), LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), null), boss);
        catalog.addPromotion(new PromotionIn("Old promo", milk, d("50"), LocalDate.now().minusDays(30), LocalDate.now().minusDays(20), null), boss);
        long senior = catalog.addDiscount(new DiscountIn("sc20", "Senior citizen", "PERCENT", d("20"), true, null), boss).id();
        long bday = catalog.addDiscount(new DiscountIn("bday", "Birthday", "AMOUNT", d("25"), false, null), boss).id();

        as("clerk", "POS_CASHIER");
        var promo = sales.sell(sale(null, List.of(new LineIn(paper, d("2")), new LineIn(milk, d("1"))), List.of(new PayIn("CASH", d("500.00"), null))), as("clerk", "POS_CASHIER"));
        assertEquals(0, d("230.00").compareTo(promo.total()), "paper 200 less 10% = 180, milk 50 with no running promotion");
        assertEquals(0, d("20.00").compareTo(sales.get(promo.id()).lines().get(0).promoOff()));

        // the senior discount needs a supervisor, the birthday one does not
        assertThrows(ResponseStatusException.class, () -> sales.sell(sale(senior, List.of(new LineIn(paper, d("1"))), List.of(new PayIn("CASH", d("100.00"), null))), as("clerk", "POS_CASHIER")));
        var gift = sales.sell(sale(bday, List.of(new LineIn(paper, d("1"))), List.of(new PayIn("CASH", d("100.00"), null))), as("clerk", "POS_CASHIER"));
        assertEquals(0, d("65.00").compareTo(gift.total()), "100 less 10% promo = 90, less 25");
        var sc = sales.sell(sale(senior, List.of(new LineIn(paper, d("1"))), List.of(new PayIn("CASH", d("100.00"), null))), as("boss", "POS_SUPERVISOR"));
        assertEquals(0, d("72.00").compareTo(sc.total()), "90 less 20%");

        BigDecimal before = onHand(paper);
        assertThrows(ResponseStatusException.class, () -> sales.sell(sale(null, List.of(new LineIn(paper, d("1"))), List.of(new PayIn("CASH", d("10.00"), null))), as("clerk", "POS_CASHIER")), "short payment");
        assertThrows(ResponseStatusException.class, () -> sales.sell(sale(null, List.of(new LineIn(paper, d("1"))), List.of(new PayIn("CARD", d("200.00"), null))), as("clerk", "POS_CASHIER")), "card over the total");
        assertThrows(ResponseStatusException.class, () -> sales.sell(sale(null, List.of(new LineIn(unpriced, d("1"))), List.of(new PayIn("CASH", d("100.00"), null))), as("clerk", "POS_CASHIER")), "no price");
        assertThrows(ResponseStatusException.class, () -> sales.sell(sale(null, List.of(new LineIn(paper, d("999"))), List.of(new PayIn("CASH", d("99999.00"), null))), as("clerk", "POS_CASHIER")), "more than on hand");
        assertThrows(ResponseStatusException.class, () -> sales.sell(sale(null, List.of(), List.of()), as("clerk", "POS_CASHIER")), "empty");
        assertEquals(0, before.compareTo(onHand(paper)), "refused sales leave the stock alone");
        assertThrows(ResponseStatusException.class, () -> catalog.setPrice(new PriceIn(paper, d("1.00")), as("clerk", "POS_CASHIER")), "a cashier cannot set prices");
    }

    @Test
    void aReturnPutsGoodsBackOnlyWhenADifferentSupervisorApprovesIt() {
        var s = sales.sell(sale(null, List.of(new LineIn(paper, d("4"))), List.of(new PayIn("CASH", d("400.00"), null))), as("clerk", "POS_CASHIER"));
        assertEquals(0, d("16").compareTo(onHand(paper)));
        assertThrows(ResponseStatusException.class, () -> returns.create(new NewReturn(s.id(), " ", List.of(new SaleReturnController.LineIn(1, d("1")))), as("clerk", "POS_CASHIER")), "needs a reason");
        assertThrows(ResponseStatusException.class, () -> returns.create(new NewReturn(s.id(), "damaged", List.of(new SaleReturnController.LineIn(1, d("5")))), as("clerk", "POS_CASHIER")), "more than was sold");
        var r = returns.create(new NewReturn(s.id(), "damaged", List.of(new SaleReturnController.LineIn(1, d("3")))), as("clerk", "POS_CASHIER"));
        assertEquals("PENDING", r.status());
        assertEquals(0, d("300.00").compareTo(r.refund()));
        assertEquals(0, d("16").compareTo(onHand(paper)), "nothing changes before approval");
        // what is waiting counts: only 1 more can be asked for
        assertThrows(ResponseStatusException.class, () -> returns.create(new NewReturn(s.id(), "again", List.of(new SaleReturnController.LineIn(1, d("2")))), as("clerk", "POS_CASHIER")));
        assertThrows(ResponseStatusException.class, () -> returns.approve(r.id(), null, as("clerk", "POS_CASHIER")), "a cashier cannot approve");
        assertThrows(ResponseStatusException.class, () -> returns.approve(r.id(), null, as("clerk", "POS_CASHIER", "POS_SUPERVISOR")), "not the one who recorded it");
        assertEquals("APPROVED", returns.approve(r.id(), new Decision("ok"), as("boss", "POS_SUPERVISOR")).status());
        assertEquals(0, d("19").compareTo(onHand(paper)));
        assertEquals(0, d("300.00").compareTo(sales.get(s.id()).summary().returnedTotal()));
        assertEquals(0, d("3").compareTo(sales.get(s.id()).lines().get(0).returned()));
        assertThrows(ResponseStatusException.class, () -> returns.approve(r.id(), null, as("boss", "POS_SUPERVISOR")), "decided once");
        var rej = returns.create(new NewReturn(s.id(), "changed mind", List.of(new SaleReturnController.LineIn(1, d("1")))), as("clerk", "POS_CASHIER"));
        assertThrows(ResponseStatusException.class, () -> returns.reject(rej.id(), new Decision(" "), as("boss", "POS_SUPERVISOR")));
        assertEquals("REJECTED", returns.reject(rej.id(), new Decision("not eligible"), as("boss", "POS_SUPERVISOR")).status());
        assertEquals(0, d("19").compareTo(onHand(paper)));
        for (long product : new long[] {paper, milk}) {
            assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_txn WHERE product_id = ?", BigDecimal.class, product)
                .compareTo(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM ecis_inventory_balance WHERE product_id = ?", BigDecimal.class, product)), "the balance equals the ledger");
        }
    }
}
