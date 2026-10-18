package ph.coop.core.accounting.journal;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

/**
 * Accounting periods (guide step 6.2): OPEN -> SOFT_CLOSED -> CLOSED -> LOCKED. A soft close can be reopened;
 * CLOSED and LOCKED cannot. Periods of one branch never overlap. Every change is audited.
 */
@RestController
@RequestMapping("/api/accounting-periods")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class PeriodController {

    record Period(long id, int branchCode, String name, LocalDate startDate, LocalDate endDate, String status) {}

    record NewPeriod(int branchCode, String name, LocalDate startDate, LocalDate endDate) {}

    record Change(String status) {}

    private final JdbcTemplate jdbc;

    PeriodController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Period map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Period(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate(), rs.getString(6));
    }

    @GetMapping
    List<Period> list(@RequestParam int branchCode) {
        return jdbc.query("SELECT id, branch_code, name, start_date, end_date, status FROM ecis_accounting_period WHERE branch_code = ? ORDER BY start_date DESC",
                PeriodController::map, branchCode);
    }

    @PostMapping
    @Transactional
    Period create(@RequestBody NewPeriod in, Authentication auth) {
        if (in.name() == null || in.name().isBlank() || in.startDate() == null || in.endDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name, start date and end date are required.");
        }
        if (in.endDate().isBefore(in.startDate())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        Integer overlap = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_accounting_period WHERE branch_code = ? AND start_date <= ? AND end_date >= ?",
                Integer.class, in.branchCode(), in.endDate(), in.startDate());
        if (overlap != null && overlap > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "That range overlaps an existing period.");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO ecis_accounting_period (branch_code, name, start_date, end_date, status, updated_by, updated_at) VALUES (?,?,?,?,?,?,?)",
                in.branchCode(), in.name(), in.startDate(), in.endDate(), "OPEN", auth.getName(), now);
        Long id = jdbc.queryForObject("SELECT id FROM ecis_accounting_period WHERE branch_code = ? AND start_date = ? AND end_date = ?",
                Long.class, in.branchCode(), in.startDate(), in.endDate());
        audit(auth, "PERIOD_CREATE", id, null, "OPEN");
        return get(id);
    }

    @PostMapping("/{id}/status")
    @Transactional
    Period change(@PathVariable long id, @RequestBody Change in, Authentication auth) {
        PeriodStatus to;
        try {
            to = PeriodStatus.valueOf(in.status());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status.");
        }
        List<String> cur = jdbc.queryForList("SELECT status FROM ecis_accounting_period WHERE id = ? FOR UPDATE", String.class, id);
        if (cur.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period.");
        PeriodStatus from = PeriodStatus.valueOf(cur.get(0));
        boolean forward = to.ordinal() == from.ordinal() + 1;
        boolean reopen = from == PeriodStatus.SOFT_CLOSED && to == PeriodStatus.OPEN;
        if (!forward && !reopen) throw new ResponseStatusException(HttpStatus.CONFLICT, "A period cannot go from " + from + " to " + to + ".");
        jdbc.update("UPDATE ecis_accounting_period SET status = ?, updated_by = ?, updated_at = ? WHERE id = ? AND status = ?",
                to.name(), auth.getName(), LocalDateTime.now(), id, from.name());
        audit(auth, "PERIOD_STATUS", id, from.name(), to.name());
        return get(id);
    }

    private Period get(long id) {
        return jdbc.query("SELECT id, branch_code, name, start_date, end_date, status FROM ecis_accounting_period WHERE id = ?", PeriodController::map, id).get(0);
    }

    private void audit(Authentication auth, String action, long id, String prev, String next) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (?,?,?,?,?,?,?,NULL)",
                LocalDateTime.now(), auth.getName(), action, "accounting_period", String.valueOf(id), prev, next);
    }
}
