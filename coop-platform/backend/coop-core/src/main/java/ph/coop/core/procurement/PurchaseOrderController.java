package ph.coop.core.procurement;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
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
import ph.coop.core.procurement.ProcurementRules.Line;
import ph.coop.core.workflow.WorkflowEngine;
import ph.coop.core.workflow.WorkflowEngine.Gate;

/**
 * Purchase orders (guide phase 12). A purchaser writes one to a supplier, optionally answering an approved purchase request; a different approver approves it.
 * Approved orders wait for receiving (the next stage). Cancelling an order that has received nothing frees its request to be ordered again.
 */
@RestController
@RequestMapping("/api/procurement/orders")
@PreAuthorize("hasAnyRole('RECEIVER','PURCHASER','PURCHASE_APPROVER','ACCOUNTANT','ADMIN')")
class PurchaseOrderController {

    static final String WORKFLOW = "PURCHASE_ORDER";

    /** {@code productId} is optional: a line that names a product is stocked when received (and takes that product's unit); a free-text line is not. */
    record LineIn(String description, String unit, BigDecimal quantity, BigDecimal unitCost, Long productId) {
        LineIn(String description, String unit, BigDecimal quantity, BigDecimal unitCost) {
            this(description, unit, quantity, unitCost, null);
        }
    }

    record NewOrder(long supplierId, Long requestId, LocalDate expectedDate, String remarks, List<LineIn> lines) {}

    record Reason(String note) {}

    record Summary(long id, String orderNo, long supplierId, String supplier, Long requestId, String requestNo, LocalDate orderDate, LocalDate expectedDate, int termsDays, String remarks,
                   String status, BigDecimal total, String createdBy, String approvedBy, String waiting) {
        /** The same order with the extra approval it is waiting for (null when none). */
        Summary withWaiting(String w) {
            return new Summary(id, orderNo, supplierId, supplier, requestId, requestNo, orderDate, expectedDate, termsDays, remarks, status, total, createdBy, approvedBy, w);
        }
    }

    record LineOut(String description, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal received, Long productId, boolean trackBatch, boolean trackExpiry) {}

    record Detail(Summary summary, List<LineOut> lines) {}

    private static final String SUMMARY = """
            SELECT o.id, o.order_no, o.supplier_id, s.name, o.request_id, r.request_no, o.order_date, o.expected_date, o.terms_days, o.remarks, o.status, o.total, o.created_by, o.approved_by
            FROM ecis_purchase_order o
            JOIN ecis_supplier s ON s.id = o.supplier_id
            LEFT JOIN ecis_purchase_request r ON r.id = o.request_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final ProcurementSupport support;
    private final Clock clock;
    private final WorkflowEngine workflow;

    PurchaseOrderController(JdbcTemplate jdbc, Db db, ProcurementSupport support, Clock clock, WorkflowEngine workflow) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.clock = clock;
        this.workflow = workflow;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date expected = rs.getDate(8);
        long req = rs.getLong(5);
        Long requestId = rs.wasNull() ? null : req;
        return new Summary(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), requestId, rs.getString(6), rs.getDate(7).toLocalDate(), expected == null ? null : expected.toLocalDate(),
            rs.getInt(9), rs.getString(10), rs.getString(11), rs.getBigDecimal(12), rs.getString(13), rs.getString(14), null);
    }

    /** Draft orders carry the extra approval they wait for, if a workflow is holding them. */
    private Summary withWorkflow(Summary s) {
        return s.status().equals("DRAFT") ? s.withWaiting(workflow.waitingFor(WORKFLOW, String.valueOf(s.id()))) : s;
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) String status) {
        return jdbc.query(SUMMARY + " WHERE o.branch_code = ? AND (? IS NULL OR o.status = ?) ORDER BY o.id DESC LIMIT 300", PurchaseOrderController::map, ProcurementSupport.BRANCH, status, status).stream().map(this::withWorkflow).toList();
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE o.branch_code = ? AND o.id = ?", PurchaseOrderController::map, ProcurementSupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such purchase order.");
        List<LineOut> lines = jdbc.query("""
                SELECT l.description, l.unit, l.quantity, l.unit_cost, l.qty_received, l.product_id, COALESCE(p.track_batch, 0), COALESCE(p.track_expiry, 0)
                FROM ecis_purchase_order_line l LEFT JOIN ecis_product p ON p.id = l.product_id WHERE l.order_id = ? ORDER BY l.line_no
                """, (rs, i) -> {
                long product = rs.getLong(6);
                return new LineOut(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.wasNull() ? null : product, rs.getInt(7) == 1, rs.getInt(8) == 1);
            }, id);
        return new Detail(withWorkflow(s.get(0)), lines);
    }

    @PostMapping
    @Transactional
    Summary create(@RequestBody NewOrder in, Authentication auth) {
        support.requirePurchaser(auth);
        LocalDate today = LocalDate.now(clock);
        List<Object[]> sup = jdbc.query("SELECT active, terms_days FROM ecis_supplier WHERE branch_code = ? AND id = ? FOR UPDATE", (rs, i) -> new Object[] {rs.getInt(1) == 1, rs.getInt(2)}, ProcurementSupport.BRANCH, in.supplierId());
        if (sup.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such supplier.");
        String requestStatus = null;
        if (in.requestId() != null) {
            List<String> st = jdbc.queryForList("SELECT status FROM ecis_purchase_request WHERE branch_code = ? AND id = ? FOR UPDATE", String.class, ProcurementSupport.BRANCH, in.requestId());
            if (st.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such purchase request.");
            requestStatus = st.get(0);
        }
        // a line that names a product takes that product's unit
        List<String> units = new java.util.ArrayList<>();
        int at = 0;
        if (in.lines() != null) {
            for (LineIn l : in.lines()) {
                at++;
                String unit = l.unit();
                if (l.productId() != null) {
                    List<String> p = jdbc.queryForList("SELECT unit FROM ecis_product WHERE branch_code = ? AND id = ? AND active = 1", String.class, ProcurementSupport.BRANCH, l.productId());
                    if (p.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + at + ": no such active product.");
                    unit = p.get(0);
                }
                units.add(unit);
            }
        }
        List<Line> lines = new java.util.ArrayList<>();
        for (int i = 0; in.lines() != null && i < in.lines().size(); i++) {
            LineIn l = in.lines().get(i);
            lines.add(new Line(l.description(), units.get(i), l.quantity(), l.unitCost()));
        }
        List<String> problems = ProcurementRules.orderProblems((Boolean) sup.get(0)[0], requestStatus, today, in.expectedDate(), lines);
        if (!problems.isEmpty()) throw ProcurementSupport.bad(problems);
        BigDecimal total = ProcurementRules.total(lines);
        String no = support.nextNumber("ecis_purchase_order", "order_no", "PO", today.getYear());
        long id = db.insertReturningId("""
                INSERT INTO ecis_purchase_order (branch_code, order_no, supplier_id, request_id, order_date, expected_date, terms_days, remarks, status, total, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, "id", ProcurementSupport.BRANCH, no, in.supplierId(), in.requestId(), today, in.expectedDate(), sup.get(0)[1],
            in.remarks() == null || in.remarks().isBlank() ? null : in.remarks().trim(), total, auth.getName(), Timestamp.valueOf(support.now()));
        int n = 0;
        for (Line l : lines) {
            jdbc.update("INSERT INTO ecis_purchase_order_line (order_id, line_no, product_id, description, unit, quantity, unit_cost) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, n + 1, in.lines().get(n).productId(), l.description().trim(), l.unit().trim(), l.quantity(), l.price());
            n++;
        }
        if (in.requestId() != null) jdbc.update("UPDATE ecis_purchase_request SET status = 'ORDERED' WHERE id = ?", in.requestId());
        support.audit(auth, "procurement.order.create", "ecis_purchase_order", no, "supplier " + in.supplierId() + ", " + lines.size() + " lines, " + total + (in.requestId() == null ? "" : ", request " + in.requestId()));
        return get(id).summary();
    }

