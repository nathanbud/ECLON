package ph.coop.core.pos;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.inventory.InventorySupport;
import ph.coop.core.inventory.StockService;
import ph.coop.core.inventory.StockService.Move;
import ph.coop.core.inventory.StockService.Posted;
import ph.coop.core.pos.PosRules.Allocation;
import ph.coop.core.pos.PosRules.Payment;

/**
 * Selling (guide phase 14): Sale, Payment, Inventory Reduction. A cashier rings up products at the current price (with the promotion that applies today), may give a
 * discount, takes payment, and the sale takes its stock out of the warehouse through the inventory ledger, first-expiry-first-out for batch products; the sale number
 * is the receipt number. The cost of the goods sold is kept on each line from the ledger. Revenue and cost entries in the general ledger are not written yet
 * (every sale carries journaled = false until the owner's accounts are set).
 */
@RestController
@RequestMapping("/api/pos/sales")
@PreAuthorize("hasAnyRole('POS_CASHIER','POS_SUPERVISOR','ACCOUNTANT','ADMIN')")
class SaleController {

    record LineIn(long productId, BigDecimal quantity) {}

    record PayIn(String method, BigDecimal amount, String reference) {}

    record NewSale(long warehouseId, Long clientId, String customerName, Long discountId, List<LineIn> lines, List<PayIn> payments) {}

    record Summary(long id, String saleNo, LocalDate saleDate, LocalDateTime saleTime, String warehouse, String cashier, Long clientId, String customerName, BigDecimal subtotal, BigDecimal discountTotal,
                   BigDecimal total, BigDecimal paid, BigDecimal changeGiven, BigDecimal returnedTotal, boolean journaled) {}

    record LineOut(int lineNo, long productId, String sku, String product, String unit, String batchNo, BigDecimal quantity, BigDecimal unitPrice, BigDecimal promoOff, BigDecimal lineTotal, BigDecimal returned) {}

    record PayOut(String method, BigDecimal amount, String reference) {}

    record Detail(Summary summary, String discount, List<LineOut> lines, List<PayOut> payments) {}

