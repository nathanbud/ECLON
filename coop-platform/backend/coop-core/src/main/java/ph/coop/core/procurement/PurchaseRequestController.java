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
 * Purchase requests (guide phase 12): a purchaser submits one, a different approver approves or rejects it, and an approved request can be ordered.
 * Nobody decides their own request, whatever their role.
 */
@RestController
@RequestMapping("/api/procurement/requests")
@PreAuthorize("hasAnyRole('PURCHASER','PURCHASE_APPROVER','ACCOUNTANT','ADMIN')")
class PurchaseRequestController {

    static final String WORKFLOW = "PURCHASE_REQUEST";

    record LineIn(String description, String unit, BigDecimal quantity, BigDecimal estCost) {}

    record NewRequest(String purpose, LocalDate neededBy, List<LineIn> lines) {}

    record Decision(String note) {}

    record Summary(long id, String requestNo, LocalDate requestDate, LocalDate neededBy, String purpose, String status, String requestedBy, String decidedBy, String decisionNote, BigDecimal total, String waiting) {
        /** The same request with the extra approval it is waiting for (null when none). */
        Summary withWaiting(String w) {
            return new Summary(id, requestNo, requestDate, neededBy, purpose, status, requestedBy, decidedBy, decisionNote, total, w);
        }
    }

    record Detail(Summary summary, List<LineIn> lines) {}

