package ph.coop.core.businessday;

import ph.coop.core.connection.DatabaseChanges;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.businessday.BusinessDayRules.Check;
import ph.coop.core.frontoffice.DayEndChecks;
import ph.coop.core.frontoffice.DayEndJobs;

/**
 * Start Day and End Day (Front Office > Business Day). Start Day opens one business date for the branch; postings then carry it (BusinessDay). End
 * Day runs the checks (DayEndChecks) and closes the day, or closes it anyway with a reason when a check that must pass fails; the failed checks
 * are kept with the day. An administrator may reopen the last closed day, with a reason, while no day is open. The rights are the old system's
 * AccessStartDay and AccessEndDay; administrators may always (the way in when nobody with the right is there). Every step is logged in
 * ecis_business_day_log and the audit log.
 */
@RestController
@RequestMapping("/api/business-day")
public class BusinessDayController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public record DayRow(String date, String words, String status, String openedBy, String openedAt, String closedBy, String closedAt, String overrideReason, String failedChecks) {}

    /** What the top bar and the posting screens need: whether the business day is in use and the open date, if any. */
    public record Status(boolean inUse, String calendarDate, String openDate, String openWords) {}

    public record State(int branch, boolean inUse, boolean available, String calendarDate, DayRow open, DayRow lastClosed, String suggested, String legacyDate,
                        boolean mayStart, boolean mayEnd, boolean mayReopen, List<Check> checks, List<DayRow> recent) {}

    public record StartCheck(String problem, List<String> warnings) {}

    public record StartIn(String date, Boolean confirmed) {}

    public record ReasonIn(String reason) {}

    private record Person(String username, int branch, boolean start, boolean end, boolean frontOffice, boolean admin) {
        boolean maySee() { return start || end || frontOffice || admin; }
    }

    private final JdbcTemplate jdbc;
    private final BusinessDay day;
    private final DayEndChecks dayEnd;
    private final DayEndJobs jobs;
    private final TransactionTemplate tx;
    private final Clock clock;

    BusinessDayController(JdbcTemplate jdbc, BusinessDay day, DayEndChecks dayEnd, DayEndJobs jobs, PlatformTransactionManager txm, Clock clock) {
        this.jdbc = jdbc;
        this.day = day;
        this.dayEnd = dayEnd;
        this.jobs = jobs;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    // ---- reading ------------------------------------------------------------------------------------------------------

    /** For everyone signed in: the top bar shows the business date, and screens say when posting is closed. */
    @GetMapping("/status")
    public Status status(Authentication auth) {
        Person p = person(auth);
        LocalDate open = day.available() ? day.openDay(p.branch()).orElse(null) : null;
        return new Status(day.inUse(), day.calendarDate().toString(), open == null ? null : open.toString(), open == null ? null : BusinessDayRules.words(open));
    }

    @GetMapping
    public State state(Authentication auth) {
        Person p = person(auth);
        if (!p.maySee()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not work in the front office, so you cannot do this. Ask an administrator to give you front office access in Settings > Users.");
        LocalDate today = day.calendarDate();
        if (!day.available()) {
            return new State(p.branch(), false, false, today.toString(), null, null, null, legacyDate(p.branch()), false, false, false, List.of(), List.of());
        }
        List<DayRow> recent = days(p.branch(), 30);
        DayRow open = recent.stream().filter((d) -> "OPEN".equals(d.status())).findFirst().orElse(null);
        DayRow lastClosed = recent.stream().filter((d) -> "CLOSED".equals(d.status())).findFirst().orElse(null);
        LocalDate closedOn = lastClosed == null ? null : LocalDate.parse(lastClosed.date());
        LocalDate suggested = open != null ? null : BusinessDayRules.suggested(closedOn, today, day.holidays(p.branch(), today.minusDays(14), today));
        List<Check> checks = open == null ? List.of() : dayEnd.checks(p.branch(), LocalDate.parse(open.date()));
        return new State(p.branch(), day.inUse(), true, today.toString(), open, lastClosed, suggested == null ? null : suggested.toString(), legacyDate(p.branch()),
            open == null && (p.start() || p.admin()), open != null && (p.end() || p.admin()), open == null && lastClosed != null && p.admin(), checks, recent);
    }

    /** Whether a date can be opened, and what to confirm first. */
    @GetMapping("/start-check")
    public StartCheck startCheck(@RequestParam String date, Authentication auth) {
        Person p = person(auth);
        if (!p.start() && !p.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not start the business day.");
        requireAvailable();
        LocalDate d = parse(date);
        LocalDate today = day.calendarDate();
        LocalDate open = day.openDay(p.branch()).orElse(null);
        LocalDate lastClosed = lastClosed(p.branch());
        String problem = BusinessDayRules.startProblem(open, lastClosed, d, today);
        return new StartCheck(problem, problem != null ? List.of() : BusinessDayRules.startWarnings(lastClosed, d, today, holidaysAround(p.branch(), lastClosed, d)));
    }

    // ---- Start Day, End Day, reopen --------------------------------------------------------------------------------

    @PostMapping("/start")
    public State start(@RequestBody StartIn in, Authentication auth) {
        Person p = person(auth);
        if (!p.start() && !p.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not start the business day. An administrator can give the Start Day right in Settings > Users.");
        requireAvailable();
        LocalDate d = parse(in == null ? null : in.date());
        tx.executeWithoutResult((st) -> {
            lockBranch(p.branch());
            LocalDate today = day.calendarDate();
            LocalDate open = day.openDay(p.branch()).orElse(null);
            LocalDate lastClosed = lastClosed(p.branch());
            String problem = BusinessDayRules.startProblem(open, lastClosed, d, today);
            if (problem != null) throw new ResponseStatusException(HttpStatus.CONFLICT, problem);
            List<String> warnings = BusinessDayRules.startWarnings(lastClosed, d, today, holidaysAround(p.branch(), lastClosed, d));
            if (!warnings.isEmpty() && !Boolean.TRUE.equals(in.confirmed())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirm before starting the day: " + String.join(" ", warnings));
            }
            LocalDateTime now = LocalDateTime.now(clock);
            jdbc.update("INSERT INTO ecis_business_day (branch_code, business_date, status, opened_by, opened_at) VALUES (?, ?, 'OPEN', ?, ?)",
                p.branch(), java.sql.Date.valueOf(d), p.username(), Timestamp.valueOf(now));
            String detail = warnings.isEmpty() ? null : String.join(" ", warnings);
            log(p, d, "START", detail, null, now);
            AuditLog.write(jdbc, p.username(), "business.day.start", "ecis_business_day", p.branch() + "/" + d, null, "OPEN" + (detail == null ? "" : "; confirmed: " + detail), null);
        });
        return state(auth);
    }

    @PostMapping("/end")
    public State end(@RequestBody(required = false) ReasonIn in, Authentication auth) {
        Person p = person(auth);
        if (!p.end() && !p.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not end the business day. An administrator can give the End Day right in Settings > Users.");
        requireAvailable();
        LocalDate open = day.openDay(p.branch()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No business day is open."));
        String reason = in == null || in.reason() == null ? null : in.reason().trim();
        // The checks only read; they run before the closing transaction so a table missing on an older database cannot spoil it. They run before the
        // jobs too, so a day that cannot end yet (a drawer not counted, no reason given) is refused at once instead of after the jobs' minute or two.
        List<Check> checks = dayEnd.checks(p.branch(), open);
        String problem = BusinessDayRules.endProblem(checks, reason);
        if (problem != null) throw new ResponseStatusException(HttpStatus.CONFLICT, problem);
        // the end-of-day jobs run while the day is still open (a step that fails is kept and can be run again; it does not stop End Day)
        jobs.run(p.branch(), open, auth, true);
        // checked again: the jobs take a while and something may have changed meanwhile (they only add items to review, never a failing check)
        checks = dayEnd.checks(p.branch(), open);
        problem = BusinessDayRules.endProblem(checks, reason);
        if (problem != null) throw new ResponseStatusException(HttpStatus.CONFLICT, problem);
        List<Check> failed = BusinessDayRules.failing(checks);
        String why = failed.isEmpty() ? null : reason.substring(0, Math.min(reason.length(), 300));
        String failedText = failed.isEmpty() ? null : BusinessDayRules.summary(failed);
        tx.executeWithoutResult((st) -> {
            lockBranch(p.branch());
            LocalDateTime now = LocalDateTime.now(clock);
            int n = jdbc.update("UPDATE ecis_business_day SET status = 'CLOSED', closed_by = ?, closed_at = ?, override_reason = ?, failed_checks = ? WHERE branch_code = ? AND business_date = ? AND status = 'OPEN'",
                p.username(), Timestamp.valueOf(now), why, failedText, p.branch(), java.sql.Date.valueOf(open));
            if (n != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone changed the business day while you were ending it, so it was not ended. Reload Business Day, check where it stands, and end it again if it is still open.");
            log(p, open, failed.isEmpty() ? "END" : "END_OVERRIDE", failedText, why, now);
            AuditLog.write(jdbc, p.username(), failed.isEmpty() ? "business.day.end" : "business.day.end.override", "ecis_business_day", p.branch() + "/" + open,
                "OPEN", "CLOSED" + (failedText == null ? "" : "; failed: " + failedText), why);
        });
        return state(auth);
    }

    /** The end-of-day jobs of a day (the open one when no date is given). */
    @GetMapping("/jobs")
    public DayEndJobs.State jobs(@RequestParam(required = false) String date, Authentication auth) {
        Person p = person(auth);
        if (!p.maySee()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not work in the front office, so you cannot do this. Ask an administrator to give you front office access in Settings > Users.");
        LocalDate d = date == null || date.isBlank() ? day.openDay(p.branch()).orElse(day.calendarDate()) : parse(date);
        return jobs.state(p.branch(), d);
    }

    /** Runs the open day's end-of-day jobs now (steps already done are skipped; failed ones are tried again). End Day runs them too. */
    @PostMapping("/jobs/run")
    public DayEndJobs.State runJobs(Authentication auth) {
        Person p = person(auth);
        if (!p.end() && !p.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not run the end-of-day jobs. They need the End Day right.");
        requireAvailable();
        if (!jobs.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, DatabaseChanges.notSetUp("the end-of-day jobs"));
        LocalDate open = day.openDay(p.branch()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No business day is open."));
        return jobs.run(p.branch(), open, auth, false);
    }

    @PostMapping("/reopen")
    public State reopen(@RequestBody ReasonIn in, Authentication auth) {
        Person p = person(auth);
        if (!p.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator may reopen a closed business day.");
        requireAvailable();
        String reason = in == null || in.reason() == null ? null : in.reason().trim();
        tx.executeWithoutResult((st) -> {
            lockBranch(p.branch());
            LocalDate open = day.openDay(p.branch()).orElse(null);
            LocalDate lastClosed = lastClosed(p.branch());
            String problem = BusinessDayRules.reopenProblem(open, lastClosed, reason);
            if (problem != null) throw new ResponseStatusException(HttpStatus.CONFLICT, problem);
            LocalDateTime now = LocalDateTime.now(clock);
            String why = reason.substring(0, Math.min(reason.length(), 300));
            jdbc.update("UPDATE ecis_business_day SET status = 'OPEN', closed_by = NULL, closed_at = NULL, override_reason = NULL, failed_checks = NULL WHERE branch_code = ? AND business_date = ? AND status = 'CLOSED'",
                p.branch(), java.sql.Date.valueOf(lastClosed));
            log(p, lastClosed, "REOPEN", null, why, now);
            AuditLog.write(jdbc, p.username(), "business.day.reopen", "ecis_business_day", p.branch() + "/" + lastClosed, "CLOSED", "OPEN", why);
        });
        return state(auth);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private Person person(Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch((a) -> "ROLE_ADMIN".equals(a.getAuthority()));
        List<Person> r = jdbc.query("""
                SELECT AccessBR_CODE, COALESCE(AccessStartDay, 0), COALESCE(AccessEndDay, 0), COALESCE(AccessCanAccessFrontOffice, 0) FROM access
                WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 ORDER BY AccessID LIMIT 1
                """, (rs, i) -> new Person(auth.getName(), rs.getInt(1), rs.getInt(2) == 1, rs.getInt(3) == 1, rs.getInt(4) == 1, admin), auth.getName());
        // the bootstrap administrator is not in the old user list; it works in branch 1
        if (r.isEmpty()) return new Person(auth.getName(), 1, false, false, false, admin);
        return r.get(0);
    }

    private void requireAvailable() {
        if (!day.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The business day is not set up in this database yet. An administrator has to apply the business day migration.");
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the date to open.");
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The date is not a date: " + s);
        }
    }

    /** Holds the branch's day rows for the rest of the transaction, so two people cannot start (or end) a day at the same time. */
    private void lockBranch(int branch) {
        jdbc.query("SELECT business_date FROM ecis_business_day WHERE branch_code = ? ORDER BY business_date FOR UPDATE", (rs) -> { }, branch);
    }

    private LocalDate lastClosed(int branch) {
        List<java.sql.Date> d = jdbc.queryForList("SELECT MAX(business_date) FROM ecis_business_day WHERE branch_code = ? AND status = 'CLOSED'", java.sql.Date.class, branch);
        return d.isEmpty() || d.get(0) == null ? null : d.get(0).toLocalDate();
    }

    private Map<LocalDate, String> holidaysAround(int branch, LocalDate lastClosed, LocalDate d) {
        LocalDate from = lastClosed == null ? d : lastClosed;
        return day.holidays(branch, from.isAfter(d) ? d : from, d);
    }

    /** The old system's own business date (semaphor), shown for reference; this application never changes it. */
    private String legacyDate(int branch) {
        try {
            List<java.sql.Date> d = jdbc.queryForList("SELECT SemTransactionDate FROM semaphor WHERE SemBR_CODE = ? ORDER BY SemaphorID", java.sql.Date.class, branch);
            return d.isEmpty() || d.get(0) == null ? null : d.get(0).toLocalDate().toString();
        } catch (org.springframework.dao.DataAccessException e) {
            return null;
        }
    }

    private List<DayRow> days(int branch, int limit) {
        return jdbc.query("""
                SELECT d.business_date, d.status, d.opened_by, d.opened_at, d.closed_by, d.closed_at, d.override_reason, d.failed_checks,
                  (SELECT CONCAT_WS(' ', a.AccessFName, a.AccessLName) FROM access a WHERE a.AccessUserName = d.opened_by ORDER BY a.AccessID LIMIT 1),
                  (SELECT CONCAT_WS(' ', a.AccessFName, a.AccessLName) FROM access a WHERE a.AccessUserName = d.closed_by ORDER BY a.AccessID LIMIT 1)
                FROM ecis_business_day d WHERE d.branch_code = ? ORDER BY d.business_date DESC LIMIT ?
                """, (rs, i) -> {
            LocalDate date = rs.getDate(1).toLocalDate();
            return new DayRow(date.toString(), BusinessDayRules.words(date), rs.getString(2), name(rs.getString(9), rs.getString(3)), stamp(rs.getTimestamp(4)),
                rs.getString(5) == null ? null : name(rs.getString(10), rs.getString(5)), stamp(rs.getTimestamp(6)), rs.getString(7), rs.getString(8));
        }, branch, limit);
    }

    private static String name(String full, String username) {
        return full == null || full.isBlank() ? username : full.trim();
    }

    private static String stamp(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().format(STAMP);
    }

    private void log(Person p, LocalDate d, String action, String detail, String reason, LocalDateTime at) {
        jdbc.update("INSERT INTO ecis_business_day_log (branch_code, business_date, action, username, at, detail, reason) VALUES (?, ?, ?, ?, ?, ?, ?)",
            p.branch(), java.sql.Date.valueOf(d), action, p.username(), Timestamp.valueOf(at), detail == null ? null : detail.substring(0, Math.min(detail.length(), 2000)), reason);
    }
}
