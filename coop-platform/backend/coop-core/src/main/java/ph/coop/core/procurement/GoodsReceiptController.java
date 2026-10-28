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
import ph.coop.core.procurement.ProcurementRules.Open;
import ph.coop.core.procurement.ProcurementRules.Take;

/**
 * Goods receipts (guide phase 12, stage 2). A receiver records what came in against an approved purchase order, in whole or in part; the order moves from APPROVED
 * to PARTIAL to RECEIVED. The person who made the order cannot receive its goods. A receipt is never edited: a different approver reverses it with a reason, which
 * takes the quantities back off the order, and a correct receipt is made.
 */
@RestController
@RequestMapping("/api/procurement/receipts")
@PreAuthorize("hasAnyRole('RECEIVER','PURCHASER','PURCHASE_APPROVER','ACCOUNTANT','ADMIN')")
class GoodsReceiptController {

    record TakeIn(int lineNo, BigDecimal quantity) {}

    record NewReceipt(long orderId, String deliveryNote, String remarks, List<TakeIn> lines) {}

    record Reason(String note) {}

    record Summary(long id, String receiptNo, long orderId, String orderNo, String supplier, LocalDate receiptDate, String deliveryNote, String remarks, String status, String receivedBy,
                   String reversedBy, String reverseNote) {}

    record LineOut(int lineNo, int orderLineNo, String description, String unit, BigDecimal quantity) {}

    record Detail(Summary summary, List<LineOut> lines) {}

