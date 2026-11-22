package ph.coop.core.inventory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import ph.coop.core.inventory.StockService.Move;

/**
 * Stock counts (guide phase 13). A storekeeper counts a warehouse and enters what was found; the screen records what the books showed at that moment. A different stock
 * approver approves the count, which writes an ADJUSTMENT ledger row for every difference (counted minus books) or rejects it with a reason. Nothing changes until approval.
 */
@RestController
@RequestMapping("/api/inventory/counts")
@PreAuthorize("hasAnyRole('STOREKEEPER','STOCK_APPROVER','ACCOUNTANT','ADMIN')")
class StockCountController {

    record LineIn(long productId, String batchNo, BigDecimal countedQty) {}

    record NewCount(long warehouseId, List<LineIn> lines) {}

    record Decision(String note) {}

    record Summary(long id, String countNo, long warehouseId, String warehouse, java.time.LocalDate countDate, String status, String countedBy, String decidedBy, String decisionNote, int differences) {}

    record LineOut(long productId, String sku, String product, String batchNo, BigDecimal systemQty, BigDecimal countedQty, BigDecimal variance) {}

    record Detail(Summary summary, List<LineOut> lines) {}

    private static final String SUMMARY = """
            SELECT c.id, c.count_no, c.warehouse_id, w.name, c.count_date, c.status, c.counted_by, c.decided_by, c.decision_note,
                   (SELECT COUNT(*) FROM ecis_stock_count_line l WHERE l.count_id = c.id AND l.counted_qty <> l.system_qty)
            FROM ecis_stock_count c JOIN ecis_warehouse w ON w.id = c.warehouse_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;
    private final StockService stock;

    StockCountController(JdbcTemplate jdbc, Db db, InventorySupport support, StockService stock) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.stock = stock;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Summary(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getDate(5).toLocalDate(), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10));
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) String status) {
        return jdbc.query(SUMMARY + " WHERE c.branch_code = ? AND (CAST(? AS CHAR) IS NULL OR c.status = ?) ORDER BY c.id DESC LIMIT 300", StockCountController::map, InventorySupport.BRANCH, status, status);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE c.branch_code = ? AND c.id = ?", StockCountController::map, InventorySupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stock count.");
        List<LineOut> lines = jdbc.query("""
                SELECT l.product_id, p.sku, p.name, COALESCE(b.batch_no, ''), l.system_qty, l.counted_qty FROM ecis_stock_count_line l
                JOIN ecis_product p ON p.id = l.product_id LEFT JOIN ecis_inventory_batch b ON b.id = l.batch_id WHERE l.count_id = ? ORDER BY l.line_no
                """, (rs, i) -> new LineOut(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), InventoryRules.variance(rs.getBigDecimal(5), rs.getBigDecimal(6))), id);
        return new Detail(s.get(0), lines);
    }

    @PostMapping
    @Transactional
    Summary create(@RequestBody NewCount in, Authentication auth) {
        support.requireKeeper(auth);
        if (in.lines() == null || in.lines().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one counted line.");
        Integer wh = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_warehouse WHERE branch_code = ? AND id = ? AND active = 1", Integer.class, InventorySupport.BRANCH, in.warehouseId());
        if (wh == null || wh == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That warehouse was not found or is switched off. Choose an active warehouse from the list.");
        List<Object[]> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (LineIn l : in.lines()) {
            n++;
            String at = "Line " + n + ": ";
            if (l.countedQty() == null || l.countedQty().signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, at + "the counted quantity cannot be negative.");
            if (l.countedQty().stripTrailingZeros().scale() > 3 || l.countedQty().compareTo(InventoryRules.MAX_QUANTITY) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, at + "the counted quantity is not valid.");
            List<Object[]> prod = jdbc.query("SELECT track_batch FROM ecis_product WHERE branch_code = ? AND id = ?", (rs, i) -> new Object[] {rs.getInt(1) == 1}, InventorySupport.BRANCH, l.productId());
            if (prod.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, at + "no such product.");
            boolean tracked = (Boolean) prod.get(0)[0], named = l.batchNo() != null && !l.batchNo().isBlank();
            if (tracked != named) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, at + (tracked ? "this product is tracked by batch, give the batch." : "this product is not tracked by batch."));
            long batch = named ? stock.batchId(l.productId(), l.batchNo(), null, false) : 0;
            if (!seen.add(l.productId() + "/" + batch)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, at + "this product and batch are counted twice.");
            resolved.add(new Object[] {l.productId(), batch, stock.onHand(l.productId(), in.warehouseId(), batch), l.countedQty()});
        }
        String no = support.nextNumber("ecis_stock_count", "count_no", "SC", support.today().getYear());
        long id = db.insertReturningId("INSERT INTO ecis_stock_count (branch_code, count_no, warehouse_id, count_date, status, counted_by, created_at) VALUES (?, ?, ?, ?, 'SUBMITTED', ?, ?)",
            "id", InventorySupport.BRANCH, no, in.warehouseId(), support.today(), auth.getName(), Timestamp.valueOf(support.now()));
        n = 0;
        for (Object[] r : resolved) jdbc.update("INSERT INTO ecis_stock_count_line (count_id, line_no, product_id, batch_id, system_qty, counted_qty) VALUES (?, ?, ?, ?, ?, ?)", id, ++n, r[0], r[1], r[2], r[3]);
        support.audit(auth, "inventory.count.create", "ecis_stock_count", no, "warehouse " + in.warehouseId() + ", " + resolved.size() + " lines");
        return get(id).summary();
    }

    private Summary decide(long id, boolean approve, Decision in, Authentication auth) {
        support.requireApprover(auth);
        List<Summary> found = jdbc.query(SUMMARY + " WHERE c.branch_code = ? AND c.id = ? FOR UPDATE", StockCountController::map, InventorySupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stock count.");
        Summary c = found.get(0);
        if (!c.status().equals("SUBMITTED")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This count is already " + c.status().toLowerCase() + ".");
        String own = InventorySupport.own(c.countedBy(), auth.getName(), "count");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, own);
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (!approve && note == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the count is rejected.");
        if (note != null && note.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note is too long: it can have at most 255 characters. Shorten it and try again.");
        if (approve) {
            List<Object[]> lines = jdbc.query("SELECT product_id, batch_id, system_qty, counted_qty FROM ecis_stock_count_line WHERE count_id = ? ORDER BY line_no",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4)}, id);
            for (Object[] l : lines) {
                BigDecimal v = InventoryRules.variance((BigDecimal) l[2], (BigDecimal) l[3]);
                if (v.signum() == 0) continue;
                String batchNo = (Long) l[1] == 0 ? null : jdbc.queryForObject("SELECT batch_no FROM ecis_inventory_batch WHERE id = ?", String.class, l[1]);
                stock.post(new Move("ADJUSTMENT", (Long) l[0], c.warehouseId(), batchNo, null, v, null, "COUNT", c.countNo(), "Stock count " + c.countNo()), c.countedBy());
            }
        }
        jdbc.update("UPDATE ecis_stock_count SET status = ?, decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ? AND status = 'SUBMITTED'", approve ? "APPROVED" : "REJECTED", auth.getName(), Timestamp.valueOf(support.now()), note, id);
        support.audit(auth, "inventory.count." + (approve ? "approve" : "reject"), "ecis_stock_count", c.countNo(), note == null ? c.differences() + " differences" : note);
        return get(id).summary();
    }

    @PostMapping("/{id}/approve")
    @Transactional
    Summary approve(@PathVariable long id, @RequestBody(required = false) Decision in, Authentication auth) {
        return decide(id, true, in, auth);
    }

    @PostMapping("/{id}/reject")
    @Transactional
    Summary reject(@PathVariable long id, @RequestBody Decision in, Authentication auth) {
        return decide(id, false, in, auth);
    }
}