    private Summary locked(long id) {
        List<Summary> found = jdbc.query(SUMMARY + " WHERE o.branch_code = ? AND o.id = ? FOR UPDATE", PurchaseOrderController::map, ProcurementSupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such purchase order.");
        return found.get(0);
    }

    @PostMapping("/{id}/approve")
    @Transactional
    Summary approve(@PathVariable long id, Authentication auth) {
        support.requireApprover(auth);
        Summary o = locked(id);
        if (!o.status().equals("DRAFT")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already " + o.status().toLowerCase() + ".");
        String own = ProcurementRules.approverProblem(o.createdBy(), auth.getName(), "order");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, own);
        // the usual approver's approval may be only the first: a workflow can ask for more when the total reaches its limits
        Gate gate = workflow.gate(WORKFLOW, String.valueOf(id), o.total(), o.createdBy(), auth.getName());
        if (!gate.done()) {
            support.audit(auth, "procurement.order.approve.first", "ecis_purchase_order", o.orderNo(), "first approval of " + o.total() + "; waiting for " + gate.waitingFor());
            return get(id).summary();
        }
        jdbc.update("UPDATE ecis_purchase_order SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ? AND status = 'DRAFT'", auth.getName(), Timestamp.valueOf(support.now()), id);
        support.audit(auth, "procurement.order.approve", "ecis_purchase_order", o.orderNo(), "approved " + o.total());
        return get(id).summary();
    }

    /** Cancels an order that has received nothing; the request it answered can be ordered again. */
    @PostMapping("/{id}/cancel")
    @Transactional
    Summary cancel(@PathVariable long id, @RequestBody Reason in, Authentication auth) {
        support.requirePurchaser(auth);
        Summary o = locked(id);
        if (!o.status().equals("DRAFT") && !o.status().equals("APPROVED")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is " + o.status().toLowerCase() + " and cannot be cancelled.");
        if (in == null || in.note() == null || in.note().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the order is cancelled.");
        BigDecimal received = jdbc.queryForObject("SELECT COALESCE(SUM(qty_received), 0) FROM ecis_purchase_order_line WHERE order_id = ?", BigDecimal.class, id);
        if (received != null && received.signum() > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Goods were already received on this order, so it cannot be cancelled.");
        jdbc.update("UPDATE ecis_purchase_order SET status = 'CANCELLED', remarks = ? WHERE id = ?", (o.remarks() == null ? "" : o.remarks() + " | ") + "Cancelled: " + in.note().trim(), id);
        if (o.requestId() != null) {
            Integer others = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_purchase_order WHERE request_id = ? AND status <> 'CANCELLED' AND id <> ?", Integer.class, o.requestId(), id);
            if (others != null && others == 0) jdbc.update("UPDATE ecis_purchase_request SET status = 'APPROVED' WHERE id = ? AND status = 'ORDERED'", o.requestId());
        }
        workflow.cancel(WORKFLOW, String.valueOf(id), auth.getName(), in.note().trim());
        support.audit(auth, "procurement.order.cancel", "ecis_purchase_order", o.orderNo(), in.note().trim());
        return get(id).summary();
    }
}
