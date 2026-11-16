package ph.coop.core.frontoffice;


import ph.coop.core.accounting.close.PostingPeriods;
import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;

/**
 * Penalty waivers, for accountants and administrators.
 * <p>
 * The report lists the payments where the cashier charged less penalty than the program suggested, read from the audit log of the loan payment
 * postings (the penalty, the suggestion and who allowed the difference). It is the record of what was waived at the window; the old system kept none.
 * <p>
 * The waiver journal is the accountant's General Journal mapped from the real "50% Penalty" journal (2025 type 1 no. 9573, documentation/loan-posting-map.md):
 * the expense account "Penalty waived ..." is debited and the loan is credited as event 42 (number 99, GL account from glcontrol, normally 4149901), one
 * ledger row. It does not change what the member owes: the old ledger holds no penalty balance, penalties are worked out when a payment is made. The person who
 * posts and a different accountant or administrator, who approves through an override request (kind PENALTY_WAIVER), are on the journal.
 */
@RestController
@RequestMapping("/api/loan-penalty-waivers")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class PenaltyWaiverController {

    static final String KIND = "PENALTY_WAIVER";
    private static final int WAIVED = 42, PENALTY = 41, CLASS_LOANS = 12;
    private static final Pattern DETAIL = Pattern.compile("client (\\d+) loan (\\d+)/(\\d+) amount ([0-9.]+) penalty ([0-9.]+) \\(suggested ([0-9.]+)\\)");

    record Waived(String at, String receipt, String cashier, int clientId, String client, int typeCode, int refNo, BigDecimal paid, BigDecimal suggested, BigDecimal charged,
                  BigDecimal waived, String allowedBy) {}

    record Report(String from, String to, List<Waived> rows, BigDecimal total) {}

    record Expense(String code, String name) {}

    record Request(int clientId, int typeCode, int refNo, BigDecimal amount, String expenseAccount, String reason) {}

    record Check(String description, BigDecimal suggestedPenalty, String approval, List<String> problems, boolean ready) {}

    record Posted(int year, long controlNo, String documentNo, BigDecimal amount) {}

    record Decision(boolean approve, String note) {}

    private final JdbcTemplate jdbc;

    private final ph.coop.core.businessday.BusinessDay businessDay;
    private final Db db;
    private final Clock clock;
    private final MemberLedgerPoster poster;
    private final ph.coop.core.loan.LoanBook book;
    private final TransactionTemplate tx;

    PenaltyWaiverController(JdbcTemplate jdbc, Db db, Clock clock, MemberLedgerPoster poster, ph.coop.core.loan.LoanBook book, PlatformTransactionManager txm, ph.coop.core.businessday.BusinessDay businessDay) {

        this.businessDay = businessDay;
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.poster = poster;
        this.book = book;
        this.tx = new TransactionTemplate(txm);
    }

    // ---- the report ---------------------------------------------------------------------------------------------------------

    @GetMapping("/report")
    Report report(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from == null || from.isBlank() ? today.minusDays(90) : LocalDate.parse(from);
        LocalDate t = to == null || to.isBlank() ? today : LocalDate.parse(to);
        if (t.isBefore(f)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        // who allowed each difference: the override that was spent on that receipt
        Map<String, String> allowedBy = new HashMap<>();
        List<String[]> used = new ArrayList<>();
        jdbc.query("SELECT new_value FROM ecis_audit_log WHERE action = 'loan.payment.override.used' AND at >= ? AND at < ?",
            (rs) -> { used.add(new String[] {rs.getString(1)}); }, Timestamp.valueOf(f.atStartOfDay()), Timestamp.valueOf(t.plusDays(1).atStartOfDay()));
        Pattern who = Pattern.compile("allowed by user (\\d+) for (\\S+)");
        Map<Integer, String> names = new HashMap<>();
        for (String[] u : used) {
            Matcher m = who.matcher(u[0] == null ? "" : u[0]);
            if (!m.find()) continue;
            int id = Integer.parseInt(m.group(1));
            String name = names.computeIfAbsent(id, (k) -> jdbc.queryForList("SELECT COALESCE(NULLIF(CONCAT_WS(' ', AccessFName, AccessLName), ''), AccessUserName) FROM access WHERE AccessID = ?", String.class, k)
                .stream().findFirst().orElse("user " + k));
            allowedBy.put(m.group(2), name);
        }
        List<Waived> rows = new ArrayList<>();
        Map<Integer, String> clients = new HashMap<>();
        jdbc.query("""
                SELECT at, username, entity_id, new_value FROM ecis_audit_log
                WHERE action = 'loan.payment.post' AND at >= ? AND at < ? AND new_value LIKE '%(suggested %' ORDER BY at DESC LIMIT 2000
                """, (rs) -> {
                Matcher m = DETAIL.matcher(rs.getString(4) == null ? "" : rs.getString(4));
                if (!m.find()) return;
                BigDecimal charged = new BigDecimal(m.group(5)), suggested = new BigDecimal(m.group(6));
                if (charged.compareTo(suggested) >= 0) return;
                int client = Integer.parseInt(m.group(1));
                String name = clients.computeIfAbsent(client, (k) -> jdbc.queryForList("SELECT COALESCE(NULLIF(AccountName, ''), CONCAT_WS(', ', LName, FName)) FROM client WHERE ClientID = ? LIMIT 1", String.class, k)
                    .stream().findFirst().orElse(""));
                String ref = rs.getString(3);
                rows.add(new Waived(rs.getTimestamp(1).toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), ref, rs.getString(2), client, name,
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), new BigDecimal(m.group(4)), suggested, charged, suggested.subtract(charged), allowedBy.get(ref)));
            }, Timestamp.valueOf(f.atStartOfDay()), Timestamp.valueOf(t.plusDays(1).atStartOfDay()));
        BigDecimal total = rows.stream().map(Waived::waived).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Report(f.toString(), t.toString(), rows, total);
    }

    // ---- the waiver journal -------------------------------------------------------------------------------------------------

    /** The accounts an accountant may debit: the enabled "penalty waived" detail accounts (4149911 and so on). */
    @GetMapping("/expense-accounts")
    List<Expense> expenseAccounts() {
        return jdbc.query("SELECT COAID, COADesc FROM coa WHERE COABR_CODE = 1 AND COAID LIKE '41499%' AND COAacct_tag = 'D' AND COALESCE(COAisdisabled, 0) = 0 AND COAID <> '4149901' ORDER BY COAID",
            (rs, i) -> new Expense(rs.getString(1), rs.getString(2)));
    }

    private Person who(Authentication auth) {
        Person p = poster.person(auth.getName());
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so this cannot be posted.");
        return p;
    }

    private String description(Request r) {
        String why = r.reason() == null ? "" : r.reason().trim();
        return ("WAIVE " + r.clientId() + " " + r.typeCode() + "/" + r.refNo() + " TO " + r.expenseAccount() + " " + why);
    }

    private List<String> problems(Person p, Request r) {
        List<String> out = new ArrayList<>();
        if (r.amount() == null || r.amount().signum() <= 0) out.add("Enter the amount waived.");
        else if (r.amount().stripTrailingZeros().scale() > 2) out.add("The amount can have at most 2 decimal places.");
        if (r.reason() == null || r.reason().trim().length() < 5) out.add("Give the reason (at least 5 characters), for example the approval it was waived under.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM loan WHERE LoanBR_CODE = ? AND ClientIDLoan = ? AND LoanSLC_CODE = ? AND LoanSLT_CODE = ? AND LoanREF_NO = ?", Integer.class,
            p.branch(), r.clientId(), CLASS_LOANS, r.typeCode(), r.refNo()) == 0) out.add("That client has no such loan.");
        if (r.expenseAccount() == null || expenseAccounts().stream().noneMatch((e) -> e.code().equals(r.expenseAccount()))) out.add("Choose the \"penalty waived\" account to debit.");
        try {
            poster.glAccount(p.branch(), CLASS_LOANS, r.typeCode(), WAIVED, 99);
        } catch (ResponseStatusException e) {
            out.add("This loan type has no account for waived penalties in the ledger setup.");
        }
        return out;
    }

    private String approvalStatus(Person p, BigDecimal amount, String description) {
        if (!db.tableExists("ecis_fo_override") || !db.columnExists("ecis_fo_override", "used_ref")) return "NONE";
        List<String> latest = jdbc.queryForList("""
                SELECT status FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ? AND used_ref IS NULL ORDER BY id DESC LIMIT 1
                """, String.class, p.branch(), KIND, p.id(), amount, description);
        return latest.isEmpty() ? "NONE" : latest.get(0);
    }

    private BigDecimal suggestion(Person p, Request r) {
        try {
            var t = book.terms(p.branch(), r.clientId(), r.typeCode(), r.refNo());
            return t.isEmpty() ? null : book.quote(p.branch(), r.clientId(), r.typeCode(), r.refNo(), t.get(), businessDay.today(p.branch())).suggestedPenalty();
        } catch (RuntimeException e) {
            return null; // the suggestion is only a guide here
        }
    }

    @PostMapping("/check")
    Check check(@RequestBody Request req, Authentication auth) {
        Person p = who(auth);
        List<String> problems = problems(p, req);
        String d = description(req);
        String approval = problems.isEmpty() ? approvalStatus(p, req.amount(), d) : "NONE";
        BigDecimal suggested = problems.isEmpty() ? suggestion(p, req) : null;
        return new Check(d, suggested, approval, problems, problems.isEmpty() && "APPROVED".equals(approval));
    }

    @PostMapping("/ask")
    Map<String, Object> ask(@RequestBody Request req, Authentication auth) {
        Person p = who(auth);
        List<String> problems = problems(p, req);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        String d = description(req);
        String d250 = d.substring(0, Math.min(d.length(), 250));
        if (!"NONE".equals(approvalStatus(p, req.amount(), d250)) && !"REJECTED".equals(approvalStatus(p, req.amount(), d250)))
            return Map.of("status", approvalStatus(p, req.amount(), d250));
        jdbc.update("INSERT INTO ecis_fo_override (branch_code, draft_id, kind, description, amount, limit_amount, requested_by, requested_at) VALUES (?, NULL, ?, ?, ?, NULL, ?, ?)",
            p.branch(), KIND, d250, req.amount(), p.id(), Timestamp.valueOf(LocalDateTime.now(clock)));
        return Map.of("status", "PENDING");
    }

    /** Waiver requests waiting for a decision, for accountants and administrators other than the one who asked. */
    @GetMapping("/pending")
    List<Map<String, Object>> pending(Authentication auth) {
        Person p = who(auth);
        return jdbc.queryForList("""
                SELECT o.id, o.description, o.amount, o.requested_at, COALESCE(NULLIF(CONCAT_WS(' ', a.AccessFName, a.AccessLName), ''), a.AccessUserName) AS requested_by
                FROM ecis_fo_override o JOIN access a ON a.AccessID = o.requested_by
                WHERE o.branch_code = ? AND o.kind = ? AND o.status = 'PENDING' AND o.requested_by <> ? ORDER BY o.id
                """, p.branch(), KIND, p.id());
    }

    @PostMapping("/{id}/decide")
    Map<String, Object> decide(@PathVariable long id, @RequestBody Decision d, Authentication auth) {
        Person p = who(auth);
        List<Object[]> row = jdbc.query("SELECT requested_by, status, amount FROM ecis_fo_override WHERE id = ? AND branch_code = ? AND kind = ?",
            (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getBigDecimal(3)}, id, p.branch(), KIND);
        if (row.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such request.");
        if ((Integer) row.get(0)[0] == p.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot decide your own request. Ask another accountant or administrator.");
        String note = d.note() == null ? null : d.note().trim();
        if (!d.approve() && (note == null || note.length() < 3)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason when rejecting.");
        int n = jdbc.update("UPDATE ecis_fo_override SET status = ?, decided_by = ?, decided_at = ?, note = ? WHERE id = ? AND status = 'PENDING'",
            d.approve() ? "APPROVED" : "REJECTED", p.id(), Timestamp.valueOf(LocalDateTime.now(clock)), note, id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This request has already been decided.");
        AuditLog.write(jdbc, auth.getName(), d.approve() ? "fo.override.approve" : "fo.override.reject", "fo_override", String.valueOf(id), null, KIND + " " + row.get(0)[2], note);
        return Map.of("status", d.approve() ? "APPROVED" : "REJECTED");
    }

    @PostMapping("/post")
    Posted post(@RequestBody Request req, Authentication auth) {
        Person p = who(auth);
        if (!db.canInsertInto("transsum", "transdt", "gltrans", "sldtl"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The app cannot post to the ledger yet.");
        return tx.execute((st) -> {
            List<String> problems = problems(p, req);
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
            String d = description(req);
            String d250 = d.substring(0, Math.min(d.length(), 250));
            List<long[]> found = jdbc.query("""
                    SELECT id, decided_by FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ?
                      AND status = 'APPROVED' AND used_ref IS NULL ORDER BY id LIMIT 1 FOR UPDATE
                    """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, p.branch(), KIND, p.id(), req.amount(), d250);
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Another accountant or administrator has to approve this waiver first.");
            long approver = found.get(0)[1];
            BigDecimal amount = req.amount().setScale(2, java.math.RoundingMode.HALF_UP);
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            LocalDate day = businessDay.postingDate(p.branch());
            int year = day.getYear();
            PostingPeriods.requireOpen(jdbc, p.branch(), day);
            long ctl = db.nextControlNumber(p.branch(), 1, 1, year);
            String docNo = SharePaymentRules.documentNo(null, ctl);
            String explanation = "PENALTY WAIVED " + req.reason().trim();
            Timestamp stamp = Timestamp.valueOf(now);
            jdbc.update("""
                    INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_CLIENTID, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                    VALUES (?, ?, 1, 1, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """, p.branch(), year, ctl, docNo, req.clientId(), day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), day.format(DateTimeFormatter.BASIC_ISO_DATE),
                explanation.substring(0, Math.min(explanation.length(), 200)), stamp);
            String loanAccount = poster.glAccount(p.branch(), CLASS_LOANS, req.typeCode(), WAIVED, 99);
            String sql = """
                    INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSLC_CODE, DTSLT_CODE, DTREF_NO, DTSLE_CODE, DTCTS_NUM,
                      DTSL_DATE, DTAMT, DTAMTDUE, DTENCDBY, DTAPPRVBY, DTPOSTBY, DTUPD_TAG, DTREFERENCE, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                    VALUES (?, ?, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 1, '', 0, 0, 0)
                    """;
            // the expense account is debited with no sub-ledger; the loan is credited as event 42
            jdbc.update(sql, p.branch(), year, ctl, 0, p.dept(), req.expenseAccount(), null, null, null, null, null, day.toString(), amount, p.id(), approver, p.id());
            jdbc.update(sql, p.branch(), year, ctl, 1, p.dept(), loanAccount, CLASS_LOANS, req.typeCode(), req.refNo(), WAIVED, 99, day.toString(), amount.negate(), p.id(), approver, p.id());
            poster.writeGlLine(p.branch(), req.expenseAccount(), day, 1, p.dept(), amount, stamp);
            poster.writeGlLine(p.branch(), loanAccount, day, 1, p.dept(), amount.negate(), stamp);
            poster.writeLedgerRow(p.branch(), req.clientId(), new MemberLine(CLASS_LOANS, req.typeCode(), req.refNo(), WAIVED, 99, loanAccount, amount.negate()), day, 1, ctl, docNo, null, null);
            String ref = p.branch() + "/" + year + "/1/1/" + ctl;
            jdbc.update("UPDATE ecis_fo_override SET used_ref = ? WHERE id = ?", ref, found.get(0)[0]);
            AuditLog.write(jdbc, auth.getName(), "loan.penalty.waive", "transsum", ref, null, "loan " + req.typeCode() + "/" + req.refNo() + " client " + req.clientId() + " waived " + amount.toPlainString() + " to " + req.expenseAccount()
                    + ", approved by user " + approver, req.reason().trim());
            return new Posted(year, ctl, docNo, amount);
        });
    }
}
