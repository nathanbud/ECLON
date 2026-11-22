package ph.coop.core.inventory;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import ph.coop.core.workflow.WorkflowEngine;
import ph.coop.core.workflow.WorkflowEngine.Gate;

/**
 * Stock adjustments (guide phase 13): ADJUSTMENT (either way), DAMAGE and EXPIRY (stock out). A storekeeper submits one with a reason; a different stock approver
 * approves it, which writes the ledger rows, or rejects it with a reason. Nothing changes stock until it is approved.
 */
@RestController
@RequestMapping("/api/inventory/adjustments")
@PreAuthorize("hasAnyRole('STOREKEEPER','STOCK_APPROVER','ACCOUNTANT','ADMIN')")
class StockAdjustmentController {

    static final String WORKFLOW = "STOCK_ADJUSTMENT";

    record LineIn(long productId, String batchNo, BigDecimal quantity) {}

    record NewAdjustment(long warehouseId, String type, String reason, List<LineIn> lines) {}

    record Decision(String note) {}

    record Summary(long id, String adjustNo, long warehouseId, String warehouse, String type, String reason, String status, String requestedBy, String decidedBy, String decisionNote, String waiting) {
        /** The same adjustment with the extra approval it is waiting for (null when none). */
        Summary withWaiting(String w) {
            return new Summary(id, adjustNo, warehouseId, warehouse, type, reason, status, requestedBy, decidedBy, decisionNote, w);
        }
    }

    record LineOut(long productId, String sku, String product, String batchNo, BigDecimal quantity) {}

    record Detail(Summary summary, List<LineOut> lines) {}

