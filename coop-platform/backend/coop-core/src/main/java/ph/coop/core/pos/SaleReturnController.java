package ph.coop.core.pos;

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
import ph.coop.core.inventory.InventorySupport;
import ph.coop.core.inventory.StockService;
import ph.coop.core.inventory.StockService.Move;

/**
 * Sales returns (guide phase 14). A cashier records goods brought back against a sale, with a reason; the refund is worked out from what the customer paid for those
 * units (promotion and sale discount included). A POS supervisor who did not record the return approves it, which puts the goods back into the warehouse they were sold
 * from and counts the refund against the sale, or rejects it with a reason. Nothing changes until it is approved.
 */
@RestController
@RequestMapping("/api/pos/returns")
@PreAuthorize("hasAnyRole('POS_CASHIER','POS_SUPERVISOR','ACCOUNTANT','ADMIN')")
class SaleReturnController {

    record LineIn(int saleLineNo, BigDecimal quantity) {}

    record NewReturn(long saleId, String reason, List<LineIn> lines) {}

    record Decision(String note) {}

    record Summary(long id, String returnNo, long saleId, String saleNo, String reason, String status, BigDecimal refund, String requestedBy, String decidedBy, String decisionNote) {}

    record LineOut(int saleLineNo, String sku, String product, BigDecimal quantity, BigDecimal amount) {}

    record Detail(Summary summary, List<LineOut> lines) {}