    private static final String SUMMARY = """
            SELECT g.id, g.receipt_no, g.order_id, o.order_no, s.name, g.receipt_date, g.delivery_note, g.remarks, g.status, g.received_by, g.reversed_by, g.reverse_note
            FROM ecis_goods_receipt g
            JOIN ecis_purchase_order o ON o.id = g.order_id
            JOIN ecis_supplier s ON s.id = o.supplier_id
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final ProcurementSupport support;
    private final Clock clock;

    GoodsReceiptController(JdbcTemplate jdbc, Db db, ProcurementSupport support, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.clock = clock;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Summary(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getDate(6).toLocalDate(), rs.getString(7), rs.getString(8), rs.getString(9),
            rs.getString(10), rs.getString(11), rs.getString(12));
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) Long orderId) {
        return jdbc.query(SUMMARY + " WHERE g.branch_code = ? AND (? IS NULL OR g.order_id = ?) ORDER BY g.id DESC LIMIT 300", GoodsReceiptController::map, ProcurementSupport.BRANCH, orderId, orderId);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE g.branch_code = ? AND g.id = ?", GoodsReceiptController::map, ProcurementSupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such goods receipt.");
        List<LineOut> lines = jdbc.query("""
                SELECT l.line_no, l.order_line_no, p.description, p.unit, l.quantity FROM ecis_goods_receipt_line l
                JOIN ecis_goods_receipt g ON g.id = l.receipt_id
                JOIN ecis_purchase_order_line p ON p.order_id = g.order_id AND p.line_no = l.order_line_no
                WHERE l.receipt_id = ? ORDER BY l.line_no
                """, (rs, i) -> new LineOut(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5)), id);
        return new Detail(s.get(0), lines);
    }

    /** The order's lines with what has come in, locked for the update. */
    private List<Open> open(long orderId) {
        return jdbc.query("SELECT line_no, quantity, qty_received FROM ecis_purchase_order_line WHERE order_id = ? ORDER BY line_no FOR UPDATE",
            (rs, i) -> new Open(rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), orderId);
    }

    private void refreshStatus(long orderId) {
        String status = ProcurementRules.statusAfter(open(orderId));
        jdbc.update("UPDATE ecis_purchase_order SET status = ? WHERE id = ?", status, orderId);
    }

    @PostMapping
    @Transactional
    Summary create(@RequestBody NewReceipt in, Authentication auth) {
        support.requireReceiver(auth);
        List<Object[]> order = jdbc.query("SELECT status, created_by, order_no FROM ecis_purchase_order WHERE branch_code = ? AND id = ? FOR UPDATE",
            (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3)}, ProcurementSupport.BRANCH, in.orderId());
        if (order.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such purchase order.");
        String own = ProcurementRules.approverProblem((String) order.get(0)[1], auth.getName(), "order");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You made this order, so someone else must receive its goods.");
        List<Open> lines = open(in.orderId());
        List<Take> takes = in.lines() == null ? List.of() : in.lines().stream().map((t) -> new Take(t.lineNo(), t.quantity())).toList();
        List<String> problems = ProcurementRules.receiptProblems((String) order.get(0)[0], lines, takes);
        if (in.deliveryNote() != null && in.deliveryNote().length() > 40) problems.add("The delivery receipt number is too long (40 characters at most).");
        if (!problems.isEmpty()) throw ProcurementSupport.bad(problems);
        LocalDate today = LocalDate.now(clock);
        String no = support.nextNumber("ecis_goods_receipt", "receipt_no", "GR", today.getYear());
        long id = db.insertReturningId("""
                INSERT INTO ecis_goods_receipt (branch_code, receipt_no, order_id, receipt_date, delivery_note, remarks, status, received_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?)
                """, "id", ProcurementSupport.BRANCH, no, in.orderId(), today, blank(in.deliveryNote()), blank(in.remarks()), auth.getName(), Timestamp.valueOf(support.now()));
        int n = 0;
        for (Take t : takes) {
            jdbc.update("INSERT INTO ecis_goods_receipt_line (receipt_id, line_no, order_line_no, quantity) VALUES (?, ?, ?, ?)", id, ++n, t.lineNo(), t.quantity());
            jdbc.update("UPDATE ecis_purchase_order_line SET qty_received = qty_received + ? WHERE order_id = ? AND line_no = ?", t.quantity(), in.orderId(), t.lineNo());
        }
        refreshStatus(in.orderId());
        support.audit(auth, "procurement.receipt.create", "ecis_goods_receipt", no, "order " + order.get(0)[2] + ", " + takes.size() + " lines");
        return get(id).summary();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Takes a receipt back, with a reason, by a purchase approver (or administrator) other than the person who received. The goods count as not received again. */
    @PostMapping("/{id}/reverse")
    @Transactional
    Summary reverse(@PathVariable long id, @RequestBody Reason in, Authentication auth) {
        support.requireApprover(auth);
        List<Summary> found = jdbc.query(SUMMARY + " WHERE g.branch_code = ? AND g.id = ? FOR UPDATE", GoodsReceiptController::map, ProcurementSupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such goods receipt.");
        Summary g = found.get(0);
        if (!g.status().equals("RECEIVED")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This receipt is already reversed.");
        String own = ProcurementRules.approverProblem(g.receivedBy(), auth.getName(), "receipt");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, own);
        if (in == null || in.note() == null || in.note().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the receipt is reversed.");
        if (in.note().length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The reason is too long (255 characters at most).");
        jdbc.queryForObject("SELECT status FROM ecis_purchase_order WHERE id = ? FOR UPDATE", String.class, g.orderId());
        List<Open> lines = open(g.orderId());
        List<Object[]> taken = jdbc.query("SELECT order_line_no, quantity FROM ecis_goods_receipt_line WHERE receipt_id = ?", (rs, i) -> new Object[] {rs.getInt(1), rs.getBigDecimal(2)}, id);
        for (Object[] t : taken) {
            Open o = lines.stream().filter((l) -> l.lineNo() == (Integer) t[0]).findFirst().orElseThrow();
            if (o.received().compareTo((BigDecimal) t[1]) < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "The order no longer shows these goods as received.");
            jdbc.update("UPDATE ecis_purchase_order_line SET qty_received = qty_received - ? WHERE order_id = ? AND line_no = ?", t[1], g.orderId(), t[0]);
        }
        jdbc.update("UPDATE ecis_goods_receipt SET status = 'REVERSED', reversed_by = ?, reversed_at = ?, reverse_note = ? WHERE id = ? AND status = 'RECEIVED'", auth.getName(), Timestamp.valueOf(support.now()), in.note().trim(), id);
        refreshStatus(g.orderId());
        support.audit(auth, "procurement.receipt.reverse", "ecis_goods_receipt", g.receiptNo(), in.note().trim());
        return get(id).summary();
    }
}
