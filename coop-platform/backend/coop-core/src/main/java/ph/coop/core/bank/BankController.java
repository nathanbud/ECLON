package ph.coop.core.bank;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

/**
 * Bank accounts and their reconciliation (guide phase 9). The banks are the rows of {@code bankcode}; each has a ledger account
 * (BCCOAID) whose entries are the {@code transdt} rows that reached the general ledger: only those tagged 1 or 5 (DTUPD_TAG), which
 * is what the ledger totals add up to. Everything here is read-only against the legacy tables; the reconciliations live in
 * ecis_bank_recon and ecis_bank_recon_item (V013).
 */
@RestController
@RequestMapping("/api/banks")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class BankController {

    private static final int MAX_ENTRIES = 3000;
    private static final int DEFAULT_WINDOW_DAYS = 60;
    private static final int MAX_RANGE_DAYS = 400;

    record Bank(int id, String name, String accountNo, String ledgerAccount, BigDecimal bookBalance, LocalDate lastActivity,
                LocalDate reconciledThrough, BigDecimal lastStatementBalance) {}

    record Key(int year, int module, int code, long ctlNo, int seq) {}

    record Entry(Key key, LocalDate date, String transaction, String documentNo, String explanation, String reference, BigDecimal amount, BigDecimal balanceAfter) {}

    record Statement(Bank bank, LocalDate from, LocalDate to, BigDecimal opening, List<Entry> entries, BigDecimal closing, boolean truncated) {}

    record SheetItem(Key key, LocalDate date, String transaction, String documentNo, String explanation, String reference, BigDecimal amount, boolean outstanding) {}

    record Sheet(Bank bank, LocalDate statementDate, BigDecimal bookBalance, LocalDate from, Long previousId, LocalDate previousDate, Long draftId,
                 BigDecimal draftStatementBalance, List<SheetItem> items) {}

    record SaveRequest(LocalDate statementDate, BigDecimal statementBalance, List<Key> outstanding, String note) {}

    record Recon(long id, int bankId, String bank, LocalDate statementDate, BigDecimal statementBalance, BigDecimal bookBalance, BigDecimal outstandingTotal,
                 BigDecimal difference, boolean reconciled, String status, String note, String preparedBy, LocalDateTime preparedAt, String finalizedBy, LocalDateTime finalizedAt,
                 int outstandingCount) {}

    private final JdbcTemplate jdbc;
    private final Db db;

    BankController(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    // ---- bank accounts ------------------------------------------------------------------------------------------

    @GetMapping
    List<Bank> banks(@RequestParam int branchCode) {
        requireTables();
        List<Bank> banks = new ArrayList<>();
        List<Object[]> rows = jdbc.query("SELECT BCID, BCDesc2, BCACCTNO, BCCOAID FROM bankcode WHERE BCBR_CODE = ? AND BCCOAID IS NOT NULL ORDER BY BCID",
            (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)}, branchCode);
        for (Object[] r : rows) banks.add(bank(branchCode, (Integer) r[0], (String) r[1], (String) r[2], (String) r[3]));
        return banks;
    }

    private Bank bank(int branch, int id, String name, String accountNo, String coa) {
        // the general ledger is the books: debit positive, credit negative, so the sum is the balance
        Object[] gl = jdbc.query("SELECT COALESCE(SUM(GLTransDR_AMT), 0) + COALESCE(SUM(GLTransCR_AMT), 0), MAX(GLTransTR_DATE) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ?",
            (rs, i) -> new Object[] {rs.getBigDecimal(1), rs.getDate(2)}, branch, coa).get(0);
        List<Object[]> last = jdbc.query("SELECT statement_date, statement_balance FROM ecis_bank_recon WHERE branch_code = ? AND bank_id = ? AND status = 'FINAL' ORDER BY statement_date DESC LIMIT 1",
            (rs, i) -> new Object[] {rs.getDate(1), rs.getBigDecimal(2)}, branch, id);
        java.sql.Date lastActivity = (java.sql.Date) gl[1];
        return new Bank(id, name, accountNo, coa, (BigDecimal) gl[0], lastActivity == null ? null : lastActivity.toLocalDate(),
            last.isEmpty() ? null : ((java.sql.Date) last.get(0)[0]).toLocalDate(), last.isEmpty() ? null : (BigDecimal) last.get(0)[1]);
    }

    private Bank oneBank(int branch, int bankId) {
        List<Bank> found = jdbc.query("SELECT BCID, BCDesc2, BCACCTNO, BCCOAID FROM bankcode WHERE BCBR_CODE = ? AND BCID = ? AND BCCOAID IS NOT NULL",
            (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)}, branch, bankId)
            .stream().map((r) -> bank(branch, (Integer) r[0], (String) r[1], (String) r[2], (String) r[3])).toList();
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such bank account.");
        return found.get(0);
    }

    // ---- entries ------------------------------------------------------------------------------------------------

    private static final String ENTRY_SELECT = """
            SELECT x.DTTR_YEAR, x.DTTR_MODULE, x.DTTR_CODE, x.DTTR_CTLNO, x.DTSEQ_NO, t.TR_DATE, tt.TransTypeDesc, t.TR_DOCNO, t.EXPLANATION, x.DTREFERENCE, x.DTAMT
            FROM transdt x
            JOIN transsum t ON t.TR_BRCODE = x.DTTR_BRCODE AND t.TR_YEAR = x.DTTR_YEAR AND t.TR_MODULE = x.DTTR_MODULE AND t.TR_CODE = x.DTTR_CODE AND t.TR_CTLNO = x.DTTR_CTLNO
            LEFT JOIN transtype tt ON tt.TransTypeID = x.DTTR_CODE
            WHERE x.DTTR_BRCODE = ? AND x.DTACCT_CODE = ? AND x.DTUPD_TAG IN (1, 5)
            """;

    private static Entry entry(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Entry(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getInt(5)), rs.getDate(6).toLocalDate(), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getString(10), rs.getBigDecimal(11), null);
    }

    @GetMapping("/{bankId}/entries")
    Statement entries(@PathVariable int bankId, @RequestParam int branchCode, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        requireTables();
        if (to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The range is limited to " + MAX_RANGE_DAYS + " days.");
        Bank bank = oneBank(branchCode, bankId);
        BigDecimal opening = jdbc.queryForObject("""
                SELECT COALESCE(SUM(x.DTAMT), 0) FROM transdt x
                JOIN transsum t ON t.TR_BRCODE = x.DTTR_BRCODE AND t.TR_YEAR = x.DTTR_YEAR AND t.TR_MODULE = x.DTTR_MODULE AND t.TR_CODE = x.DTTR_CODE AND t.TR_CTLNO = x.DTTR_CTLNO
                WHERE x.DTTR_BRCODE = ? AND x.DTACCT_CODE = ? AND x.DTUPD_TAG IN (1, 5) AND t.TR_DATE < ?
                """, BigDecimal.class, branchCode, bank.ledgerAccount(), from);
        List<Entry> raw = jdbc.query(ENTRY_SELECT + " AND t.TR_DATE BETWEEN ? AND ? ORDER BY t.TR_DATE, x.DTTR_CTLNO, x.DTSEQ_NO LIMIT " + (MAX_ENTRIES + 1),
            BankController::entry, branchCode, bank.ledgerAccount(), from, to);
        boolean truncated = raw.size() > MAX_ENTRIES;
        List<Entry> out = new ArrayList<>();
        BigDecimal running = opening;
        for (Entry e : raw.subList(0, Math.min(raw.size(), MAX_ENTRIES))) {
            running = running.add(e.amount());
            out.add(new Entry(e.key(), e.date(), e.transaction(), e.documentNo(), e.explanation(), e.reference(), e.amount(), running));
        }
        return new Statement(bank, from, to, opening, out, running, truncated);
    }

    // ---- reconciliation -----------------------------------------------------------------------------------------

    private BigDecimal bookBalanceOn(int branch, String coa, LocalDate date) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(GLTransDR_AMT), 0) + COALESCE(SUM(GLTransCR_AMT), 0) FROM gltrans WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ? AND GLTransTR_DATE <= ?",
            BigDecimal.class, branch, coa, date);
    }

    private Object[] lastFinal(int branch, int bankId) {
        List<Object[]> rows = jdbc.query("SELECT id, statement_date FROM ecis_bank_recon WHERE branch_code = ? AND bank_id = ? AND status = 'FINAL' ORDER BY statement_date DESC LIMIT 1",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getDate(2).toLocalDate()}, branch, bankId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long draftId(int branch, int bankId) {
        List<Long> d = jdbc.queryForList("SELECT id FROM ecis_bank_recon WHERE branch_code = ? AND bank_id = ? AND status = 'DRAFT' ORDER BY id DESC LIMIT 1", Long.class, branch, bankId);
        return d.isEmpty() ? null : d.get(0);
    }

    private Set<Key> itemKeys(long reconId) {
        Set<Key> keys = new HashSet<>();
        jdbc.query("SELECT tr_year, tr_module, tr_code, tr_ctlno, seq_no FROM ecis_bank_recon_item WHERE recon_id = ?",
            (rs) -> { keys.add(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getInt(5))); }, reconId);
        return keys;
    }

    /** The entries to tick through for a statement date: everything since the last reconciliation (or the last 60 days), plus what was left outstanding then. */
    @GetMapping("/{bankId}/reconciliation-sheet")
    Sheet sheet(@PathVariable int bankId, @RequestParam int branchCode, @RequestParam LocalDate statementDate) {
        requireTables();
        Bank bank = oneBank(branchCode, bankId);
        Object[] prev = lastFinal(branchCode, bankId);
        LocalDate from = prev == null ? statementDate.minusDays(DEFAULT_WINDOW_DAYS) : ((LocalDate) prev[1]).plusDays(1);
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(statementDate)) from = statementDate.minusDays(MAX_RANGE_DAYS);

        Set<Key> carried = prev == null ? Set.of() : itemKeys((Long) prev[0]);
        Long draft = draftId(branchCode, bankId);
        Set<Key> chosen = new HashSet<>(carried);
        BigDecimal draftBalance = null;
        if (draft != null) {
            chosen = itemKeys(draft);
            draftBalance = jdbc.queryForObject("SELECT statement_balance FROM ecis_bank_recon WHERE id = ?", BigDecimal.class, draft);
        }
        List<Entry> found = jdbc.query(ENTRY_SELECT + " AND t.TR_DATE BETWEEN ? AND ? ORDER BY t.TR_DATE, x.DTTR_CTLNO, x.DTSEQ_NO LIMIT " + MAX_ENTRIES,
            BankController::entry, branchCode, bank.ledgerAccount(), from, statementDate);
        List<Entry> all = new ArrayList<>(found);
        Set<Key> have = new HashSet<>();
        for (Entry e : found) have.add(e.key());
        for (Key k : carried) {
            if (!have.contains(k)) all.addAll(jdbc.query(ENTRY_SELECT + " AND x.DTTR_YEAR = ? AND x.DTTR_MODULE = ? AND x.DTTR_CODE = ? AND x.DTTR_CTLNO = ? AND x.DTSEQ_NO = ?",
                BankController::entry, branchCode, bank.ledgerAccount(), k.year(), k.module(), k.code(), k.ctlNo(), k.seq()));
        }
        all.sort((a, b) -> a.date().compareTo(b.date()));
        List<SheetItem> items = new ArrayList<>();
        for (Entry e : all) items.add(new SheetItem(e.key(), e.date(), e.transaction(), e.documentNo(), e.explanation(), e.reference(), e.amount(), chosen.contains(e.key())));
        return new Sheet(bank, statementDate, bookBalanceOn(branchCode, bank.ledgerAccount(), statementDate), from, prev == null ? null : (Long) prev[0],
            prev == null ? null : (LocalDate) prev[1], draft, draftBalance, items);
    }

    /** Saves the reconciliation as a draft (replacing the bank's open draft). The numbers are worked out here from the ledger, not taken from the screen. */
    @PostMapping("/{bankId}/reconciliations")
    @Transactional
    Recon save(@PathVariable int bankId, @RequestParam int branchCode, @RequestBody SaveRequest req, Authentication auth) {
        requireTables();
        Bank bank = oneBank(branchCode, bankId);
        Object[] prev = lastFinal(branchCode, bankId);
        List<String> problems = BankReconRules.checkSave(req.statementDate(), req.statementBalance(), LocalDate.now(), prev == null ? null : (LocalDate) prev[1]);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));

        List<Key> keys = req.outstanding() == null ? List.of() : req.outstanding().stream().distinct().toList();
        BigDecimal outstanding = BigDecimal.ZERO;
        List<Entry> checked = new ArrayList<>();
        for (Key k : keys) {
            List<Entry> e = jdbc.query(ENTRY_SELECT + " AND x.DTTR_YEAR = ? AND x.DTTR_MODULE = ? AND x.DTTR_CODE = ? AND x.DTTR_CTLNO = ? AND x.DTSEQ_NO = ?",
                BankController::entry, branchCode, bank.ledgerAccount(), k.year(), k.module(), k.code(), k.ctlNo(), k.seq());
            if (e.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An entry you marked is not a ledger entry of this bank account.");
            if (e.get(0).date().isAfter(req.statementDate())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An entry you marked is dated after the statement date.");
            checked.add(e.get(0));
            outstanding = outstanding.add(e.get(0).amount());
        }
        BigDecimal statement = req.statementBalance().setScale(2, java.math.RoundingMode.UNNECESSARY);
        BigDecimal book = bookBalanceOn(branchCode, bank.ledgerAccount(), req.statementDate());
        BigDecimal diff = BankReconRules.difference(statement, book, outstanding);

        Long old = draftId(branchCode, bankId);
        if (old != null) {
            jdbc.update("DELETE FROM ecis_bank_recon_item WHERE recon_id = ?", old);
            jdbc.update("UPDATE ecis_bank_recon SET status = 'REPLACED' WHERE id = ?", old);
        }
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim().substring(0, Math.min(req.note().trim().length(), 250));
        long id = db.insertReturningId("""
                INSERT INTO ecis_bank_recon (branch_code, bank_id, statement_date, statement_balance, book_balance, outstanding_total, difference, status, note, prepared_by, prepared_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, "id", branchCode, bankId, req.statementDate(), statement, book, outstanding, diff, note, auth.getName(), Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        for (Entry e : checked) {
            jdbc.update("INSERT INTO ecis_bank_recon_item (recon_id, tr_year, tr_module, tr_code, tr_ctlno, seq_no, amount) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, e.key().year(), e.key().module(), e.key().code(), e.key().ctlNo(), e.key().seq(), e.amount());
        }
        audit(auth, "bank.recon.save", id, bank.name() + " " + req.statementDate() + " difference " + diff.toPlainString());
        return recon(id);
    }

    @GetMapping("/{bankId}/reconciliations")
    List<Recon> history(@PathVariable int bankId, @RequestParam int branchCode) {
        requireTables();
        oneBank(branchCode, bankId);
        return jdbc.queryForList("SELECT id FROM ecis_bank_recon WHERE branch_code = ? AND bank_id = ? AND status <> 'REPLACED' ORDER BY statement_date DESC, id DESC LIMIT 50", Long.class, branchCode, bankId)
            .stream().map(this::recon).toList();
    }

    /** A different person confirms a draft whose difference is zero; from then on its outstanding entries carry to the next statement. */
    @PostMapping("/reconciliations/{id}/finalize")
    @Transactional
    Recon finalizeIt(@PathVariable long id, Authentication auth) {
        requireTables();
        List<Object[]> row = jdbc.query("SELECT branch_code, bank_id, status, prepared_by, difference, statement_date FROM ecis_bank_recon WHERE id = ? FOR UPDATE",
            (rs, i) -> new Object[] {rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getDate(6).toLocalDate()}, id);
        if (row.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such reconciliation.");
        Object[] r = row.get(0);
        List<String> problems = new ArrayList<>(BankReconRules.checkFinalize((String) r[3], auth.getName(), (BigDecimal) r[4], (String) r[2]));
        Object[] prev = lastFinal((Integer) r[0], (Integer) r[1]);
        if (prev != null && !((LocalDate) r[5]).isAfter((LocalDate) prev[1])) problems.add("A later statement was already finalized.");
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", problems));
        jdbc.update("UPDATE ecis_bank_recon SET status = 'FINAL', finalized_by = ?, finalized_at = ? WHERE id = ? AND status = 'DRAFT'", auth.getName(), Timestamp.valueOf(LocalDateTime.now().withNano(0)), id);
        audit(auth, "bank.recon.finalize", id, "statement " + r[5]);
        return recon(id);
    }

    private Recon recon(long id) {
        return jdbc.query("""
                SELECT r.id, r.bank_id, b.BCDesc2, r.statement_date, r.statement_balance, r.book_balance, r.outstanding_total, r.difference, r.status, r.note,
                       r.prepared_by, r.prepared_at, r.finalized_by, r.finalized_at, (SELECT COUNT(*) FROM ecis_bank_recon_item i WHERE i.recon_id = r.id)
                FROM ecis_bank_recon r LEFT JOIN bankcode b ON b.BCBR_CODE = r.branch_code AND b.BCID = r.bank_id WHERE r.id = ?
                """, (rs, i) -> new Recon(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getBigDecimal(8).signum() == 0, rs.getString(9), rs.getString(10), rs.getString(11), rs.getTimestamp(12).toLocalDateTime(), rs.getString(13),
                rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toLocalDateTime(), rs.getInt(15)), id).get(0);
    }

    private void audit(Authentication auth, String action, long id, String detail) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (NOW(), ?, ?, 'bank_recon', ?, ?)", auth.getName(), action, String.valueOf(id), detail);
    }

    private void requireTables() {
        if (!db.allTablesExist("bankcode", "ecis_bank_recon", "ecis_bank_recon_item")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Bank reconciliation is not set up yet. An administrator has to run database/migrations/V013_bank_reconciliation.sql.");
        }
    }
}
