package ph.coop.core.accounting.close;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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
import ph.coop.core.accounting.close.YearEndRules.Balance;
import ph.coop.core.accounting.close.YearEndRules.Department;
import ph.coop.core.accounting.close.YearEndRules.Line;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;

/**
 * Year-end closing of the books (Accounting > Year-end close). An accountant or administrator looks at the closing for a finished year and
 * prepares it; a different person posts it. Posting writes the old system's Closing Entry into the legacy ledger, dated 31 December
 * (see {@link YearEndRules} and documentation/state/year-end-close.md), closes the year's open accounting periods, and adds the closing to
 * the year's built balances. A closed year is never reopened here; a mistake found later is corrected by a journal in an open year.
 */
@RestController
@RequestMapping("/api/year-end-close")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class YearEndCloseController {

    record Preview(int branchCode, int year, LocalDate closingDate, String surplusAccount, BigDecimal income, BigDecimal expenses, BigDecimal surplus,
                   int lineCount, List<Department> departments, List<String> problems, Close waiting) {}

    record Close(long id, int branchCode, int year, String status, BigDecimal surplus, int lineCount, String preparedBy, LocalDateTime preparedAt,
                 String decidedBy, LocalDateTime decidedAt, String reason, Long controlNo) {}

    record Closes(Integer lastClosedYear, List<Close> closes) {}

    record NewClose(int branchCode, int year) {}

    record Reason(String reason) {}

    private record Person(int id, int dept) {}

    private static final String CLOSE_COLUMNS = "id, branch_code, tr_year, status, surplus, line_count, prepared_by, prepared_at, decided_by, decided_at, reason, tr_ctlno";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final ClosedBooks books;
    private final String surplusAccount;

    YearEndCloseController(JdbcTemplate jdbc, Db db, Clock clock, ClosedBooks books,
                           @Value("${coop.year-end.surplus-account:30198}") String surplusAccount) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.books = books;
        this.surplusAccount = surplusAccount;
    }

    @GetMapping
    Closes list(@RequestParam int branchCode) {
        return new Closes(books.lastClosedYear(branchCode),
                jdbc.query("SELECT " + CLOSE_COLUMNS + " FROM ecis_year_end_close WHERE branch_code = ? ORDER BY tr_year DESC, id DESC", YearEndCloseController::close, branchCode));
    }

    @GetMapping("/preview")
    Preview preview(@RequestParam int branchCode, @RequestParam int year) {
        List<Balance> balances = balances(branchCode, year);
        List<Line> lines = YearEndRules.closingLines(balances, surplusAccount);
        List<Department> depts = YearEndRules.departments(balances);
        BigDecimal income = depts.stream().map(Department::income).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expenses = depts.stream().map(Department::expenses).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> problems = YearEndRules.problems(facts(branchCode, year, lines.isEmpty()));
        List<Close> waiting = jdbc.query("SELECT " + CLOSE_COLUMNS + " FROM ecis_year_end_close WHERE branch_code = ? AND tr_year = ? AND status = 'PREPARED'",
                YearEndCloseController::close, branchCode, year);
        return new Preview(branchCode, year, YearEndRules.closingDate(year), surplusAccount, income, expenses, YearEndRules.surplus(balances),
                lines.size(), depts, problems, waiting.isEmpty() ? null : waiting.get(0));
    }

    /** Records the closing as PREPARED, with a fingerprint of its lines. Writes nothing to the ledger. */
    @PostMapping
    @Transactional
    Close prepare(@RequestBody NewClose in, Authentication auth) {
        if (in == null || in.year() < 2000) throw bad("Choose the year to close.");
        Preview p = preview(in.branchCode(), in.year());
        if (!p.problems().isEmpty()) throw conflict(String.join(" ", p.problems()));
        if (p.waiting() != null) throw conflict("A closing of " + in.year() + " prepared by " + p.waiting().preparedBy() + " is already waiting to be posted.");
        legacyPerson(auth.getName(), "person preparing the closing");
        List<Line> lines = YearEndRules.closingLines(balances(in.branchCode(), in.year()), surplusAccount);
        long id;
        try {
            id = db.insertReturningId("""
                    INSERT INTO ecis_year_end_close (branch_code, tr_year, status, surplus, line_count, fingerprint, prepared_by, prepared_at)
                    VALUES (?, ?, 'PREPARED', ?, ?, ?, ?, ?)
                    """, "id", in.branchCode(), in.year(), p.surplus(), lines.size(), YearEndRules.fingerprint(lines), auth.getName(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            throw conflict("A closing of " + in.year() + " is already waiting or posted.");
        }
        AuditLog.write(jdbc, auth.getName(), "yearend.prepare", "ecis_year_end_close", String.valueOf(id), null,
                "year " + in.year() + ", surplus " + p.surplus().toPlainString() + ", " + lines.size() + " lines", null);
        return get(id);
    }

    /** Posts a prepared closing into the legacy ledger. The person posting must not be the one who prepared it. */
    @PostMapping("/{id}/post")
    @Transactional
    Close post(@PathVariable long id, Authentication auth) {
        Close c = lock(id);
        if (!"PREPARED".equals(c.status())) throw conflict("This closing is " + c.status().toLowerCase() + ".");
        if (c.preparedBy().equals(auth.getName())) throw conflict("The person who prepared the closing cannot post it. Ask another accountant.");
        int branch = c.branchCode(), year = c.year();
        List<Line> lines = YearEndRules.closingLines(balances(branch, year), surplusAccount);
        List<String> problems = YearEndRules.problems(facts(branch, year, lines.isEmpty()));
        if (!problems.isEmpty()) throw conflict(String.join(" ", problems));
        String fp = jdbc.queryForObject("SELECT fingerprint FROM ecis_year_end_close WHERE id = ?", String.class, id);
        if (!YearEndRules.fingerprint(lines).equals(fp)) {
            throw conflict("The ledger for " + year + " changed after the closing was prepared. Cancel this closing and prepare it again.");
        }
        Person preparer = legacyPerson(c.preparedBy(), "person who prepared the closing");
        Person poster = legacyPerson(auth.getName(), "person posting the closing");
        long ctl = writeClosingEntry(branch, year, lines, preparer, poster);
        int n = jdbc.update("UPDATE ecis_year_end_close SET status = 'POSTED', decided_by = ?, decided_at = ?, tr_ctlno = ? WHERE id = ? AND status = 'PREPARED'",
                auth.getName(), LocalDateTime.now(clock), ctl, id);
        if (n != 1) throw conflict("The closing changed while you were working. Reload it.");
        closePeriods(branch, year, auth);
        addToBuiltBalances(branch, year, lines);
        AuditLog.write(jdbc, auth.getName(), "yearend.post", "transsum", branch + "/" + year + "/1/" + ClosedBooks.CLOSING_ENTRY + "/" + ctl, "PREPARED",
                "POSTED (closing " + id + ", " + lines.size() + " lines)", null);
        return get(id);
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    Close cancel(@PathVariable long id, @RequestBody(required = false) Reason body, Authentication auth) {
        if (body == null || body.reason() == null || body.reason().isBlank()) throw bad("A reason is required to cancel a closing.");
        Close c = lock(id);
        if (!"PREPARED".equals(c.status())) throw conflict("Only a closing that is waiting can be cancelled; this one is " + c.status().toLowerCase() + ".");
        String reason = body.reason().trim();
        jdbc.update("UPDATE ecis_year_end_close SET status = 'CANCELLED', decided_by = ?, decided_at = ?, reason = ? WHERE id = ? AND status = 'PREPARED'",
                auth.getName(), LocalDateTime.now(clock), reason.substring(0, Math.min(500, reason.length())), id);
        AuditLog.write(jdbc, auth.getName(), "yearend.cancel", "ecis_year_end_close", String.valueOf(id), "PREPARED", "CANCELLED", reason);
        return get(id);
    }

    // ---- ledger ----

    /**
     * Writes the Closing Entry as the old system does: one transsum header (module 1, type 99, dated 31 December, "Closing Entries (Computer
     * Generated)"), one transdt line per closing line (adjustment flag 1, update tag 1) and a gltrans row for each. It then cancels the year's
     * type 98 running rows on the net surplus for the period account with one one-sided type 99 gltrans row per department, as the old
     * closing does, so that account ends the year at zero. The old system writes zero lines for every other account too; those change nothing
     * and are left out.
     */
    private long writeClosingEntry(int branch, int year, List<Line> lines, Person preparer, Person poster) {
        LocalDate date = YearEndRules.closingDate(year);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        long ctl = db.nextControlNumber(branch, 1, ClosedBooks.CLOSING_ENTRY, year);
        jdbc.update("""
                INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, branch, year, ClosedBooks.CLOSING_ENTRY, ctl, String.format("%08d", ctl), date, now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                date.format(DateTimeFormatter.BASIC_ISO_DATE),
                "Closing Entries (Computer Generated) for Date Ending: " + date.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")), now);
        List<Object[]> dt = new ArrayList<>();
        List<Object[]> gl = new ArrayList<>();
        int seq = 0;
        for (Line l : lines) {
            dt.add(new Object[]{branch, year, ClosedBooks.CLOSING_ENTRY, ctl, seq++, l.dept(), l.account(), l.amount(), preparer.id(), poster.id(), poster.id()});
            gl.add(glRow(branch, l.account(), date, l.dept(), l.amount(), now));
        }
        String pl = books.profitLossAccount(branch);
        jdbc.query("""
                SELECT GLTransDEPT, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE = ?
                GROUP BY GLTransDEPT ORDER BY GLTransDEPT
                """, rs -> {
            BigDecimal net = rs.getBigDecimal(2);
            if (net != null && net.signum() != 0) gl.add(glRow(branch, pl, date, rs.getInt(1), net.negate(), now));
        }, branch, pl, LocalDate.of(year, 1, 1), date, ClosedBooks.PROFIT_LOSS_SUMMARY);
        jdbc.batchUpdate("""
                INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSL_DATE, DTADJ_FLAG, DTAMT,
                  DTENCDBY, DTAPPRVBY, DTPOSTBY, DTUPD_TAG, DTREFERENCE, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, NULL, 1, ?, ?, ?, ?, 1, '', 0, 0, 0)
                """, dt);
        jdbc.batchUpdate("""
                INSERT INTO gltrans (GLTransBR_CODE, GLTransACCT_CODE, GLTransTR_DATE, GLTransTR_CODE, GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT,
                  GLTransDR_BALB4, GLTransCR_BALB4, GLTransSUM_BALB4, GLTransSYNC, GLTransLASTUPDATED)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, ?)
                """, gl);
        return ctl;
    }

    private static Object[] glRow(int branch, String account, LocalDate date, int dept, BigDecimal amount, LocalDateTime now) {
        BigDecimal dr = amount.signum() > 0 ? amount : BigDecimal.ZERO;
        BigDecimal cr = amount.signum() < 0 ? amount : BigDecimal.ZERO;
        return new Object[]{branch, account, date, ClosedBooks.CLOSING_ENTRY, dept, dr, cr, now};
    }

    /** Open and soft-closed periods that lie inside the year become CLOSED, each audited. */
    private void closePeriods(int branch, int year, Authentication auth) {
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id, status FROM ecis_accounting_period WHERE branch_code = ? AND start_date >= ? AND end_date <= ? AND status IN ('OPEN', 'SOFT_CLOSED') ORDER BY id
                """, branch, LocalDate.of(year, 1, 1), YearEndRules.closingDate(year));
        for (Map<String, Object> p : open) {
            long pid = ((Number) p.get("id")).longValue();
            jdbc.update("UPDATE ecis_accounting_period SET status = 'CLOSED', updated_by = ?, updated_at = ? WHERE id = ?", auth.getName(), LocalDateTime.now(clock), pid);
            AuditLog.write(jdbc, auth.getName(), "PERIOD_STATUS", "accounting_period", String.valueOf(pid), (String) p.get("status"), "CLOSED", "Year-end close of " + year);
        }
    }

    /** A year whose balances were already summed (ecis_gl_year_balance) gets the closing added, so the statements stay right. */
    private void addToBuiltBalances(int branch, int year, List<Line> lines) {
        Integer built = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_gl_year_built WHERE branch_code = ? AND tr_year = ?", Integer.class, branch, year);
        if (built == null || built == 0) return;
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        for (Line l : lines) byAccount.merge(l.account(), l.amount(), BigDecimal::add);
        jdbc.query("SELECT GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ? AND GLTransTR_DATE = ? AND GLTransTR_CODE = ? GROUP BY GLTransACCT_CODE",
                rs -> { byAccount.put(rs.getString(1), rs.getBigDecimal(2)); },
                branch, books.profitLossAccount(branch), YearEndRules.closingDate(year), ClosedBooks.CLOSING_ENTRY);
        for (var e : byAccount.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() == 0) continue;
            int n = jdbc.update("UPDATE ecis_gl_year_balance SET net = net + ? WHERE branch_code = ? AND tr_year = ? AND account_code = ?", e.getValue(), branch, year, e.getKey());
            if (n == 0) jdbc.update("INSERT INTO ecis_gl_year_balance (branch_code, tr_year, account_code, net) VALUES (?, ?, ?, ?)", branch, year, e.getKey(), e.getValue());
        }
    }

    // ---- reads ----

    /** The year's net per department and income or expense account, leaving out the old system's type 98 and any earlier closing. */
    private List<Balance> balances(int branch, int year) {
        return jdbc.query("""
                SELECT GLTransDEPT, GLTransACCT_CODE, SUM(GLTransDR_AMT) + SUM(GLTransCR_AMT) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ? AND GLTransTR_CODE NOT IN (?, ?)
                  AND (GLTransACCT_CODE LIKE '4%' OR GLTransACCT_CODE LIKE '5%')
                GROUP BY GLTransDEPT, GLTransACCT_CODE ORDER BY GLTransDEPT, GLTransACCT_CODE
                """, (rs, i) -> new Balance(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)),
                branch, LocalDate.of(year, 1, 1), YearEndRules.closingDate(year), ClosedBooks.PROFIT_LOSS_SUMMARY, ClosedBooks.CLOSING_ENTRY);
    }

    private YearEndRules.Facts facts(int branch, int year, boolean nothingToClose) {
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_journal WHERE branch_code = ? AND journal_date BETWEEN ? AND ? AND status <> 'POSTED'",
                Integer.class, branch, LocalDate.of(year, 1, 1), YearEndRules.closingDate(year));
        Integer usable = jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = ? AND COAID = ? AND COAacct_tag = 'D' AND COALESCE(COAisdisabled, 0) = 0",
                Integer.class, branch, surplusAccount);
        return new YearEndRules.Facts(year, LocalDate.now(clock), books.isClosed(branch, year), books.lastClosedYear(branch),
                pending == null ? 0 : pending, usable != null && usable > 0, nothingToClose);
    }

    private Close get(long id) {
        return jdbc.query("SELECT " + CLOSE_COLUMNS + " FROM ecis_year_end_close WHERE id = ?", YearEndCloseController::close, id).get(0);
    }

    private Close lock(long id) {
        List<Close> c = jdbc.query("SELECT " + CLOSE_COLUMNS + " FROM ecis_year_end_close WHERE id = ? FOR UPDATE", YearEndCloseController::close, id);
        if (c.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such closing.");
        return c.get(0);
    }

    private static Close close(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Timestamp decided = rs.getTimestamp(10);
        long ctlValue = rs.getLong(12);
        Long ctl = rs.wasNull() ? null : ctlValue;
        return new Close(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getBigDecimal(5), rs.getInt(6), rs.getString(7),
                rs.getTimestamp(8).toLocalDateTime(), rs.getString(9), decided == null ? null : decided.toLocalDateTime(), rs.getString(11), ctl);
    }

    private Person legacyPerson(String username, String role) {
        List<Person> p = jdbc.query("SELECT AccessID, COALESCE(AccessDepartment, 0) FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
                (rs, i) -> new Person(rs.getInt(1), rs.getInt(2)), username);
        if (p.isEmpty()) throw conflict("The " + role + " (" + username + ") is not an enabled user of the old system, so the closing cannot be written to the ledger.");
        return p.get(0);
    }

    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
