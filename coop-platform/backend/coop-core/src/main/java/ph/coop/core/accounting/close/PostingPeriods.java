package ph.coop.core.accounting.close;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Whether the ledger accepts a posting dated on a given day: not in a year whose books are closed (it has a Closing Entry, see {@link ClosedBooks}),
 * and not in an accounting period that is soft-closed, closed or locked. A day that no period covers is accepted, because periods are set up by the
 * accountants and need not cover every day; journals and memos are stricter and need an open period. Called by every teller and front-office
 * posting just before it writes its header, so a refusal writes nothing. Static, like {@code AuditLog.write}, so the many writers need no new wiring.
 */
public final class PostingPeriods {

    private PostingPeriods() {}

    /** Why nothing may be posted dated {@code date}, or null when it may. */
    public static String problem(JdbcTemplate jdbc, int branch, LocalDate date) {
        Integer closed = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = 1 AND TR_CODE = ?",
                Integer.class, branch, date.getYear(), ClosedBooks.CLOSING_ENTRY);
        if (closed != null && closed > 0) {
            return "The books for " + date.getYear() + " are closed (year-end close), so nothing can be posted dated " + date + ".";
        }
        List<Map<String, Object>> p = jdbc.queryForList(
                "SELECT name, status FROM ecis_accounting_period WHERE branch_code = ? AND ? BETWEEN start_date AND end_date AND status <> 'OPEN' ORDER BY start_date",
                branch, date);
        if (p.isEmpty()) return null;
        String status = String.valueOf(p.get(0).get("status"));
        String words = status.toLowerCase().replace('_', ' ');
        return "The accounting period " + p.get(0).get("name") + " is " + words + ", so nothing can be posted dated " + date + "."
                + ("SOFT_CLOSED".equals(status) ? " An accountant can reopen it under Accounting > Periods." : "");
    }

    /** Refuses (409) a posting dated in a closed year or a period that is not open. */
    public static void requireOpen(JdbcTemplate jdbc, int branch, LocalDate date) {
        String why = problem(jdbc, branch, date);
        if (why != null) throw new ResponseStatusException(HttpStatus.CONFLICT, why);
    }
}