    private static final String SUMMARY = """
            SELECT r.id, r.return_no, r.sale_id, s.sale_no, r.reason, r.status, r.refund, r.requested_by, r.decided_by, r.decision_note
            FROM ecis_sale_return r JOIN ecis_sale s ON s.id = r.sale_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;
    private final StockService stock;
    private final PosSupport pos;

    SaleReturnController(JdbcTemplate jdbc, Db db, InventorySupport support, StockService stock, PosSupport pos) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.stock = stock;
        this.pos = pos;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Summary(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8), rs.getString(9), rs.getString(10));
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) String status) {
        return jdbc.query(SUMMARY + " WHERE r.branch_code = ? AND (CAST(? AS CHAR) IS NULL OR r.status = ?) ORDER BY r.id DESC LIMIT 300", SaleReturnController::map, InventorySupport.BRANCH, status, status);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE r.branch_code = ? AND r.id = ?", SaleReturnController::map, InventorySupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such return.");
        List<LineOut> lines = jdbc.query("""
                SELECT x.sale_line_no, p.sku, p.name, x.quantity, x.amount FROM ecis_sale_return_line x
                JOIN ecis_sale_return r ON r.id = x.return_id JOIN ecis_sale_line l ON l.sale_id = r.sale_id AND l.line_no = x.sale_line_no JOIN ecis_product p ON p.id = l.product_id
                WHERE x.return_id = ? ORDER BY x.line_no
                """, (rs, i) -> new LineOut(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5)), id);
        return new Detail(s.get(0), lines);
    }

    @PostMapping
    @Transactional
    Summary create(@RequestBody NewReturn in, Authentication auth) {
        pos.requireSeller(auth);
        if (in.reason() == null || in.reason().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason for the return.");
        if (in.reason().length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The reason is too long (255 characters at most).");
        if (in.lines() == null || in.lines().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose what is coming back.");
        List<BigDecimal[]> sale = jdbc.query("SELECT subtotal, discount_total FROM ecis_sale WHERE branch_code = ? AND id = ? FOR UPDATE", (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, InventorySupport.BRANCH, in.saleId());
        if (sale.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sale.");
        Set<Integer> seen = new HashSet<>();
        List<Object[]> priced = new ArrayList<>();
        BigDecimal refund = BigDecimal.ZERO;
        for (LineIn l : in.lines()) {
            if (!seen.add(l.saleLineNo())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sale line " + l.saleLineNo() + " is listed twice.");
            List<BigDecimal[]> line = jdbc.query("SELECT quantity, line_total, qty_returned FROM ecis_sale_line WHERE sale_id = ? AND line_no = ? FOR UPDATE", (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)}, in.saleId(), l.saleLineNo());
            if (line.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The sale has no line " + l.saleLineNo() + ".");
            BigDecimal waiting = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(x.quantity), 0) FROM ecis_sale_return_line x JOIN ecis_sale_return r ON r.id = x.return_id
                    WHERE r.sale_id = ? AND r.status = 'PENDING' AND x.sale_line_no = ?
                    """, BigDecimal.class, in.saleId(), l.saleLineNo());
            List<String> problems = PosRules.returnProblems(line.get(0)[0], line.get(0)[2].add(waiting), l.quantity());
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sale line " + l.saleLineNo() + ": " + String.join(" ", problems));
            BigDecimal amount = PosRules.refund(line.get(0)[1], line.get(0)[0], l.quantity(), sale.get(0)[0], sale.get(0)[1]);
            priced.add(new Object[] {l.saleLineNo(), l.quantity(), amount});
            refund = refund.add(amount);
        }
        String no = support.nextNumber("ecis_sale_return", "return_no", "RT", support.today().getYear());
        long id = db.insertReturningId("INSERT INTO ecis_sale_return (branch_code, return_no, sale_id, reason, status, refund, requested_by, requested_at) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)", "id",
            InventorySupport.BRANCH, no, in.saleId(), in.reason().trim(), refund, auth.getName(), Timestamp.valueOf(support.now()));
        int n = 0;
        for (Object[] p : priced) jdbc.update("INSERT INTO ecis_sale_return_line (return_id, line_no, sale_line_no, quantity, amount) VALUES (?, ?, ?, ?, ?)", id, ++n, p[0], p[1], p[2]);
        support.audit(auth, "pos.return.create", "ecis_sale_return", no, "refund " + refund.toPlainString() + ": " + in.reason().trim());
        return get(id).summary();
    }

    private Summary decide(long id, boolean approve, Decision in, Authentication auth) {
        pos.requireSupervisor(auth);
        List<Summary> found = jdbc.query(SUMMARY + " WHERE r.branch_code = ? AND r.id = ? FOR UPDATE", SaleReturnController::map, InventorySupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such return.");
        Summary r = found.get(0);
        if (!r.status().equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This return is already " + r.status().toLowerCase() + ".");
        String own = InventorySupport.own(r.requestedBy(), auth.getName(), "return");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, own);
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (!approve && note == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the return is rejected.");
        if (note != null && note.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note is too long: it can have at most 255 characters. Shorten it and try again.");
        if (approve) {
            Long warehouse = jdbc.queryForObject("SELECT warehouse_id FROM ecis_sale WHERE id = ?", Long.class, r.saleId());
            List<Object[]> lines = jdbc.query("""
                    SELECT x.sale_line_no, x.quantity, l.product_id, l.batch_id FROM ecis_sale_return_line x JOIN ecis_sale_line l ON l.sale_id = ? AND l.line_no = x.sale_line_no WHERE x.return_id = ? ORDER BY x.line_no
                    """, (rs, i) -> new Object[] {rs.getInt(1), rs.getBigDecimal(2), rs.getLong(3), rs.getLong(4)}, r.saleId(), id);
            for (Object[] l : lines) {
                String batchNo = (Long) l[3] == 0 ? null : jdbc.queryForObject("SELECT batch_no FROM ecis_inventory_batch WHERE id = ?", String.class, l[3]);
                stock.post(new Move("RETURN", (Long) l[2], warehouse, batchNo, null, (BigDecimal) l[1], null, "RETURN", r.returnNo(), "Return against " + r.saleNo()), auth.getName());
                jdbc.update("UPDATE ecis_sale_line SET qty_returned = qty_returned + ? WHERE sale_id = ? AND line_no = ?", l[1], r.saleId(), l[0]);
            }
            jdbc.update("UPDATE ecis_sale SET returned_total = returned_total + ? WHERE id = ?", r.refund(), r.saleId());
        }
        jdbc.update("UPDATE ecis_sale_return SET status = ?, decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ? AND status = 'PENDING'", approve ? "APPROVED" : "REJECTED", auth.getName(), Timestamp.valueOf(support.now()), note, id);
        support.audit(auth, "pos.return." + (approve ? "approve" : "reject"), "ecis_sale_return", r.returnNo(), note == null ? "refund " + r.refund().toPlainString() : note);
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
