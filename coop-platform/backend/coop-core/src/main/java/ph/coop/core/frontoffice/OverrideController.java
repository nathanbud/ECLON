package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Supervisor override: when a draft goes over the person's limit, they ask a supervisor (the head cashier or an administrator) to
 * approve it. The person who asks can never approve their own request, and every decision is written to the audit log.
 * Nothing is posted here; the approval is what a later posting step will require.
 */
@RestController
@RequestMapping("/api/front-office/overrides")
class OverrideController {

    record Override(long id, Long draftId, String kind, String description, BigDecimal amount, BigDecimal limitAmount, String requestedBy,
                    String requestedAt, String status, String decidedBy, String note) {}

    record Request(Long draftId, String kind, String description, BigDecimal amount, BigDecimal limitAmount) {}

    record Decision(boolean approve, String note) {}

    private record Who(int id, int branch, boolean head, String username) {}

    private static final String SELECT = """
            SELECT o.id, o.draft_id, o.kind, o.description, o.amount, o.limit_amount, r.AccessUserName, o.requested_at, o.status, d.AccessUserName, o.note
            FROM ecis_fo_override o
            LEFT JOIN access r ON r.AccessID = o.requested_by
            LEFT JOIN access d ON d.AccessID = o.decided_by
            """;

    private final JdbcTemplate jdbc;
    private final FrontOfficeAccess access;

    OverrideController(JdbcTemplate jdbc, FrontOfficeAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    private Who who(Authentication auth) {
        access.of(auth);
        List<Who> rows = jdbc.query("SELECT AccessID, AccessBR_CODE, COALESCE(AccessIsHeadCashier, 0), AccessUserName FROM access WHERE AccessUserName = ? LIMIT 1",
            (rs, i) -> new Who(rs.getInt(1), rs.getInt(2), rs.getInt(3) == 1, rs.getString(4)), auth.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account cannot ask for or give overrides.");
        return rows.get(0);
    }

    private static boolean supervisor(Who w, Authentication auth) {
        return w.head() || auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private static Override map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long draft = rs.getLong(2);
        Long draftId = rs.wasNull() ? null : draft;
        return new Override(rs.getLong(1), draftId, rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7),
            rs.getTimestamp(8).toLocalDateTime().format(DateTimeFormatter.ofPattern("MMM d HH:mm")), rs.getString(9), rs.getString(10), rs.getString(11));
    }

    private ResponseStatusException notSetUp() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Overrides are not set up yet. An administrator has to run database/migrations/V007_drawer_controls.sql.");
    }

    /** Ask a supervisor to approve something over the person's limit. */
    @PostMapping
    Override ask(@RequestBody Request req, Authentication auth) {
        Who w = who(auth);
        if (req.amount() == null || req.amount().signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no amount to approve.");
        String kind = req.kind() == null || req.kind().isBlank() ? "OVER_LIMIT" : req.kind().trim().toUpperCase();
        String text = req.description() == null ? "" : req.description().trim();
        if (text.length() < 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what needs approval.");
        try {
            // One open request per draft: asking again returns the one already waiting.
            if (req.draftId() != null) {
                List<Override> open = jdbc.query(SELECT + " WHERE o.draft_id = ? AND o.requested_by = ? AND o.status = 'PENDING' ORDER BY o.id DESC LIMIT 1", (rs, i) -> map(rs), req.draftId(), w.id());
                if (!open.isEmpty()) return open.get(0);
            }
            jdbc.update("INSERT INTO ecis_fo_override (branch_code, draft_id, kind, description, amount, limit_amount, requested_by, requested_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                w.branch(), req.draftId(), kind, text.substring(0, Math.min(text.length(), 250)), req.amount(), req.limitAmount(), w.id(), Timestamp.valueOf(LocalDateTime.now()));
            Long id = jdbc.queryForObject("SELECT MAX(id) FROM ecis_fo_override WHERE requested_by = ?", Long.class, w.id());
            return jdbc.queryForObject(SELECT + " WHERE o.id = ?", (rs, i) -> map(rs), id);
        } catch (org.springframework.dao.DataAccessException e) {
            if (e instanceof org.springframework.dao.EmptyResultDataAccessException) throw e;
            throw notSetUp();
        }
    }

    /** The latest request made for a draft by the signed-in person, so the draft can show whether it was approved. */
    @GetMapping("/for-draft")
    Override forDraft(@RequestParam long draftId, Authentication auth) {
        Who w = who(auth);
        try {
            List<Override> rows = jdbc.query(SELECT + " WHERE o.draft_id = ? AND o.requested_by = ? ORDER BY o.id DESC LIMIT 1", (rs, i) -> map(rs), draftId, w.id());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (org.springframework.dao.DataAccessException e) {
            return null;
        }
    }

    /** Requests waiting for a decision. Supervisors only. */
    @GetMapping("/pending")
    List<Override> pending(Authentication auth) {
        Who w = who(auth);
        if (!supervisor(w, auth)) return List.of();
        try {
            return jdbc.query(SELECT + " WHERE o.branch_code = ? AND o.status = 'PENDING' ORDER BY o.id", (rs, i) -> map(rs), w.branch());
        } catch (org.springframework.dao.DataAccessException e) {
            return List.of();
        }
    }

    /** Approve or reject. A supervisor cannot decide their own request. */
    @PostMapping("/{id}/decide")
    Override decide(@PathVariable long id, @RequestBody Decision d, Authentication auth) {
        Who w = who(auth);
        if (!supervisor(w, auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier or an administrator can decide this.");
        List<Object[]> row = jdbc.query("SELECT requested_by, status, kind, amount FROM ecis_fo_override WHERE id = ? AND branch_code = ?",
            (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)}, id, w.branch());
        if (row.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such request.");
        if (((Integer) row.get(0)[0]) == w.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot decide your own request. Ask another supervisor.");
        String note = d.note() == null ? null : d.note().trim();
        if (!d.approve() && (note == null || note.length() < 3)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason when rejecting.");
        int n = jdbc.update("UPDATE ecis_fo_override SET status = ?, decided_by = ?, decided_at = ?, note = ? WHERE id = ? AND status = 'PENDING'",
            d.approve() ? "APPROVED" : "REJECTED", w.id(), Timestamp.valueOf(LocalDateTime.now()), note, id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This request has already been decided.");
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value, reason) VALUES (NOW(), ?, ?, 'fo_override', ?, ?, ?)",
            w.username(), d.approve() ? "fo.override.approve" : "fo.override.reject", String.valueOf(id), row.get(0)[2] + " " + row.get(0)[3], note);
        return jdbc.queryForObject(SELECT + " WHERE o.id = ?", (rs, i) -> map(rs), id);
    }
}