    private static final String SUMMARY = """
            SELECT r.id, r.request_no, r.request_date, r.needed_by, r.purpose, r.status, r.requested_by, r.decided_by, r.decision_note,
                   (SELECT COALESCE(SUM(ROUND(l.quantity * l.est_cost, 2)), 0) FROM ecis_purchase_request_line l WHERE l.request_id = r.id)
            FROM ecis_purchase_request r
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final ProcurementSupport support;
    private final Clock clock;
    private final WorkflowEngine workflow;

    PurchaseRequestController(JdbcTemplate jdbc, Db db, ProcurementSupport support, Clock clock, WorkflowEngine workflow) {
        this.workflow = workflow;
        this.jdbc = jdbc;
        this.db = db;
        this.support = support;
        this.clock = clock;
    }

    private static Summary map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date needed = rs.getDate(4);
        return new Summary(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), needed == null ? null : needed.toLocalDate(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getBigDecimal(10), null);
    }

    private Summary withWorkflow(Summary s) {
        return s.status().equals("SUBMITTED") ? s.withWaiting(workflow.waitingFor(WORKFLOW, String.valueOf(s.id()))) : s;
    }

    @GetMapping
    List<Summary> list(@RequestParam(required = false) String status) {
        return jdbc.query(SUMMARY + " WHERE r.branch_code = ? AND (CAST(? AS CHAR) IS NULL OR r.status = ?) ORDER BY r.id DESC LIMIT 300", PurchaseRequestController::map, ProcurementSupport.BRANCH, status, status).stream().map(this::withWorkflow).toList();
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE r.branch_code = ? AND r.id = ?", PurchaseRequestController::map, ProcurementSupport.BRANCH, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such purchase request.");
        List<LineIn> lines = jdbc.query("SELECT description, unit, quantity, est_cost FROM ecis_purchase_request_line WHERE request_id = ? ORDER BY line_no",
            (rs, i) -> new LineIn(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)), id);
        return new Detail(withWorkflow(s.get(0)), lines);
    }

    @PostMapping
    @Transactional
    Summary create(@RequestBody NewRequest in, Authentication auth) {
        support.requirePurchaser(auth);
        LocalDate today = LocalDate.now(clock);
        List<Line> lines = in.lines() == null ? List.of() : in.lines().stream().map((l) -> new Line(l.description(), l.unit(), l.quantity(), l.estCost())).toList();
        List<String> problems = ProcurementRules.requestProblems(in.purpose(), today, in.neededBy(), lines);
        if (!problems.isEmpty()) throw ProcurementSupport.bad(problems);
        String no = support.nextNumber("ecis_purchase_request", "request_no", "PR", today.getYear());
        long id = db.insertReturningId("""
                INSERT INTO ecis_purchase_request (branch_code, request_no, request_date, needed_by, purpose, status, requested_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?, ?)
                """, "id", ProcurementSupport.BRANCH, no, today, in.neededBy(), in.purpose().trim(), auth.getName(), Timestamp.valueOf(support.now()));
        int n = 0;
        for (Line l : lines) jdbc.update("INSERT INTO ecis_purchase_request_line (request_id, line_no, description, unit, quantity, est_cost) VALUES (?, ?, ?, ?, ?, ?)",
            id, ++n, l.description().trim(), l.unit().trim(), l.quantity(), l.price());
        support.audit(auth, "procurement.request.create", "ecis_purchase_request", no, lines.size() + " lines, " + ProcurementRules.total(lines) + ": " + in.purpose().trim());
        return get(id).summary();
    }

    private Summary decide(long id, String to, Decision in, Authentication auth) {
        support.requireApprover(auth);
        List<Summary> found = jdbc.query(SUMMARY + " WHERE r.branch_code = ? AND r.id = ? FOR UPDATE", PurchaseRequestController::map, ProcurementSupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such purchase request.");
        Summary r = found.get(0);
        if (!r.status().equals("SUBMITTED")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This request is already " + r.status().toLowerCase() + ".");
        String own = ProcurementRules.approverProblem(r.requestedBy(), auth.getName(), "request");
        if (own != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, own);
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (to.equals("REJECTED") && note == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the request is rejected.");
        if (note != null && note.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note is too long (255 characters at most).");
        if (to.equals("APPROVED")) {
            // the usual approver's approval may be only the first: a workflow can ask for more when the estimate reaches its limits
            Gate gate = workflow.gate(WORKFLOW, String.valueOf(id), r.total(), r.requestedBy(), auth.getName());
            if (!gate.done()) {
                support.audit(auth, "procurement.request.approve.first", "ecis_purchase_request", r.requestNo(), "first approval of " + r.total() + "; waiting for " + gate.waitingFor());
                return get(id).summary();
            }
        } else {
            workflow.cancel(WORKFLOW, String.valueOf(id), auth.getName(), note);
        }
        jdbc.update("UPDATE ecis_purchase_request SET status = ?, decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ? AND status = 'SUBMITTED'", to, auth.getName(), Timestamp.valueOf(support.now()), note, id);
        support.audit(auth, "procurement.request." + to.toLowerCase(), "ecis_purchase_request", r.requestNo(), note == null ? to : to + ": " + note);
        return get(id).summary();
    }

    @PostMapping("/{id}/approve")
    @Transactional
    Summary approve(@PathVariable long id, @RequestBody(required = false) Decision in, Authentication auth) {
        return decide(id, "APPROVED", in, auth);
    }

    @PostMapping("/{id}/reject")
    @Transactional
    Summary reject(@PathVariable long id, @RequestBody Decision in, Authentication auth) {
        return decide(id, "REJECTED", in, auth);
    }

    /** The person who made a request, or an administrator, withdraws it until it has been ordered. */
    @PostMapping("/{id}/cancel")
    @Transactional
    Summary cancel(@PathVariable long id, @RequestBody Decision in, Authentication auth) {
        List<Summary> found = jdbc.query(SUMMARY + " WHERE r.branch_code = ? AND r.id = ? FOR UPDATE", PurchaseRequestController::map, ProcurementSupport.BRANCH, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such purchase request.");
        Summary r = found.get(0);
        if (!support.isAdmin(auth) && !r.requestedBy().equalsIgnoreCase(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who made the request can withdraw it.");
        if (!r.status().equals("SUBMITTED") && !r.status().equals("APPROVED")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This request is " + r.status().toLowerCase() + " and cannot be withdrawn.");
        if (in == null || in.note() == null || in.note().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the request is withdrawn.");
        workflow.cancel(WORKFLOW, String.valueOf(id), auth.getName(), in.note().trim());
        jdbc.update("UPDATE ecis_purchase_request SET status = 'CANCELLED', decision_note = ? WHERE id = ?", in.note().trim(), id);
        support.audit(auth, "procurement.request.cancel", "ecis_purchase_request", r.requestNo(), in.note().trim());
        return get(id).summary();
    }
}