    private static final String SUMMARY = """
            SELECT a.id, a.adjust_no, a.warehouse_id, w.name, a.adj_type, a.reason, a.status, a.requested_by, a.decided_by, a.decision_note
            FROM ecis_stock_adjustment a JOIN ecis_warehouse w ON w.id = a.warehouse_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final InventorySupport support;
    private final StockService stock;
    private final StockAdjustmentPosting posting;
    private final WorkflowEngine workflow;

    StockAdjustmentController(JdbcTemplate jdbc, Db db, InventorySupport support, StockService stock, StockAdjustmentPosting posting, WorkflowEngine workflow) {
        this.posting = posting;
        this.workflow = workflow;
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.stock = stock;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Summary(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), null);
    }

    private Summary withWorkflow(Summary s) {
        return s.status().equals("SUBMITTED") ? s.withWaiting(workflow.waitingFor(WORKFLOW, String.valueOf(s.id()))) : s;
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) String status) {
        return jdbc.query(SUMMARY + " WHERE a.branch_code = ? AND (CAST(? AS CHAR) IS NULL OR a.status = ?) ORDER BY a.id DESC LIMIT 300", StockAdjustmentController::map, InventorySupport.BRANCH, status, status).stream().map(this::withWorkflow).toList();
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE a.branch_code = ? AND a.id = ?", StockAdjustmentController::map, InventorySupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stock adjustment.");
        List<LineOut> lines = jdbc.query("""
                SELECT l.product_id, p.sku, p.name, COALESCE(b.batch_no, ''), l.quantity FROM ecis_stock_adjustment_line l
                JOIN ecis_product p ON p.id = l.product_id LEFT JOIN ecis_inventory_batch b ON b.id = l.batch_id WHERE l.adjustment_id = ? ORDER BY l.line_no
                """, (rs, i) -> new LineOut(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5)), id);
        return new Detail(withWorkflow(s.get(0)), lines);
    }

    @PostMapping
    @Transactional
    Summary create(@RequestBody NewAdjustment in, Authentication auth) {
        support.requireKeeper(auth);
        List<String> problems = new ArrayList<>();
        if (in.type() == null || !(in.type().equals("ADJUSTMENT") || in.type().equals("DAMAGE") || in.type().equals("EXPIRY"))) problems.add("The type is ADJUSTMENT, DAMAGE or EXPIRY.");
        if (in.reason() == null || in.reason().isBlank()) problems.add("Give the reason.");
        else if (in.reason().length() > 255) problems.add("The reason is too long (255 characters at most).");
        if (in.lines() == null || in.lines().isEmpty()) problems.add("Add at least one line.");
        if (!problems.isEmpty()) throw InventorySupport.bad(problems);
        Integer wh = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_warehouse WHERE branch_code = ? AND id = ? AND active = 1", Integer.class, InventorySupport.BRANCH, in.warehouseId());
        if (wh == null || wh == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That warehouse was not found or is switched off. Choose an active warehouse from the list.");
        List<long[]> resolved = new ArrayList<>();
        int n = 0;
        for (LineIn l : in.lines()) {
            n++;
            List<String> lp = InventoryRules.adjustmentLineProblems(in.type(), l.quantity());
            if (!lp.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": " + String.join(" ", lp));
            List<Object[]> prod = jdbc.query("SELECT track_batch FROM ecis_product WHERE branch_code = ? AND id = ?", (rs, i) -> new Object[] {rs.getInt(1) == 1}, InventorySupport.BRANCH, l.productId());
            if (prod.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Line " + n + ": no such product.");
            boolean tracked = (Boolean) prod.get(0)[0];
            boolean named = l.batchNo() != null && !l.batchNo().isBlank();
            if (tracked != named) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + (tracked ? ": this product is tracked by batch, give the batch." : ": this product is not tracked by batch."));
            resolved.add(new long[] {l.productId(), named ? stock.batchId(l.productId(), l.batchNo(), null, false) : 0});
        }
        String no = support.nextNumber("ecis_stock_adjustment", "adjust_no", "SA", support.today().getYear());
        long id = db.insertReturningId("""
                INSERT INTO ecis_stock_adjustment (branch_code, adjust_no, warehouse_id, adj_type, reason, status, requested_by, created_at) VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?, ?)
                """, "id", InventorySupport.BRANCH, no, in.warehouseId(), in.type(), in.reason().trim(), auth.getName(), Timestamp.valueOf(support.now()));
        n = 0;
        for (LineIn l : in.lines()) {
            jdbc.update("INSERT INTO ecis_stock_adjustment_line (adjustment_id, line_no, product_id, batch_id, quantity) VALUES (?, ?, ?, ?, ?)", id, n + 1, l.productId(), resolved.get(n)[1], l.quantity());
            n++;
        }
        support.audit(auth, "inventory.adjustment.create", "ecis_stock_adjustment", no, in.type() + ", " + in.lines().size() + " lines: " + in.reason().trim());
        return get(id).summary();
    }

    private Summary decide(long id, boolean approve, Decision in, Authentication auth) {
        support.requireApprover(auth);
        List<Summary> found = jdbc.query(SUMMARY + " WHERE a.branch_code = ? AND a.id = ? FOR UPDATE", StockAdjustmentController::map, InventorySupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stock adjustment.");
        Summary a = found.get(0);
        if (!a.status().equals("SUBMITTED")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This adjustment is already " + a.status().toLowerCase() + ".");
        String own = InventorySupport.own(a.requestedBy(), auth.getName(), "adjustment");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, own);
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (!approve && note == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the adjustment is rejected.");
        if (note != null && note.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note is too long: it can have at most 255 characters. Shorten it and try again.");
        if (approve) {
            // the usual approver's approval may be only the first: a workflow can ask for more when the adjustment's value reaches its limits
            Gate gate = workflow.gate(WORKFLOW, String.valueOf(id), posting.value(id), a.requestedBy(), auth.getName());
            if (!gate.done()) {
                support.audit(auth, "inventory.adjustment.approve.first", "ecis_stock_adjustment", a.adjustNo(), "first approval; waiting for " + gate.waitingFor());
                return get(id).summary();
            }
            posting.complete(id, auth.getName(), note);
        } else {
            workflow.cancel(WORKFLOW, String.valueOf(id), auth.getName(), note);
            posting.turnDown(id, auth.getName(), note);
        }
        support.audit(auth, "inventory.adjustment." + (approve ? "approve" : "reject"), "ecis_stock_adjustment", a.adjustNo(), note == null ? a.type() : note);
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
