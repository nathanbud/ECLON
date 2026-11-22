package ph.coop.core.frontoffice;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.conflict;
import static ph.coop.core.web.ApiErrors.forbidden;
import static ph.coop.core.web.ApiErrors.notFound;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import ph.coop.core.accounting.close.ClosedBooks;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.MemberLedgerPoster.Master;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;

/**
 * Debit/credit memos: one balanced entry that moves money between members' savings or share capital accounts and general ledger accounts (the old system's DCM, transsum module 1
 * type 2). It goes DRAFT, FOR_REVIEW, APPROVED, POSTED like a journal, and the maker, approver and poster must be three different people. Posting writes what the old system wrote
 * (see documentation/debit-credit-memo-map.md): the header, one transdt line per entry, the member's sldtl rows and the gltrans lines, in one transaction. A posted memo is never
 * edited; it is reversed by a new memo that goes through the same steps. Accountants and administrators only. Rules that need no database are in {@link MemoRules}.
 */
@RestController
@RequestMapping("/api/memos")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class MemoController {

    record LineIn(String kind, Integer clientId, Integer typeCode, Integer refNo, String accountCode, BigDecimal debit, BigDecimal credit, String note) {}

    record NewMemo(int branchCode, String memoDate, String description, List<LineIn> lines) {}

    record Summary(long id, int branchCode, String reference, LocalDate memoDate, String description, String status, String createdBy, String approvedBy, String postedBy, BigDecimal total,
                   Long reverses, String documentNo) {}

    record LineOut(int lineNo, String kind, Integer clientId, String clientName, Integer typeCode, String type, Integer refNo, String refText, String accountCode, String accountTitle,
                   BigDecimal debit, BigDecimal credit, String note) {}

    record Detail(Summary summary, List<LineOut> lines, String cancelReason) {}

    record Reason(String reason) {}

    record AccountOut(String kind, int typeCode, String type, int refNo, String refText, int status, BigDecimal balance) {}

    record Accounts(int clientId, String clientName, int clientStatus, List<AccountOut> accounts) {}

    private record Row(long id, int branch, String reference, LocalDate date, String description, String status, String createdBy, String approvedBy) {}

    private record Person(int id, int dept) {}

    private static final String SUMMARY = """
            SELECT m.id, m.branch_code, m.reference, m.memo_date, m.description, m.status, m.created_by, m.approved_by, m.posted_by,
                   (SELECT COALESCE(SUM(l.debit), 0) FROM ecis_memo_line l WHERE l.memo_id = m.id), m.reverses_id, m.tr_year, m.tr_ctlno
            FROM ecis_memo m
            """;
    private static final int CLOSED = 17;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final MemberLedgerPoster poster;
    private final Clock clock;
    private final ClosedBooks books;

    private final ph.coop.core.businessday.BusinessDay businessDay;

    MemoController(JdbcTemplate jdbc, Db db, MemberLedgerPoster poster, Clock clock, ClosedBooks books, ph.coop.core.businessday.BusinessDay businessDay) {
        this.businessDay = businessDay;
        this.jdbc = jdbc;
        this.db = db;
        this.poster = poster;
        this.clock = clock;
        this.books = books;
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    @GetMapping
    List<Summary> list(@RequestParam int branchCode, @RequestParam(required = false) String status) {
        return jdbc.query(SUMMARY + " WHERE m.branch_code = ? AND (CAST(? AS CHAR) IS NULL OR m.status = ?) ORDER BY m.id DESC LIMIT 200", MemoController::summary, branchCode, status, status);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id) {
        List<Summary> s = jdbc.query(SUMMARY + " WHERE m.id = ?", MemoController::summary, id);
        if (s.isEmpty()) throw notFound("No such memo.");
        Row r = row(id, false);
        List<LineOut> lines = new ArrayList<>();
        jdbc.query("SELECT line_no, kind, client_id, type_code, ref_no, account_code, debit, credit, note FROM ecis_memo_line WHERE memo_id = ? ORDER BY line_no", (rs) -> {
            String kind = rs.getString(2);
            Integer client = integer(rs, 3), type = integer(rs, 4), ref = integer(rs, 5);
            String code = rs.getString(6);
            String clientName = null, typeName = null, refText = null, title = null;
            if (client != null) {
                clientName = first(jdbc.queryForList("SELECT COALESCE(NULLIF(AccountName, ''), CONCAT_WS(', ', LName, FName)) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", String.class, r.branch(), client));
                Master m = master(kind);
                typeName = first(jdbc.queryForList("SELECT SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", String.class, r.branch(), m.slc(), type));
                refText = first(jdbc.queryForList("SELECT " + m.prefix() + "REF_NOX FROM " + m.table() + " WHERE " + m.prefix() + "BR_CODE = ? AND ClientID" + m.prefix() + " = ? AND " + m.prefix() + "SLC_CODE = ? AND "
                    + m.prefix() + "SLT_CODE = ? AND " + m.prefix() + "REF_NO = ?", String.class, r.branch(), client, m.slc(), type, ref));
            } else if (code != null) {
                title = first(jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, r.branch(), code));
            }
            lines.add(new LineOut(rs.getInt(1), kind, client, clientName, type, typeName, ref, refText, code, title, rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9)));
        }, id);
        String why = first(jdbc.queryForList("SELECT cancel_reason FROM ecis_memo WHERE id = ?", String.class, id));
        return new Detail(s.get(0), lines, why);
    }

    /** A member's savings and share capital accounts, for choosing what a memo line is on. */
    @GetMapping("/accounts")
    Accounts accounts(@RequestParam int branchCode, @RequestParam int clientId) {
        MemberLedgerPoster.Client c = poster.client(branchCode, clientId);
        List<AccountOut> out = new ArrayList<>();
        for (var e : Map.of(MemoRules.SAVINGS, Master.SAVINGS, MemoRules.SHARE, Master.SHARE_CAPITAL).entrySet()) {
            Master m = e.getValue();
            String p = m.prefix();
            out.addAll(jdbc.query("SELECT s." + p + "SLT_CODE, t.SLTypeM_DESC, s." + p + "REF_NO, s." + p + "REF_NOX, s." + p + "STATUS, "
                + "(SELECT COALESCE(-SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = s." + p + "BR_CODE AND d.SL_CLIENTID = s.ClientID" + p + " AND d.SLC_CODE = s." + p + "SLC_CODE AND d.SLT_CODE = s." + p + "SLT_CODE"
                + " AND d.REF_NO = s." + p + "REF_NO AND d.SLE_CODE = 11) FROM " + m.table() + " s LEFT JOIN sltype t ON t.SLTypeBR_CODE = s." + p + "BR_CODE AND t.SLTypeSLC_CODE = s." + p + "SLC_CODE AND t.SLTypeSLT_CODE = s." + p + "SLT_CODE"
                + " WHERE s." + p + "BR_CODE = ? AND s.ClientID" + p + " = ? AND s." + p + "SLC_CODE = ? ORDER BY s." + p + "SLT_CODE, s." + p + "REF_NO",
                (rs, i) -> new AccountOut(e.getKey(), rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getInt(5), rs.getBigDecimal(6)), branchCode, clientId, m.slc()));
        }
        out.sort(java.util.Comparator.comparing(AccountOut::kind).thenComparing(AccountOut::typeCode).thenComparing(AccountOut::refNo));
        return new Accounts(clientId, c.name(), c.status(), out);
    }

    // ---- steps --------------------------------------------------------------------------------------------------

    @PostMapping
    @Transactional
    Summary create(@RequestBody NewMemo in, Authentication auth) {
        LocalDate date = parse(in.memoDate());
        List<MemoRules.Line> lines = rules(in.lines());
        List<String> problems = new ArrayList<>(MemoRules.problems(lines, in.description(), date, LocalDate.now(clock)));
        if (problems.isEmpty()) problems.addAll(check(in.branchCode(), lines));
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        long id = insert(in.branchCode(), date, in.description().trim(), auth.getName(), null, lines);
        audit(auth, "MEMO_CREATE", id, null, "DRAFT", in.description().trim());
        return get(id).summary();
    }

    @PostMapping("/{id}/submit")
    @Transactional
    Summary submit(@PathVariable long id, Authentication auth) {
        Row r = row(id, true);
        require(r, "DRAFT");
        if (!r.createdBy().equalsIgnoreCase(auth.getName()) && !admin(auth)) throw forbidden("Only the person who made the memo sends it for review.");
        List<String> problems = check(r.branch(), lines(id));
        if (!problems.isEmpty()) throw conflict(String.join(" ", problems));
        move(r, "FOR_REVIEW");
        audit(auth, "MEMO_SUBMIT", id, "DRAFT", "FOR_REVIEW", null);
        return get(id).summary();
    }

    @PostMapping("/{id}/approve")
    @Transactional
    Summary approve(@PathVariable long id, Authentication auth) {
        Row r = row(id, true);
        require(r, "FOR_REVIEW");
        String problem = MemoRules.separation(r.createdBy(), auth.getName(), null);
        if (problem != null) throw forbidden(problem);
        List<String> problems = check(r.branch(), lines(id));
        if (!problems.isEmpty()) throw conflict(String.join(" ", problems));
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("UPDATE ecis_memo SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ? AND status = 'FOR_REVIEW'", auth.getName(), Timestamp.valueOf(now), id);
        audit(auth, "MEMO_APPROVE", id, "FOR_REVIEW", "APPROVED", null);
        return get(id).summary();
    }

    /** Posts an approved memo into the legacy ledger as a Debit/Credit Memo. Everything is written in this one transaction, so a failure leaves nothing behind. */
    @PostMapping("/{id}/post")
    @Transactional
    Summary post(@PathVariable long id, Authentication auth) {
        Row r = row(id, true);
        require(r, "APPROVED");
        String problem = MemoRules.separation(r.createdBy(), r.approvedBy(), auth.getName());
        if (problem != null) throw forbidden(problem);
        businessDay.requireStarted(r.branch()); // posted only while the branch has a business day open, whatever the memo's own date
        requireOpenPeriod(r.branch(), r.date());
        Person postedBy = legacyPerson(auth.getName(), "person posting");
        Person creator = legacyPerson(r.createdBy(), "maker");
        Person approver = legacyPerson(r.approvedBy(), "approver");
        List<MemoRules.Line> lines = lines(id);
        // lock each member account so two postings cannot both spend the same balance, then check the balances as they are now
        for (MemoRules.Line l : lines) if (l.member()) lock(r.branch(), l);
        List<String> problems = check(r.branch(), lines);
        if (!problems.isEmpty()) throw conflict(String.join(" ", problems));

        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        Timestamp stamp = Timestamp.valueOf(now);
        LocalDate day = r.date();
        int year = day.getYear();
        long ctl = db.nextControlNumber(r.branch(), 1, 2, year);
        String docNo = String.format("*%07d", ctl);
        Integer firstMember = lines.stream().filter(MemoRules.Line::member).map(MemoRules.Line::clientId).findFirst().orElse(null);
        jdbc.update("""
                INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_CLIENTID, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                VALUES (?, ?, 1, 2, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, r.branch(), year, ctl, docNo, firstMember, day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), day.format(DateTimeFormatter.BASIC_ISO_DATE),
            r.description().substring(0, Math.min(r.description().length(), 200)), stamp);

        String sql = """
                INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO, DTSLE_CODE, DTCTS_NUM,
                  DTSL_DATE, DTADJ_FLAG, DTAMT, DTAMTDUE, DTENCDBY, DTAPPRVBY, DTPOSTBY, DTUPD_TAG, DTREFERENCE, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                VALUES (?, ?, 1, 2, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 1, ?, 0, 0, 0)
                """;
        int seq = 0;
        for (MemoRules.Line l : lines) {
            BigDecimal signed = l.signed();
            String note = l.note() == null ? "" : l.note();
            if (l.member()) {
                Master m = master(l.kind());
                MemberLedgerPoster.Account a = poster.account(m, r.branch(), l.clientId(), l.typeCode(), l.refNo());
                String gl = poster.glAccount(r.branch(), m.slc(), l.typeCode(), 11, a.status());
                jdbc.update(sql, r.branch(), year, ctl, seq++, creator.dept(), gl, l.clientId(), m.slc(), l.typeCode(), l.refNo(), 11, a.status(), day.toString(), 1, signed,
                    creator.id(), approver.id(), postedBy.id(), note);
                poster.writeGlLine(r.branch(), gl, day, 2, creator.dept(), signed, stamp);
                poster.writeLedgerRow(r.branch(), l.clientId(), new MemberLine(m.slc(), l.typeCode(), l.refNo(), 11, a.status(), gl, signed), day, 2, ctl, docNo, null, null);
            } else {
                jdbc.update(sql, r.branch(), year, ctl, seq++, creator.dept(), l.accountCode(), null, null, null, null, null, null, day.toString(), null, signed,
                    creator.id(), approver.id(), postedBy.id(), note);
                poster.writeGlLine(r.branch(), l.accountCode(), day, 2, creator.dept(), signed, stamp);
            }
        }
        int n = jdbc.update("UPDATE ecis_memo SET status = 'POSTED', posted_by = ?, posted_at = ?, tr_year = ?, tr_ctlno = ? WHERE id = ? AND status = 'APPROVED'", auth.getName(), stamp, year, ctl, id);
        if (n != 1) throw conflict("The memo changed while you were posting it. Reload it.");
        audit(auth, "MEMO_POST", id, "APPROVED", "POSTED", "doc " + docNo);
        AuditLog.write(jdbc, auth.getName(), "memo.ledger.post", "transsum", r.branch() + "/" + year + "/1/2/" + ctl, null, "memo " + r.reference() + " " + lines.size() + " lines", null);
        return get(id).summary();
    }

    /** Withdraws a memo that has not been posted. The maker or an administrator may; the reason is kept. */
    @PostMapping("/{id}/cancel")
    @Transactional
    Summary cancel(@PathVariable long id, @RequestBody Reason body, Authentication auth) {
        String why = body == null || body.reason() == null ? "" : body.reason().trim();
        if (why.length() < 3) throw bad("Give the reason for cancelling the memo.");
        Row r = row(id, true);
        if (!List.of("DRAFT", "FOR_REVIEW", "APPROVED").contains(r.status())) throw conflict("A memo that is " + r.status().toLowerCase().replace('_', ' ') + " cannot be cancelled" + (r.status().equals("POSTED") ? "; reverse it instead." : "."));
        if (!r.createdBy().equalsIgnoreCase(auth.getName()) && !admin(auth)) throw forbidden("Only the person who made the memo, or an administrator, cancels it.");
        jdbc.update("UPDATE ecis_memo SET status = 'CANCELLED', cancel_reason = ? WHERE id = ? AND status = ?", why.substring(0, Math.min(why.length(), 200)), id, r.status());
        audit(auth, "MEMO_CANCEL", id, r.status(), "CANCELLED", why);
        return get(id).summary();
    }

    /** Creates the DRAFT memo that undoes a posted one (debits and credits swapped). It then goes through the same review, approval and posting. */
    @PostMapping("/{id}/reverse")
    @Transactional
    Summary reverse(@PathVariable long id, @RequestBody Reason body, Authentication auth) {
        String why = body == null || body.reason() == null ? "" : body.reason().trim();
        if (why.length() < 3) throw bad("Give the reason for reversing the memo.");
        Row r = row(id, true);
        if (!r.status().equals("POSTED")) throw conflict("Only a posted memo can be reversed.");
        Integer already = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_memo WHERE reverses_id = ? AND status <> 'CANCELLED'", Integer.class, id);
        if (already != null && already > 0) throw conflict("This memo already has a reversal.");
        List<MemoRules.Line> swapped = lines(id).stream().map((l) -> new MemoRules.Line(l.kind(), l.clientId(), l.typeCode(), l.refNo(), l.accountCode(), l.credit(), l.debit(), l.note())).toList();
        String description = ("Reversal of " + r.reference() + ": " + why);
        long revId = insert(r.branch(), LocalDate.now(clock), description.substring(0, Math.min(description.length(), 200)), auth.getName(), id, swapped);
        audit(auth, "MEMO_REVERSE", revId, r.reference(), "DRAFT", why);
        return get(revId).summary();
    }

    // ---- checks -------------------------------------------------------------------------------------------------

    /**
     * What the database says about the lines: the ledger accounts exist and take postings, each member's account exists and is not closed, and no member's account would be
     * overdrawn by the memo as a whole. Run when the memo is made, sent, approved and again, with the accounts locked, when it is posted.
     */
    private List<String> check(int branch, List<MemoRules.Line> lines) {
        List<String> out = new ArrayList<>();
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        Map<String, String> who = new LinkedHashMap<>();
        Map<String, BigDecimal> balance = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            MemoRules.Line l = lines.get(i);
            String at = "Line " + (i + 1) + ": ";
            if (!l.member()) {
                List<Map<String, Object>> a = jdbc.queryForList("SELECT COAacct_tag, COALESCE(COAisdisabled, 0) AS off FROM coa WHERE COABR_CODE = ? AND COAID = ?", branch, l.accountCode());
                if (a.isEmpty()) out.add(at + "unknown account " + l.accountCode() + ".");
                else if ("S".equals(a.get(0).get("COAacct_tag"))) out.add(at + "account " + l.accountCode() + " is a summary account and takes no postings.");
                else if (((Number) a.get(0).get("off")).intValue() != 0) out.add(at + "account " + l.accountCode() + " is switched off.");
                continue;
            }
            Master m = master(l.kind());
            MemberLedgerPoster.Account a;
            String name;
            try {
                a = poster.account(m, branch, l.clientId(), l.typeCode(), l.refNo());
                name = poster.client(branch, l.clientId()).name();
            } catch (org.springframework.web.server.ResponseStatusException e) {
                out.add(at + e.getReason());
                continue;
            }
            if (a.status() == CLOSED) {
                out.add(at + (a.type() == null ? "the account" : a.type()) + " " + a.refText() + " of " + name + " is closed.");
                continue;
            }
            String key = m.slc() + "/" + l.typeCode() + "/" + l.refNo() + "/" + l.clientId();
            // a credit puts money into the account, a debit takes it out
            net.merge(key, l.signed().negate(), BigDecimal::add);
            who.putIfAbsent(key, (a.type() == null ? "The account" : a.type()) + " " + a.refText() + " of " + name);
            balance.putIfAbsent(key, a.balance());
        }
        for (var e : net.entrySet()) {
            String p = MemoRules.balanceProblem(who.get(e.getKey()), balance.get(e.getKey()).add(e.getValue()));
            if (p != null) out.add(p);
        }
        return out;
    }

    private void lock(int branch, MemoRules.Line l) {
        Master m = master(l.kind());
        String p = m.prefix();
        jdbc.queryForList("SELECT 1 FROM " + m.table() + " WHERE " + p + "BR_CODE = ? AND ClientID" + p + " = ? AND " + p + "SLC_CODE = ? AND " + p + "SLT_CODE = ? AND " + p + "REF_NO = ? FOR UPDATE",
            branch, l.clientId(), m.slc(), l.typeCode(), l.refNo());
    }

    /** No period covering the date, or a period that is not open, means no posting (as for a journal). */
    private void requireOpenPeriod(int branch, LocalDate date) {
        if (books.isClosed(branch, date.getYear())) throw conflict("The books for " + date.getYear() + " are closed, so the memo cannot be posted on " + date + ". Date it in an open year.");
        List<String> s = jdbc.queryForList("SELECT status FROM ecis_accounting_period WHERE branch_code = ? AND ? BETWEEN start_date AND end_date", String.class, branch, date);
        if (s.isEmpty()) throw conflict("No accounting period covers " + date + ", so the memo cannot be posted.");
        if (!"OPEN".equals(s.get(0))) throw conflict("The accounting period of " + date + " is " + s.get(0).toLowerCase().replace('_', ' ') + "; posting is not allowed.");
    }

    private Person legacyPerson(String username, String role) {
        List<Person> p = jdbc.query("SELECT AccessID, COALESCE(AccessDepartment, 0) FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1", (rs, i) -> new Person(rs.getInt(1), rs.getInt(2)), username);
        if (p.isEmpty()) throw conflict("The " + role + " (" + username + ") is not an enabled user of the old system, so the memo cannot be posted to the ledger.");
        return p.get(0);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The first of the values, or null when there is none or it is null (Stream.findFirst would throw on a null element). */
    private static <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    /** An integer column that may be null. MySQL hands back unsigned columns as Long, so a cast to Integer would fail. */
    private static Integer integer(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Master master(String kind) {
        return MemoRules.SHARE.equals(kind) ? Master.SHARE_CAPITAL : Master.SAVINGS;
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw bad("The date must look like 2026-10-31.");
        }
    }

    private static List<MemoRules.Line> rules(List<LineIn> in) {
        if (in == null) return List.of();
        return in.stream().map((l) -> new MemoRules.Line(l.kind(), l.clientId(), l.typeCode(), l.refNo(), l.accountCode() == null || l.accountCode().isBlank() ? null : l.accountCode().trim(), l.debit(), l.credit(),
            l.note() == null || l.note().isBlank() ? null : l.note().trim())).toList();
    }

    private void require(Row r, String expected) {
        if (!r.status().equals(expected)) throw conflict("The memo is " + r.status().toLowerCase().replace('_', ' ') + ", not " + expected.toLowerCase().replace('_', ' ') + ".");
    }

    private void move(Row r, String to) {
        int n = jdbc.update("UPDATE ecis_memo SET status = ? WHERE id = ? AND status = ?", to, r.id(), r.status());
        if (n != 1) throw conflict("The memo changed while you were working. Reload it.");
    }

    private Row row(long id, boolean lock) {
        List<Row> rows = jdbc.query("SELECT id, branch_code, reference, memo_date, description, status, created_by, approved_by FROM ecis_memo WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> new Row(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), id);
        if (rows.isEmpty()) throw notFound("No such memo.");
        return rows.get(0);
    }

    private List<MemoRules.Line> lines(long id) {
        return jdbc.query("SELECT kind, client_id, type_code, ref_no, account_code, debit, credit, note FROM ecis_memo_line WHERE memo_id = ? ORDER BY line_no",
            (rs, i) -> new MemoRules.Line(rs.getString(1), integer(rs, 2), integer(rs, 3), integer(rs, 4), rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8)), id);
    }

    private long insert(int branch, LocalDate date, String description, String user, Long reverses, List<MemoRules.Line> lines) {
        long id = db.insertReturningId("INSERT INTO ecis_memo (branch_code, memo_date, description, status, created_by, created_at, reverses_id) VALUES (?, ?, ?, 'DRAFT', ?, ?, ?)", "id",
            branch, date.toString(), description, user, Timestamp.valueOf(LocalDateTime.now(clock)), reverses);
        jdbc.update("UPDATE ecis_memo SET reference = ? WHERE id = ?", "MEMO-" + date.getYear() + "-" + String.format("%04d", id), id);
        int no = 1;
        for (MemoRules.Line l : lines) {
            jdbc.update("INSERT INTO ecis_memo_line (memo_id, line_no, kind, client_id, type_code, ref_no, account_code, debit, credit, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, no++, l.kind(), l.clientId(), l.typeCode(), l.refNo(), l.accountCode(), MemoRules.amount(l.debit()), MemoRules.amount(l.credit()), l.note());
        }
        return id;
    }

    private static Summary summary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Integer year = integer(rs, 12);
        Long ctl = rs.getObject(13) == null ? null : rs.getLong(13);
        return new Summary(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getBigDecimal(10),
            rs.getObject(11) == null ? null : rs.getLong(11), year == null || ctl == null ? null : String.format("*%07d", ctl));
    }

    private void audit(Authentication auth, String action, long id, String prev, String next, String reason) {
        AuditLog.write(jdbc, auth.getName(), action, "memo", String.valueOf(id), prev, next, reason);
    }
}
