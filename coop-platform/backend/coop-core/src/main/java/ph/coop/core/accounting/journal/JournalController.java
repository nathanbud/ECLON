package ph.coop.core.accounting.journal;

import java.math.BigDecimal;
import ph.coop.core.db.Db;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

/**
 * Journal workflow on the ecis_* tables: DRAFT -> FOR_REVIEW -> APPROVED -> POSTED, and reversal.
 * The rules live in {@link Journal}; this class only loads, applies a step and saves, with an audit row per step.
 * Posting marks the journal POSTED and writes it into the legacy ledger as a General Journal (transsum, transdt, gltrans).
 */
@RestController
@RequestMapping("/api/journals")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class JournalController {

    record LineIn(String accountCode, BigDecimal debit, BigDecimal credit) {}

    record NewJournal(int branchCode, String reference, LocalDate journalDate, String description, List<LineIn> lines) {}

    record Summary(long id, int branchCode, String reference, LocalDate journalDate, String description, String status,
                   String createdBy, String approvedBy, BigDecimal total) {}

    record Detail(Summary summary, List<LineIn> lines) {}

    record Reason(String reason) {}

    private record Row(long id, int branch, String reference, LocalDate date, String status, String createdBy, String approvedBy) {}

    private static final String SUMMARY = """
            SELECT j.id, j.branch_code, j.reference, j.journal_date, j.description, j.status, j.created_by, j.approved_by,
                   (SELECT COALESCE(SUM(l.debit), 0) FROM ecis_journal_line l WHERE l.journal_id = j.id)
            FROM ecis_journal j
            """;

    private final JdbcTemplate jdbc;
    private final Db db;

    JournalController(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    private static Summary summary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Summary(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9));
    }

    @GetMapping
    List<Summary> list(@RequestParam int branchCode, @RequestParam(required = false) String status) {
        return jdbc.query(SUMMARY + " WHERE j.branch_code = ? AND (? IS NULL OR j.status = ?) ORDER BY j.id DESC LIMIT 200",
                JournalController::summary, branchCode, status, status);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE j.id = ?", JournalController::summary, id);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such journal.");
        return new Detail(s.get(0), lines(id).stream().map(l -> new LineIn(l.accountCode(), l.debit(), l.credit())).toList());
    }

    @PostMapping
    @Transactional
    Summary create(@RequestBody NewJournal in, Authentication auth) {
        List<JournalLine> lines = toLines(in.lines());
        build(in.reference(), auth.getName(), lines);
        requireKnownAccounts(in.branchCode(), lines);
        long id = insert(in.branchCode(), in.reference(), in.journalDate(), in.description(), auth.getName(), null, lines);
        audit(auth, "JOURNAL_CREATE", id, null, "DRAFT", null);
        return get(id).summary();
    }

    @PostMapping("/{id}/submit")
    @Transactional
    Summary submit(@PathVariable long id, Authentication auth) {
        return step(id, auth, "JOURNAL_SUBMIT", (j, r) -> j.submitForReview());
    }

    @PostMapping("/{id}/approve")
    @Transactional
    Summary approve(@PathVariable long id, Authentication auth) {
        return step(id, auth, "JOURNAL_APPROVE", (j, r) -> j.approve(auth.getName()));
    }

    @PostMapping("/{id}/post")
    @Transactional
    Summary post(@PathVariable long id, Authentication auth) {
        step(id, auth, "JOURNAL_POST", (j, r) -> j.post(periodFor(r.branch(), r.date())));
        postToLegacyLedger(id, auth);
        return get(id).summary();
    }

    private record Person(int id, int dept) {}

    private Person legacyPerson(String username, String role) {
        List<Person> p = jdbc.query("SELECT AccessID, COALESCE(AccessDepartment, 0) FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
                (rs, i) -> new Person(rs.getInt(1), rs.getInt(2)), username);
        if (p.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The " + role + " (" + username + ") is not an enabled user of the legacy system, so the journal cannot be posted to the ledger.");
        }
        return p.get(0);
    }

    /**
     * Writes the posted journal into the legacy ledger as a General Journal (module 1, type 1), the way the old system records one:
     * a transsum header, one transdt line per journal line (debit positive, credit negative) and one gltrans row per line.
     * Runs inside the posting transaction, so a failure here also undoes the POSTED status.
     */
    private void postToLegacyLedger(long id, Authentication auth) {
        Row r = row(id);
        Person poster = legacyPerson(auth.getName(), "person posting");
        Person creator = legacyPerson(r.createdBy(), "creator");
        Person approver = legacyPerson(r.approvedBy(), "approver");
        List<String> desc = jdbc.queryForList("SELECT description FROM ecis_journal WHERE id = ?", String.class, id);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int year = r.date().getYear();
        // Control numbers run on across years for each type; the lock keeps two people from taking the same one.
        long ctl = db.nextControlNumber(r.branch(), 1, 1, year);
        String explanation = (r.reference() + (desc.get(0) == null ? "" : " - " + desc.get(0)));
        explanation = explanation.substring(0, Math.min(explanation.length(), 200));
        jdbc.update("""
                INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                VALUES (?, ?, 1, 1, ?, ?, ?, ?, ?, ?, 0, ?)
                """, r.branch(), year, ctl, String.format("*%07d", ctl), r.date(), now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                r.date().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), explanation, now);
        int seq = 0;
        for (JournalLine l : lines(id)) {
            BigDecimal amount = l.debit().signum() > 0 ? l.debit() : l.credit().negate();
            jdbc.update("""
                    INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSL_DATE, DTAMT, DTAMTDUE,
                      DTENCDBY, DTAPPRVBY, DTPOSTBY, DTREFERENCE, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                    VALUES (?, ?, 1, 1, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, '', 0, 0, 0)
                    """, r.branch(), year, ctl, seq++, creator.dept(), l.accountCode(), r.date(), amount, creator.id(), approver.id(), poster.id());
            jdbc.update("""
                    INSERT INTO gltrans (GLTransBR_CODE, GLTransACCT_CODE, GLTransTR_DATE, GLTransTR_CODE, GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT,
                      GLTransDR_BALB4, GLTransCR_BALB4, GLTransSUM_BALB4, GLTransSYNC, GLTransLASTUPDATED)
                    VALUES (?, ?, ?, 1, ?, ?, ?, 0, 0, 0, 1, ?)
                    """, r.branch(), l.accountCode(), r.date(), creator.dept(), l.debit(), l.credit().negate(), now);
        }
        jdbc.update("UPDATE ecis_journal SET tr_year = ?, tr_ctlno = ? WHERE id = ?", year, ctl, id);
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?,?,?,?,?,?)",
                now, auth.getName(), "JOURNAL_LEDGER_POST", "transsum", r.branch() + "/" + year + "/1/1/" + ctl, "journal " + r.reference());
    }

    /** Creates the DRAFT reversing journal; it then goes through the same review and approval. */
    @PostMapping("/{id}/reverse")
    @Transactional
    Summary reverse(@PathVariable long id, @RequestBody Reason body, Authentication auth) {
        if (body == null || body.reason() == null || body.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required to reverse a journal.");
        }
        Row r = row(id);
        Journal rev;
        try {
            rev = load(r).reverse(auth.getName());
        } catch (JournalException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        long revId = insert(r.branch(), rev.reference(), LocalDate.now(), "Reversal of " + r.reference() + ": " + body.reason(),
                auth.getName(), id, rev.lines());
        audit(auth, "JOURNAL_REVERSE", revId, r.reference(), "DRAFT", body.reason());
        return get(revId).summary();
    }

    // ---- helpers ----

    private interface Action { void run(Journal j, Row r); }

    private Summary step(long id, Authentication auth, String action, Action a) {
        Row r = row(id);
        Journal j = load(r);
        String before = j.status().name();
        try {
            a.run(j, r);
        } catch (JournalException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        LocalDateTime now = LocalDateTime.now();
        int n = jdbc.update("UPDATE ecis_journal SET status = ?, approved_by = ?, approved_at = ?, posted_at = ? WHERE id = ? AND status = ?",
                j.status().name(), j.approvedBy(),
                action.equals("JOURNAL_APPROVE") ? now : null,
                action.equals("JOURNAL_POST") ? now : null, id, before);
        if (n != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "The journal changed while you were working. Reload it.");
        audit(auth, action, id, before, j.status().name(), null);
        return get(id).summary();
    }

    private Row row(long id) {
        List<Row> rows = jdbc.query("SELECT id, branch_code, reference, journal_date, status, created_by, approved_by FROM ecis_journal WHERE id = ? FOR UPDATE",
                (rs, i) -> new Row(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6), rs.getString(7)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such journal.");
        return rows.get(0);
    }

    private Journal load(Row r) {
        return Journal.restore(r.reference(), r.createdBy(), lines(r.id()), JournalStatus.valueOf(r.status()), r.approvedBy());
    }

    private List<JournalLine> lines(long id) {
        return jdbc.query("SELECT account_code, debit, credit FROM ecis_journal_line WHERE journal_id = ? ORDER BY line_no",
                (rs, i) -> new JournalLine(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), id);
    }

    /** No period covering the date means no posting. */
    private PeriodStatus periodFor(int branch, LocalDate date) {
        List<String> s = jdbc.queryForList("SELECT status FROM ecis_accounting_period WHERE branch_code = ? AND ? BETWEEN start_date AND end_date",
                String.class, branch, date);
        if (s.isEmpty()) throw new JournalException("no accounting period covers " + date);
        return PeriodStatus.valueOf(s.get(0));
    }

    private static void build(String reference, String user, List<JournalLine> lines) {
        try {
            new Journal(reference, user, lines);
        } catch (IllegalArgumentException | JournalException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static List<JournalLine> toLines(List<LineIn> in) {
        if (in == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A journal needs at least two lines.");
        try {
            return in.stream().map(l -> new JournalLine(l.accountCode(), l.debit(), l.credit())).toList();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void requireKnownAccounts(int branch, List<JournalLine> lines) {
        for (JournalLine l : lines) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM coa WHERE COABR_CODE = ? AND COAID = ?", Integer.class, branch, l.accountCode());
            if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown account " + l.accountCode() + ".");
        }
    }

    private long insert(int branch, String reference, LocalDate date, String description, String user, Long reverses, List<JournalLine> lines) {
        try {
            jdbc.update("INSERT INTO ecis_journal (branch_code, reference, journal_date, description, status, created_by, created_at, reverses_id) VALUES (?,?,?,?,?,?,?,?)",
                    branch, reference, date, description, "DRAFT", user, LocalDateTime.now(), reverses);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reference " + reference + " is already used.");
        }
        Long id = jdbc.queryForObject("SELECT id FROM ecis_journal WHERE branch_code = ? AND reference = ?", Long.class, branch, reference);
        int no = 1;
        for (JournalLine l : lines) {
            jdbc.update("INSERT INTO ecis_journal_line (journal_id, line_no, account_code, debit, credit) VALUES (?,?,?,?,?)",
                    id, no++, l.accountCode(), l.debit(), l.credit());
        }
        return id;
    }

    private void audit(Authentication auth, String action, long id, String prev, String next, String reason) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (?,?,?,?,?,?,?,?)",
                LocalDateTime.now(), auth.getName(), action, "journal", String.valueOf(id), prev, next, reason);
    }
}