    private static final String SUMMARY = """
            SELECT s.id, s.sale_no, s.sale_date, s.sale_time, w.name, s.cashier, s.client_id, s.customer_name, s.subtotal, s.discount_total, s.total, s.paid, s.change_given, s.returned_total, s.journaled
            FROM ecis_sale s JOIN ecis_warehouse w ON w.id = s.warehouse_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;
    private final StockService stock;
    private final PosSupport pos;

    SaleController(JdbcTemplate jdbc, Db db, InventorySupport support, StockService stock, PosSupport pos) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.stock = stock;
        this.pos = pos;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long client = rs.getLong(7);
        Long clientId = rs.wasNull() ? null : client;
        return new Summary(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getTimestamp(4).toLocalDateTime(), rs.getString(5), rs.getString(6), clientId, rs.getString(8), rs.getBigDecimal(9),
            rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBigDecimal(14), rs.getInt(15) == 1);
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) LocalDate date, @RequestParam(required = false) String cashier) {
        return jdbc.query(SUMMARY + " WHERE s.branch_code = ? AND (? IS NULL OR s.sale_date = ?) AND (? IS NULL OR s.cashier = ?) ORDER BY s.id DESC LIMIT 300", SaleController::map,
            InventorySupport.BRANCH, date, date, cashier, cashier);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE s.branch_code = ? AND s.id = ?", SaleController::map, InventorySupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sale.");
        List<LineOut> lines = jdbc.query("""
                SELECT l.line_no, l.product_id, p.sku, p.name, p.unit, COALESCE(b.batch_no, ''), l.quantity, l.unit_price, l.promo_off, l.line_total, l.qty_returned
                FROM ecis_sale_line l JOIN ecis_product p ON p.id = l.product_id LEFT JOIN ecis_inventory_batch b ON b.id = l.batch_id WHERE l.sale_id = ? ORDER BY l.line_no
                """, (rs, i) -> new LineOut(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
                rs.getBigDecimal(11)), id);
        List<PayOut> pays = jdbc.query("SELECT method, amount, reference FROM ecis_sale_payment WHERE sale_id = ? ORDER BY line_no", (rs, i) -> new PayOut(rs.getString(1), rs.getBigDecimal(2), rs.getString(3)), id);
        String discount = jdbc.queryForList("SELECT d.name FROM ecis_sale s JOIN ecis_discount d ON d.id = s.discount_id WHERE s.id = ?", String.class, id).stream().findFirst().orElse(null);
        return new Detail(s.get(0), discount, lines, pays);
    }

    @PostMapping
    @Transactional
    Summary sell(@RequestBody NewSale in, Authentication auth) {
        pos.requireSeller(auth);
        LocalDate today = support.today();
        Integer wh = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_warehouse WHERE branch_code = ? AND id = ? AND active = 1", Integer.class, InventorySupport.BRANCH, in.warehouseId());
        if (wh == null || wh == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such active warehouse.");
        if (in.lines() == null || in.lines().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one product.");
        if (in.lines().size() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A sale can have at most 200 lines.");
        if (in.clientId() != null) {
            Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, InventorySupport.BRANCH, in.clientId());
            if (c == null || c == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such member.");
        }
        if (in.customerName() != null && in.customerName().length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The customer name is too long (80 characters at most).");

        // price every line and choose the batches
        record Planned(long productId, long batchId, String batchNo, BigDecimal quantity, BigDecimal price, Long promoId, BigDecimal promoOff, BigDecimal total) {}
        List<Planned> planned = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int n = 0;
        for (LineIn l : in.lines()) {
            n++;
            String at = "Line " + n + ": ";
            List<String> qp = PosRules.quantityProblems(l.quantity());
            if (!qp.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, at + String.join(" ", qp));
            List<Object[]> prod = jdbc.query("SELECT name, track_batch FROM ecis_product WHERE branch_code = ? AND id = ? AND active = 1", (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2) == 1}, InventorySupport.BRANCH, l.productId());
            if (prod.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, at + "no such active product.");
            String name = (String) prod.get(0)[0];
            List<BigDecimal> price = jdbc.queryForList("SELECT price FROM ecis_product_price WHERE product_id = ? ORDER BY id DESC LIMIT 1", BigDecimal.class, l.productId());
            if (price.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, at + name + " has no price yet.");
            List<Object[]> promo = jdbc.query("SELECT id, percent_off FROM ecis_promotion WHERE branch_code = ? AND product_id = ? AND active = 1 AND start_date <= ? AND end_date >= ? ORDER BY percent_off DESC LIMIT 1",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getBigDecimal(2)}, InventorySupport.BRANCH, l.productId(), today, today);
            List<Allocation> parts;
            if ((Boolean) prod.get(0)[1]) {
                parts = PosRules.allocate(stock.batchesWithStock(l.productId(), in.warehouseId()), l.quantity(), today);
                if (parts == null) throw new ResponseStatusException(HttpStatus.CONFLICT, at + "not enough unexpired stock of " + name + " in this warehouse.");
            } else {
                BigDecimal have = stock.onHand(l.productId(), in.warehouseId(), 0);
                if (have.compareTo(l.quantity()) < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, at + "only " + have.stripTrailingZeros().toPlainString() + " of " + name + " on hand.");
                parts = List.of(new Allocation(0, null, l.quantity()));
            }
            for (Allocation a : parts) {
                BigDecimal off = promo.isEmpty() ? BigDecimal.ZERO : PosRules.promoOff(a.quantity(), price.get(0), (BigDecimal) promo.get(0)[1]);
                BigDecimal total = PosRules.gross(a.quantity(), price.get(0)).subtract(off);
                planned.add(new Planned(l.productId(), a.batchId(), a.batchNo(), a.quantity(), price.get(0), promo.isEmpty() ? null : (Long) promo.get(0)[0], off, total));
                subtotal = subtotal.add(total);
            }
        }

        // the sale-level discount
        BigDecimal discountTotal = BigDecimal.ZERO;
        if (in.discountId() != null) {
            List<Object[]> d = jdbc.query("SELECT kind, value, requires_supervisor FROM ecis_discount WHERE branch_code = ? AND id = ? AND active = 1", (rs, i) -> new Object[] {rs.getString(1), rs.getBigDecimal(2), rs.getInt(3) == 1},
                InventorySupport.BRANCH, in.discountId());
            if (d.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such active discount.");
            if ((Boolean) d.get(0)[2] && !pos.isSupervisor(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a POS supervisor can give this discount.");
            discountTotal = PosRules.saleDiscount((String) d.get(0)[0], (BigDecimal) d.get(0)[1], subtotal);
        }
        BigDecimal total = subtotal.subtract(discountTotal);
        List<Payment> pays = in.payments() == null ? List.of() : in.payments().stream().map((p) -> new Payment(p.method(), p.amount())).toList();
        List<String> pp = PosRules.paymentProblems(total, pays);
        if (!pp.isEmpty()) throw InventorySupport.bad(pp);
        BigDecimal paid = pays.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime now = support.now();
        String no = support.nextNumber("ecis_sale", "sale_no", "SL", today.getYear());
        long id = db.insertReturningId("""
                INSERT INTO ecis_sale (branch_code, sale_no, sale_date, sale_time, warehouse_id, cashier, client_id, customer_name, discount_id, subtotal, discount_total, total, paid, change_given)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "id", InventorySupport.BRANCH, no, today, Timestamp.valueOf(now), in.warehouseId(), auth.getName(), in.clientId(), in.customerName() == null || in.customerName().isBlank() ? null : in.customerName().trim(),
            in.discountId(), subtotal, discountTotal, total, paid, PosRules.change(total, pays));

        // inventory reduction: the cost of what was sold comes from the ledger
        BigDecimal cost = BigDecimal.ZERO;
        n = 0;
        for (Planned p : planned) {
            Posted posted = stock.post(new Move("SALE", p.productId(), in.warehouseId(), p.batchNo(), null, p.quantity().negate(), null, "SALE", no, null), auth.getName());
            jdbc.update("INSERT INTO ecis_sale_line (sale_id, line_no, product_id, batch_id, quantity, unit_price, promo_id, promo_off, line_total, unit_cost) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, ++n, p.productId(), p.batchId(), p.quantity(), p.price(), p.promoId(), p.promoOff(), p.total(), posted.unitCost());
            cost = cost.add(PosRules.money(p.quantity().multiply(posted.unitCost())));
        }
        n = 0;
        for (PayIn p : in.payments() == null ? List.<PayIn>of() : in.payments())
            jdbc.update("INSERT INTO ecis_sale_payment (sale_id, line_no, method, amount, reference) VALUES (?, ?, ?, ?, ?)", id, ++n, p.method(), p.amount(), p.reference() == null || p.reference().isBlank() ? null : p.reference().trim());
        jdbc.update("UPDATE ecis_sale SET cost_total = ? WHERE id = ?", cost, id);
        support.audit(auth, "pos.sale", "ecis_sale", no, planned.size() + " lines, total " + total.toPlainString() + (discountTotal.signum() > 0 ? ", discount " + discountTotal.toPlainString() : ""));
        return get(id).summary();
    }
}
