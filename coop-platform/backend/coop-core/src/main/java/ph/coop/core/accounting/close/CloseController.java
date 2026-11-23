package ph.coop.core.accounting.close;

import static ph.coop.core.web.ApiErrors.bad;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;

/**
 * Closing the books (Accounting > Closing). Monthly closing is optional: every month can be reviewed and signed off, and only when the policy asks for it does
 * signing it off also close the month's accounting period. Year-end closing is mandatory and controlled: the year's checklist must be complete before the
 * closing entry can be prepared or posted (YearEndCloseController), and the year is shown as overdue once its due day has passed.
 */
@RestController
@RequestMapping("/api/accounting/close")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class CloseController {

    record Policy(boolean monthlyClose, int yearEndDueDays) {}

    record PolicyIn(Boolean monthlyClose, Integer yearEndDueDays, String reason) {}

    /** {@code overdueYear}: the oldest finished year not closed after its due day (null when none); {@code monthsToReview}: past months of this year not signed off. */
    record Status(Policy policy, Integer lastClosedYear, Integer yearToClose, LocalDate yearDue, boolean yearOverdue, List<String> monthsToReview) {}

    record Review(String scope, String period, LocalDate from, LocalDate to, List<CloseChecklist.Item> items, boolean complete, long open, String signedBy,
                  String signedAt, boolean ended, boolean monthlyClose) {}

    record TickIn(Boolean done, String note) {}

    record SignIn(String note) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final CloseReviews reviews;
    private final ClosedBooks books;

    CloseController(JdbcTemplate jdbc, Db db, CloseReviews reviews, ClosedBooks books) {
        this.jdbc = jdbc;
        this.db = db;
        this.reviews = reviews;
        this.books = books;
    }

    private void requireStored() {
        if (!reviews.stored()) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("The month-end review and the year-end checklist"));
    }

    private static String checkPeriod(String scope, String period) {
        try {
            if (CloseChecklist.MONTH.equals(scope)) return YearMonth.parse(period).toString();
            int y = Integer.parseInt(period);
            if (y < 2000 || y > 2100) throw new NumberFormatException();
            return String.valueOf(y);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw bad(CloseChecklist.MONTH.equals(scope) ? "Give the month as year and month, such as 2026-03." : "Give the year, such as 2025.");
        }
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    Status status(@RequestParam int branchCode) {
        Policy policy = new Policy(reviews.monthlyClose(), reviews.dueDays());
        LocalDate today = reviews.today();
        Integer last = books.lastClosedYear(branchCode);
        int toClose = last == null ? today.getYear() - 1 : last + 1;
        LocalDate due = CloseChecklist.dueDate(toClose, policy.yearEndDueDays());
        boolean finished = today.isAfter(LocalDate.of(toClose, 12, 31));
        List<String> months = new ArrayList<>();
        if (policy.monthlyClose() && reviews.stored()) {
            for (int m = 1; m < today.getMonthValue(); m++) {
                String p = YearMonth.of(today.getYear(), m).toString();
                if (reviews.signoff(branchCode, CloseChecklist.MONTH, p) == null) months.add(p);
            }
        }
        return new Status(policy, last, finished ? toClose : null, finished ? due : null, finished && today.isAfter(due), months);
    }

    /** The closing policy: whether monthly closing is required, and how many days after 31 December the year-end close is due. Administrators, with a reason. */
    @PutMapping("/policy")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Policy setPolicy(@RequestBody PolicyIn in, Authentication auth) {
        if (!db.tableExists("ecis_setting")) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("The closing policy"));
        if (in == null || in.reason() == null || in.reason().trim().length() < 3) throw bad("Give the reason for the change (at least 3 characters); it is kept in the audit log. Nothing was saved.");
        Policy before = new Policy(reviews.monthlyClose(), reviews.dueDays());
        if (in.monthlyClose() != null && in.monthlyClose() != before.monthlyClose()) {
            save(CloseReviews.MONTHLY_CLOSE, String.valueOf(in.monthlyClose()), auth);
            AuditLog.write(jdbc, auth.getName(), "accounting.close.policy", "setting", CloseReviews.MONTHLY_CLOSE, String.valueOf(before.monthlyClose()), String.valueOf(in.monthlyClose()), in.reason().trim());
        }
        if (in.yearEndDueDays() != null && in.yearEndDueDays() != before.yearEndDueDays()) {
            if (in.yearEndDueDays() < 1 || in.yearEndDueDays() > 365) throw bad("The year-end close is due 1 to 365 days after 31 December. Nothing was saved.");
            save(CloseReviews.YEAR_END_DUE_DAYS, String.valueOf(in.yearEndDueDays()), auth);
            AuditLog.write(jdbc, auth.getName(), "accounting.close.policy", "setting", CloseReviews.YEAR_END_DUE_DAYS, String.valueOf(before.yearEndDueDays()), String.valueOf(in.yearEndDueDays()), in.reason().trim());
        }
        return new Policy(reviews.monthlyClose(), reviews.dueDays());
    }

    private void save(String key, String value, Authentication auth) {
        db.upsert("ecis_setting", Map.of("setting_key", key), Map.of("setting_value", value, "updated_by", auth.getName(), "updated_at", java.sql.Timestamp.valueOf(LocalDateTime.now())));
    }

    @GetMapping("/{scope}/{period}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    Review review(@RequestParam int branchCode, @PathVariable String scope, @PathVariable String period) {
        String s = scope(scope);
        String p = checkPeriod(s, period);
        LocalDate[] b = CloseReviews.bounds(s, p);
        List<CloseChecklist.Item> items = reviews.items(branchCode, s, p);
        String[] signed = CloseChecklist.MONTH.equals(s) ? reviews.signoff(branchCode, s, p) : null;
        if (CloseChecklist.YEAR.equals(s) && books.isClosed(branchCode, Integer.parseInt(p))) signed = new String[] {"(year-end close posted)", null};
        return new Review(s, p, b[0], b[1], items, CloseChecklist.complete(items), CloseChecklist.open(items), signed == null ? null : signed[0],
            signed == null ? null : signed[1], reviews.today().isAfter(b[1]), reviews.monthlyClose());
    }

    private static String scope(String s) {
        String u = s == null ? "" : s.toUpperCase(java.util.Locale.ROOT);
        if (!u.equals(CloseChecklist.MONTH) && !u.equals(CloseChecklist.YEAR)) throw bad("Choose month or year.");
        return u;
    }

    /** Confirms an item (with an optional note), accepts one the application found wanting (with an explanation), or takes a tick back. Audited. */
    @PutMapping("/{scope}/{period}/items/{item}")
    @Transactional
    Review tick(@RequestParam int branchCode, @PathVariable String scope, @PathVariable String period, @PathVariable String item, @RequestBody TickIn in, Authentication auth) {
        requireStored();
        String s = scope(scope);
        String p = checkPeriod(s, period);
        if (CloseChecklist.MONTH.equals(s) && reviews.signoff(branchCode, s, p) != null) throw bad("This month is already signed off; its review can no longer change.");
        if (CloseChecklist.YEAR.equals(s) && books.isClosed(branchCode, Integer.parseInt(p))) throw bad("The books for " + p + " are closed; the checklist can no longer change.");
        boolean done = in == null || in.done() == null || in.done();
        boolean automatic = CloseChecklist.definitions(s).stream().anyMatch((d) -> d.key().equals(item) && d.automatic());
        if (done) {
            String problem = CloseChecklist.tickProblem(s, item, in == null ? null : in.note(), automatic);
            if (problem != null) throw bad(problem + " Nothing was saved.");
            reviews.tick(branchCode, s, p, item, in == null ? null : in.note(), auth.getName());
        } else {
            reviews.untick(branchCode, s, p, item);
        }
        AuditLog.write(jdbc, auth.getName(), done ? "accounting.close.tick" : "accounting.close.untick", "close", branchCode + "/" + s + "/" + p + "/" + item,
            null, done ? (in == null || in.note() == null ? "done" : in.note().trim()) : null, null);
        return review(branchCode, s, p);
    }

    /**
     * Signs a month off once its review is complete and the month has ended. When monthly closing is required, its accounting periods are closed too, so
     * nothing more is posted into it. Audited.
     */
    @PostMapping("/month/{period}/signoff")
    @Transactional
    Review signMonth(@RequestParam int branchCode, @PathVariable String period, @RequestBody(required = false) SignIn in, Authentication auth) {
        requireStored();
        String p = checkPeriod(CloseChecklist.MONTH, period);
        Review r = review(branchCode, CloseChecklist.MONTH, p);
        if (!r.ended()) throw bad(p + " has not ended yet; it can be signed off from " + r.to().plusDays(1) + ".");
        if (r.signedBy() != null) throw bad(p + " was already signed off by " + r.signedBy() + ".");
        if (!r.complete()) throw bad(r.open() + " item" + (r.open() == 1 ? " of the review is" : "s of the review are") + " not done. Confirm or explain each, then sign off.");
        reviews.sign(branchCode, CloseChecklist.MONTH, p, in == null ? null : in.note(), auth.getName());
        AuditLog.write(jdbc, auth.getName(), "accounting.close.month", "close", branchCode + "/MONTH/" + p, null, "signed off", in == null ? null : in.note());
        if (reviews.monthlyClose() && db.tableExists("ecis_accounting_period")) {
            List<Map<String, Object>> open = jdbc.queryForList("""
                    SELECT id, status FROM ecis_accounting_period WHERE branch_code = ? AND start_date >= ? AND end_date <= ? AND status IN ('OPEN', 'SOFT_CLOSED') ORDER BY id
                    """, branchCode, r.from(), r.to());
            for (Map<String, Object> period0 : open) {
                long id = ((Number) period0.get("id")).longValue();
                jdbc.update("UPDATE ecis_accounting_period SET status = 'CLOSED', updated_by = ?, updated_at = ? WHERE id = ?", auth.getName(), LocalDateTime.now(), id);
                AuditLog.write(jdbc, auth.getName(), "PERIOD_STATUS", "accounting_period", String.valueOf(id), (String) period0.get("status"), "CLOSED", "Month-end close of " + p);
            }
        }
        return review(branchCode, CloseChecklist.MONTH, p);
    }
}
