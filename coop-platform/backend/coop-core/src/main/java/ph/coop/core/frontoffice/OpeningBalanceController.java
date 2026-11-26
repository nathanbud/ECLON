package ph.coop.core.frontoffice;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.conflict;
import static ph.coop.core.web.ApiErrors.forbidden;
import static ph.coop.core.web.ApiErrors.notFound;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.accounting.ControlAccounts;
import ph.coop.core.accounting.ledger.Ledger;
import ph.coop.core.accounting.ledger.Money;
import ph.coop.core.accounting.ledger.Voucher;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.MemberLedgerPoster.Master;
import ph.coop.core.member.SpreadsheetReader;

/**
 * Opening balances: a new cooperative's balances on the day it starts on this system (Accounting > Opening balances). A batch holds lines on general ledger
 * accounts and on members' savings and share capital accounts; it goes DRAFT, FOR_REVIEW, APPROVED, POSTED like a memo, and the maker, approver and poster are
 * three different people. Posting writes one General Journal through the {@link Ledger}: each ledger line as it is, and each member line on the control account
 * of its account type with the member's ledger row, so the members' totals and the control accounts agree from the first day. A control account is never typed
 * as a ledger line. A member account takes an opening balance only while it has no entries. Accountants and administrators; auditors read. Rules that need no
 * database are in {@link OpeningBalanceRules}.
 */
@RestController
@RequestMapping("/api/opening-balances")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class OpeningBalanceController {

    /**
     * One line. A loan or time deposit line also gives its terms (see {@link OpeningBalanceRules.Terms}): a loan its status, rate, months still to run, next due
     * date and, for the record, the amount and date granted; a time deposit its rate, term in days, placement date and accrued interest.
     */
    record LineIn(String kind, Integer clientId, Integer typeCode, Integer refNo, String accountCode, BigDecimal debit, BigDecimal credit, String note,
                  Integer status, BigDecimal rate, Integer terms, String startDate, BigDecimal originalAmount, String grantedOn, BigDecimal accrued) {
        LineIn(String kind, Integer clientId, Integer typeCode, Integer refNo, String accountCode, BigDecimal debit, BigDecimal credit, String note) {
            this(kind, clientId, typeCode, refNo, accountCode, debit, credit, note, null, null, null, null, null, null, null);
        }

        static LineIn of(OpeningBalanceRules.Line l) {
            OpeningBalanceRules.Terms t = l.terms();
            return t == null ? new LineIn(l.kind(), l.clientId(), l.typeCode(), l.refNo(), l.accountCode(), l.debit(), l.credit(), l.note())
                : new LineIn(l.kind(), l.clientId(), l.typeCode(), l.refNo(), l.accountCode(), l.debit(), l.credit(), l.note(), t.status(), t.rate(), t.terms(),
                    t.start() == null ? null : t.start().toString(), t.originalAmount(), t.grantedOn() == null ? null : t.grantedOn().toString(), t.accrued());
        }
    }

    record BatchIn(int branchCode, String openingDate, String description, List<LineIn> lines) {}

    record Reason(String reason) {}

    record Summary(long id, int branchCode, String reference, LocalDate openingDate, String description, String status, String createdBy, String approvedBy, String postedBy,
                   int lines, BigDecimal debits, BigDecimal credits, String documentNo) {}

    /** A line as shown: for a loan or time deposit also its terms ({@code line}), the maturity, and the account its accrued interest goes to. */
    record LineOut(int lineNo, String kind, Integer clientId, String memberName, Integer typeCode, String typeName, Integer refNo, String accountNo, String accountCode,
                   String accountTitle, BigDecimal debit, BigDecimal credit, String note, LineIn line, LocalDate maturity, String accrualAccount) {}

    record TrialOut(String account, String title, BigDecimal debit, BigDecimal credit, int memberAccounts, boolean control) {}

    record Detail(Summary summary, List<LineOut> lines, List<TrialOut> trialBalance, List<String> problems, String cancelReason) {}

    record ImportOut(List<LineIn> lines, List<String> problems) {}

    private record Row(long id, int branch, String reference, LocalDate date, String description, String status, String createdBy, String approvedBy) {}

    private record Person(int id, int dept) {}

    /** A member line as found in the database: the account it is on, and the control account it posts to. */
    private record Found(int refNo, int status, String typeName, String accountNo, String memberName, String control, String accrualControl) {
        Found(int refNo, int status, String typeName, String accountNo, String memberName, String control) {
            this(refNo, status, typeName, accountNo, memberName, control, null);
        }
    }

    /** What checking the lines against the database gave: the problems, and for each member line what it was found to be (by line index). */
    private record Checked(List<String> problems, Map<Integer, Found> found) {}

    private static final int CLOSED = 17;
    private static final int PRINCIPAL = 11;
    private static final String SUMMARY = """
            SELECT b.id, b.branch_code, b.reference, b.opening_date, b.description, b.status, b.created_by, b.approved_by, b.posted_by,
                   (SELECT COUNT(*) FROM ecis_opening_line l WHERE l.batch_id = b.id),
                   (SELECT COALESCE(SUM(l.debit), 0) FROM ecis_opening_line l WHERE l.batch_id = b.id),
                   (SELECT COALESCE(SUM(l.credit), 0) FROM ecis_opening_line l WHERE l.batch_id = b.id), b.tr_ctlno
            FROM ecis_opening_batch b
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final MemberLedgerPoster poster;
    private final ControlAccounts controls;
    private final Ledger ledger;
    private final OpeningAccounts accounts;
    private final Clock clock;

    OpeningBalanceController(JdbcTemplate jdbc, Db db, MemberLedgerPoster poster, ControlAccounts controls, Ledger ledger, OpeningAccounts accounts, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.poster = poster;
        this.controls = controls;
        this.ledger = ledger;
        this.accounts = accounts;
        this.clock = clock;
    }

    private void requireSetUp() {
        if (!db.tableExists("ecis_opening_batch")) throw conflict(DatabaseChanges.notSetUp("opening balances"));
        if (!db.columnExists("ecis_opening_line", "start_date")) throw conflict(DatabaseChanges.notSetUp("opening balances of loans and time deposits"));
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    List<Summary> list(@RequestParam int branchCode) {
        requireSetUp();
        return jdbc.query(SUMMARY + " WHERE b.branch_code = ? ORDER BY b.id DESC", OpeningBalanceController::summary, branchCode);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    Detail get(@PathVariable long id) {
        requireSetUp();
        Row r = row(id, false);
        List<OpeningBalanceRules.Line> lines = lines(id);
        Checked c = r.status().equals("POSTED") || r.status().equals("CANCELLED") ? found(r.branch(), lines) : check(r.branch(), id, lines);
        List<String> problems = new ArrayList<>();
        if (!r.status().equals("POSTED") && !r.status().equals("CANCELLED")) {
            problems.addAll(OpeningBalanceRules.problems(lines, r.description(), r.date(), LocalDate.now(clock)));
            problems.addAll(c.problems());
        }
        Map<String, String> titles = new HashMap<>();
        List<LineOut> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OpeningBalanceRules.Line l = lines.get(i);
            Found f = c.found().get(i);
            String code = l.member() ? (f == null ? null : f.control()) : l.accountCode();
            Integer ref = f == null || f.refNo() == 0 ? l.refNo() : Integer.valueOf(f.refNo());
            LocalDate maturity = null;
            if (OpeningBalanceRules.TD.equals(l.kind()) && l.terms() != null && l.terms().start() != null && l.terms().terms() != null) maturity = OpeningBalanceRules.maturity(l.terms());
            out.add(new LineOut(i + 1, l.kind(), l.clientId(), f == null ? null : f.memberName(), l.typeCode(), f == null ? null : f.typeName(),
                ref, f == null ? null : f.accountNo(), code, code == null ? null : title(r.branch(), code, titles),
                l.debit(), l.credit(), l.note(), l.opens() ? LineIn.of(l) : null, maturity, f == null ? null : f.accrualControl()));
        }
        List<OpeningBalanceRules.Posting> postings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OpeningBalanceRules.Line l = lines.get(i);
            Found f = c.found().get(i);
            String account = l.member() ? (f == null ? null : f.control()) : l.accountCode() == null ? null : l.accountCode().trim();
            if (account != null) postings.add(new OpeningBalanceRules.Posting(account, OpeningBalanceRules.amount(l.debit()), OpeningBalanceRules.amount(l.credit()), l.member()));
            if (f != null && f.accrualControl() != null && l.accrued().signum() > 0) postings.add(new OpeningBalanceRules.Posting(f.accrualControl(), BigDecimal.ZERO, l.accrued(), true));
        }
        List<TrialOut> trial = OpeningBalanceRules.trialBalance(postings).stream()
            .map((t) -> new TrialOut(t.account(), title(r.branch(), t.account(), titles), t.debit(), t.credit(), t.memberAccounts(), controls.isControl(r.branch(), t.account()))).toList();
        String why = first(jdbc.queryForList("SELECT cancel_reason FROM ecis_opening_batch WHERE id = ?", String.class, id));
        return new Detail(summaryOf(id), out, trial, problems, why);
    }

    /** A loan or time deposit type a line can open, with what the screen fills in from it: the yearly rate and, for a loan, its term in months and payment mode. */
    record OpeningType(String kind, int typeCode, String name, BigDecimal rate, Integer termMonths, String paymentMode) {}

    /** The loan and time deposit types that are switched on, for choosing on a loan or time deposit line. */
    @GetMapping("/types")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    List<OpeningType> types(@RequestParam int branchCode) {
        return jdbc.query("""
                SELECT t.SLTypeSLC_CODE, t.SLTypeSLT_CODE, t.SLTypeM_DESC, t.SLTypeINT_RATE, t.SLTypeTERMS, t.SLTypeTERM_PERD, p.PaymentModeDesc
                FROM sltype t LEFT JOIN paymentmode p ON p.PaymentModeID = t.SLTypePPMT_MODE
                WHERE t.SLTypeBR_CODE = ? AND t.SLTypeSLC_CODE IN (12, 24) AND COALESCE(t.SLTypeIsDisabled, 0) = 0
                ORDER BY t.SLTypeSLC_CODE, t.SLTypeSLT_CODE
                """, (rs, i) -> {
                boolean loan = rs.getInt(1) == 12;
                int terms = rs.getInt(5);
                Integer months = !loan || rs.wasNull() || terms <= 0 ? null : rs.getInt(6) == 7 ? terms * 12 : terms;
                String name = rs.getString(3);
                String mode = rs.getString(7);
                return new OpeningType(loan ? OpeningBalanceRules.LOAN : OpeningBalanceRules.TD, rs.getInt(2), name == null ? null : name.trim(), rs.getBigDecimal(4), months,
                    loan && mode != null ? mode.trim() : null);
            }, branchCode);
    }

    /**
     * Reads the lines of a CSV or Excel file (see {@link OpeningBalanceRules#read}) without saving anything; the screen puts them into a draft, where they are
     * checked against the database.
     */
    @PostMapping("/import")
    ImportOut importFile(@RequestParam("file") MultipartFile file) throws IOException {
        List<SpreadsheetReader.Row> rows;
        try {
            rows = SpreadsheetReader.read(file.getOriginalFilename(), file.getBytes());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage() + " Use a CSV or Excel (.xlsx) file whose first row names the columns Kind, Member, Type, Account no, Ledger account, Debit, Credit and Note"
                + " (and for loans and time deposits their terms, as in Download template).");
        }
        OpeningBalanceRules.Imported in = OpeningBalanceRules.read(rows);
        return new ImportOut(in.lines().stream().map(LineIn::of).toList(), in.problems());
    }

    // ---- steps --------------------------------------------------------------------------------------------------

    /** Saves a new draft. The lines are checked in full when the draft is sent for review; a draft may be saved while it still has problems. */
    @PostMapping
    @Transactional
    Summary create(@RequestBody BatchIn in, Authentication auth) {
        requireSetUp();
        LocalDate date = parse(in.openingDate());
        List<OpeningBalanceRules.Line> lines = draftable(in, date);
        LocalDateTime now = LocalDateTime.now(clock);
        long id = db.insertReturningId("INSERT INTO ecis_opening_batch (branch_code, opening_date, description, status, created_by, created_at) VALUES (?, ?, ?, 'DRAFT', ?, ?)", "id",
            in.branchCode(), java.sql.Date.valueOf(date), in.description().trim(), auth.getName(), Timestamp.valueOf(now));
        jdbc.update("UPDATE ecis_opening_batch SET reference = ? WHERE id = ?", "OB-" + date.getYear() + "-" + String.format("%04d", id), id);
        insertLines(id, lines);
        audit(auth, "opening.balance.create", id, null, "DRAFT", lines.size() + " lines");
        return summaryOf(id);
    }

    /** Replaces a draft's date, description and lines. Only its maker (or an administrator) changes it, and only while it is a draft. */
    @PutMapping("/{id}")
    @Transactional
    Summary update(@PathVariable long id, @RequestBody BatchIn in, Authentication auth) {
        requireSetUp();
        Row r = row(id, true);
        require(r, "DRAFT");
        if (!r.createdBy().equalsIgnoreCase(auth.getName()) && !admin(auth)) throw forbidden("Only the person who prepared these opening balances, or an administrator, changes them. Ask them, or prepare a batch of your own.");
        LocalDate date = parse(in.openingDate());
        List<OpeningBalanceRules.Line> lines = draftable(in, date);
        jdbc.update("UPDATE ecis_opening_batch SET opening_date = ?, description = ? WHERE id = ? AND status = 'DRAFT'", java.sql.Date.valueOf(date), in.description().trim(), id);
        jdbc.update("DELETE FROM ecis_opening_line WHERE batch_id = ?", id);
        insertLines(id, lines);
        audit(auth, "opening.balance.update", id, "DRAFT", "DRAFT", lines.size() + " lines");
        return summaryOf(id);
    }

    @PostMapping("/{id}/submit")
    @Transactional
    Summary submit(@PathVariable long id, Authentication auth) {
        requireSetUp();
        Row r = row(id, true);
        require(r, "DRAFT");
        if (!r.createdBy().equalsIgnoreCase(auth.getName()) && !admin(auth)) throw forbidden("Only the person who prepared these opening balances, or an administrator, sends them for review.");
        requireNoProblems(r, "sent for review");
        int n = jdbc.update("UPDATE ecis_opening_batch SET status = 'FOR_REVIEW', submitted_at = ? WHERE id = ? AND status = 'DRAFT'", Timestamp.valueOf(LocalDateTime.now(clock)), id);
        if (n != 1) throw conflict("The batch changed while you were working on it. Reload it and try again.");
        audit(auth, "opening.balance.submit", id, "DRAFT", "FOR_REVIEW", null);
        return summaryOf(id);
    }

    @PostMapping("/{id}/approve")
    @Transactional
    Summary approve(@PathVariable long id, Authentication auth) {
        requireSetUp();
        Row r = row(id, true);
        require(r, "FOR_REVIEW");
        String problem = OpeningBalanceRules.separation(r.createdBy(), auth.getName(), null);
        if (problem != null) throw forbidden(problem);
        requireNoProblems(r, "approved");
        jdbc.update("UPDATE ecis_opening_batch SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ? AND status = 'FOR_REVIEW'", auth.getName(),
            Timestamp.valueOf(LocalDateTime.now(clock)), id);
        audit(auth, "opening.balance.approve", id, "FOR_REVIEW", "APPROVED", null);
        return summaryOf(id);
    }

    /** Sends an approved batch back to its maker as a draft, with the reason (a reviewer who finds a mistake). */
    @PostMapping("/{id}/return")
    @Transactional
    Summary sendBack(@PathVariable long id, @RequestBody Reason body, Authentication auth) {
        requireSetUp();
        String why = reason(body, "sending the opening balances back");
        Row r = row(id, true);
        if (!List.of("FOR_REVIEW", "APPROVED").contains(r.status())) throw conflict("Only opening balances waiting for review or posting can be sent back; these are " + words(r.status()) + ".");
        if (r.createdBy().equalsIgnoreCase(auth.getName())) throw forbidden("You prepared these opening balances. To change them, ask the reviewer to send them back, or cancel them and prepare them again.");
        jdbc.update("UPDATE ecis_opening_batch SET status = 'DRAFT', approved_by = NULL, approved_at = NULL, submitted_at = NULL WHERE id = ? AND status = ?", id, r.status());
        audit(auth, "opening.balance.return", id, r.status(), "DRAFT", why);
        return summaryOf(id);
    }

    /** Posts an approved batch as one General Journal through the ledger, in this one transaction, so a failure leaves nothing behind. */
    @PostMapping("/{id}/post")
    @Transactional
    Summary post(@PathVariable long id, Authentication auth) {
        requireSetUp();
        Row r = row(id, true);
        require(r, "APPROVED");
        String problem = OpeningBalanceRules.separation(r.createdBy(), r.approvedBy(), auth.getName());
        if (problem != null) throw forbidden(problem);
        Person postedBy = legacyPerson(auth.getName(), "person posting");
        Person creator = legacyPerson(r.createdBy(), "person who prepared them");
        Person approver = legacyPerson(r.approvedBy(), "approver");
        List<OpeningBalanceRules.Line> lines = lines(id);
        // lock each member account so a teller cannot post into it while its opening balance is written, then check everything as it is now
        for (OpeningBalanceRules.Line l : lines) if (l.member() && !l.opens() && l.refNo() != null) lock(r.branch(), l);
        requireNoProblems(r, "posted");
        Checked c = check(r.branch(), id, lines);

        List<Voucher.Line> out = new ArrayList<>();
        int loans = 0, deposits = 0;
        for (int i = 0; i < lines.size(); i++) {
            OpeningBalanceRules.Line l = lines.get(i);
            Money amount = Money.of(l.signed());
            if (l.opens()) {
                // open the loan or certificate, keep its number on the line, then post its balance (and a certificate's accrued interest) on it
                Found f = c.found().get(i);
                int ref = accounts.open(r.branch(), l, r.date(), r.reference());
                jdbc.update("UPDATE ecis_opening_line SET ref_no = ? WHERE batch_id = ? AND line_no = ?", ref, id, i + 1);
                int slc = OpeningBalanceRules.CLASS.get(l.kind());
                out.add(new Voucher.Line(f.control(), creator.dept(), amount, new Voucher.Member(l.clientId(), slc, l.typeCode(), ref, PRINCIPAL, f.status())));
                if (l.accrued().signum() > 0) {
                    out.add(new Voucher.Line(f.accrualControl(), creator.dept(), Money.of(l.accrued().negate()),
                        new Voucher.Member(l.clientId(), slc, l.typeCode(), ref, OpeningBalanceRules.ACCRUED_EVENT, OpeningBalanceRules.ACCRUED_STATUS)));
                }
                if (slc == 12) loans++;
                else deposits++;
            } else if (l.member()) {
                Found f = c.found().get(i);
                int slc = OpeningBalanceRules.CLASS.get(l.kind());
                out.add(new Voucher.Line(f.control(), creator.dept(), amount, new Voucher.Member(l.clientId(), slc, l.typeCode(), f.refNo(), PRINCIPAL, f.status())));
            } else {
                out.add(new Voucher.Line(l.accountCode().trim(), creator.dept(), amount));
            }
        }
        Ledger.Posted p = ledger.write(new Voucher("opening-balance-" + id, auth.getName(), r.branch(), 1, 1, r.date(), r.description(), Voucher.Style.GENERAL_JOURNAL,
            new Voucher.People(creator.id(), approver.id(), postedBy.id()), out, List.of()));
        int n = jdbc.update("UPDATE ecis_opening_batch SET status = 'POSTED', posted_by = ?, posted_at = ?, tr_year = ?, tr_ctlno = ? WHERE id = ? AND status = 'APPROVED'",
            auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock)), p.year(), p.controlNo(), id);
        if (n != 1) throw conflict("The batch changed while you were posting it. Nothing was posted; reload it and try again.");
        audit(auth, "opening.balance.post", id, "APPROVED", "POSTED", "General Journal " + p.documentNo() + ", " + lines.size() + " lines"
            + (loans > 0 ? ", " + loans + " loan" + (loans == 1 ? "" : "s") + " opened" : "") + (deposits > 0 ? ", " + deposits + " time deposit" + (deposits == 1 ? "" : "s") + " opened" : ""));
        return summaryOf(id);
    }

    /** Withdraws a batch that has not been posted. Its maker or an administrator may; the reason is kept. A posted batch is corrected by a memo or journal instead. */
    @PostMapping("/{id}/cancel")
    @Transactional
    Summary cancel(@PathVariable long id, @RequestBody Reason body, Authentication auth) {
        requireSetUp();
        String why = reason(body, "cancelling the opening balances");
        Row r = row(id, true);
        if (r.status().equals("POSTED")) throw conflict("These opening balances are posted and cannot be cancelled. Correct a member's balance with a debit/credit memo, and a ledger balance with a journal.");
        if (r.status().equals("CANCELLED")) throw conflict("These opening balances are already cancelled. Prepare a new batch if the balances are still to be entered.");
        if (!r.createdBy().equalsIgnoreCase(auth.getName()) && !admin(auth)) throw forbidden("Only the person who prepared these opening balances, or an administrator, cancels them.");
        jdbc.update("UPDATE ecis_opening_batch SET status = 'CANCELLED', cancel_reason = ? WHERE id = ? AND status = ?", why.substring(0, Math.min(why.length(), 200)), id, r.status());
        audit(auth, "opening.balance.cancel", id, r.status(), "CANCELLED", why);
        return summaryOf(id);
    }

    // ---- checks -------------------------------------------------------------------------------------------------

    /** The structural checks a draft must pass to be saved at all: a date, a description, a sensible number of lines and lines of a known kind. */
    private static List<OpeningBalanceRules.Line> draftable(BatchIn in, LocalDate date) {
        if (date == null) throw bad("Give the opening date: the day the balances are as of. Nothing was saved.");
        String d = in.description() == null ? "" : in.description().trim();
        if (d.length() < OpeningBalanceRules.DESCRIPTION_MIN || d.length() > OpeningBalanceRules.DESCRIPTION_MAX)
            throw bad("Describe the opening balances in " + OpeningBalanceRules.DESCRIPTION_MIN + " to " + OpeningBalanceRules.DESCRIPTION_MAX + " characters, for example \"Opening balances as of 1 January 2027\". Nothing was saved.");
        List<LineIn> raw = in.lines() == null ? List.of() : in.lines();
        if (raw.size() > OpeningBalanceRules.MAX_LINES) throw bad("A batch can have at most " + OpeningBalanceRules.MAX_LINES + " lines. Split the members over two batches. Nothing was saved.");
        List<OpeningBalanceRules.Line> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            LineIn l = raw.get(i);
            String kind = OpeningBalanceRules.kind(l == null ? null : l.kind());
            if (kind == null) throw bad("Line " + (i + 1) + " does not say whether it is a ledger account, savings, share capital, a loan or a time deposit. Choose one, then save again. Nothing was saved.");
            String account = l.accountCode() == null || l.accountCode().isBlank() ? null : l.accountCode().trim();
            String note = l.note() == null || l.note().isBlank() ? null : l.note().trim();
            if (note != null && note.length() > 100) note = note.substring(0, 100);
            OpeningBalanceRules.Terms terms = null;
            if (OpeningBalanceRules.LOAN.equals(kind) || OpeningBalanceRules.TD.equals(kind)) {
                terms = new OpeningBalanceRules.Terms(l.status(), l.rate(), l.terms(), lineDate(l.startDate(), i, OpeningBalanceRules.LOAN.equals(kind) ? "next due date" : "placement date"),
                    l.originalAmount(), lineDate(l.grantedOn(), i, "date granted"), l.accrued());
            }
            out.add(new OpeningBalanceRules.Line(kind, l.clientId(), l.typeCode(), l.refNo(), account, l.debit(), l.credit(), note, terms));
        }
        return out;
    }

    private void requireNoProblems(Row r, String what) {
        List<OpeningBalanceRules.Line> lines = lines(r.id());
        List<String> problems = new ArrayList<>(OpeningBalanceRules.problems(lines, r.description(), r.date(), LocalDate.now(clock)));
        if (problems.isEmpty()) problems.addAll(check(r.branch(), r.id(), lines).problems());
        if (!problems.isEmpty()) {
            List<String> shown = problems.size() > 10 ? problems.subList(0, 10) : problems;
            throw conflict("The opening balances cannot be " + what + " yet, so nothing was changed. " + String.join(" ", shown)
                + (problems.size() > 10 ? " And " + (problems.size() - 10) + " more: open the batch to see them all." : ""));
        }
    }

    /**
     * What the database says about the lines: each ledger account exists, takes postings and is not a control account; each member account exists (found by its
     * number, or as the member's only account of that type), is not closed, has no entries yet, is not in another batch and has a control account to post to.
     */
    private Checked check(int branch, long batchId, List<OpeningBalanceRules.Line> lines) {
        List<String> out = new ArrayList<>();
        Map<Integer, Found> found = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            OpeningBalanceRules.Line l = lines.get(i);
            String at = "Line " + (i + 1) + ": ";
            if (!l.member()) {
                if (l.accountCode() == null) continue;
                String code = l.accountCode().trim();
                List<Map<String, Object>> a = jdbc.queryForList("SELECT COAacct_tag, COALESCE(COAisdisabled, 0) AS off FROM coa WHERE COABR_CODE = ? AND COAID = ?", branch, code);
                if (a.isEmpty()) out.add(at + "account " + code + " is not in the chart of accounts. Check the number, or add the account in Chart of Accounts first.");
                else if ("S".equals(a.get(0).get("COAacct_tag"))) out.add(at + "account " + code + " is a heading (summary account) and takes no postings. Use the detail accounts under it.");
                else if (((Number) a.get(0).get("off")).intValue() != 0) out.add(at + "account " + code + " is switched off. Switch it on in Chart of Accounts, or use another account.");
                else if (controls.isControl(branch, code)) out.add(at + "account " + code + " is the control account of members' accounts, so its balance comes from the members' lines."
                    + " Enter each member's balance instead (or leave the members for another batch), and take this line out.");
                continue;
            }
            if (l.clientId() == null || l.typeCode() == null) continue;
            if (l.opens()) {
                OpeningAccounts.Found o = accounts.check(branch, l, null, at, out);
                if (o != null) found.put(i, new Found(0, o.status(), o.typeName(), "new", o.memberName(), o.control(), o.accrualControl()));
                continue;
            }
            Master m = OpeningBalanceRules.SHARE.equals(l.kind()) ? Master.SHARE_CAPITAL : Master.SAVINGS;
            String what = m.slc() == 31 ? "share capital" : "savings";
            String name = names.computeIfAbsent(String.valueOf(l.clientId()), (k) -> {
                try {
                    return poster.client(branch, l.clientId()).name();
                } catch (ResponseStatusException e) {
                    return null;
                }
            });
            if (name == null) {
                out.add(at + "member " + l.clientId() + " is not in this branch. Check the member number, or register the member first.");
                continue;
            }
            String p = m.prefix();
            List<Object[]> accounts = jdbc.query("SELECT s." + p + "REF_NO, s." + p + "STATUS, s." + p + "REF_NOX, t.SLTypeM_DESC FROM " + m.table() + " s LEFT JOIN sltype t ON t.SLTypeBR_CODE = s."
                    + p + "BR_CODE AND t.SLTypeSLC_CODE = s." + p + "SLC_CODE AND t.SLTypeSLT_CODE = s." + p + "SLT_CODE WHERE s." + p + "BR_CODE = ? AND s.ClientID" + p + " = ? AND s." + p
                    + "SLC_CODE = ? AND s." + p + "SLT_CODE = ? AND (CAST(? AS CHAR) IS NULL OR s." + p + "REF_NO = ?) ORDER BY s." + p + "REF_NO",
                (rs, n) -> new Object[] {rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4)}, branch, l.clientId(), m.slc(), l.typeCode(), l.refNo(), l.refNo());
            if (accounts.isEmpty()) {
                out.add(at + name + " (member " + l.clientId() + ") has no " + what + " account of type " + l.typeCode() + (l.refNo() == null ? "" : " numbered " + l.refNo())
                    + ". Open it first (Settings > Products > Account opening opens accounts when members are registered), or correct the type.");
                continue;
            }
            if (accounts.size() > 1) {
                out.add(at + name + " (member " + l.clientId() + ") has " + accounts.size() + " " + what + " accounts of type " + l.typeCode() + ". Give the account number of the one this balance is for.");
                continue;
            }
            Object[] a = accounts.get(0);
            int ref = (Integer) a[0], status = (Integer) a[1];
            String typeName = a[3] == null ? what : ((String) a[3]).trim();
            String accountNo = a[2] == null ? String.valueOf(ref) : ((String) a[2]).trim();
            String control = null;
            try {
                control = poster.glAccount(branch, m.slc(), l.typeCode(), PRINCIPAL, status);
            } catch (ResponseStatusException e) {
                out.add(at + e.getReason());
            }
            found.put(i, new Found(ref, status, typeName, accountNo, name, control));
            if (status == CLOSED) out.add(at + name + "'s " + typeName + " " + accountNo + " is closed. Reactivate the member's account first, or take the line out.");
            Integer entries = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ?", Integer.class,
                branch, l.clientId(), m.slc(), l.typeCode(), ref);
            if (entries != null && entries > 0) out.add(at + name + "'s " + typeName + " " + accountNo + " already has " + entries + " entr" + (entries == 1 ? "y" : "ies")
                + ", and an opening balance is an account's first entry. Take the line out, and adjust the account with a debit/credit memo instead.");
            Integer elsewhere = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ecis_opening_line l JOIN ecis_opening_batch b ON b.id = l.batch_id
                    WHERE b.branch_code = ? AND b.id <> ? AND b.status IN ('DRAFT', 'FOR_REVIEW', 'APPROVED') AND l.kind = ? AND l.client_id = ? AND l.type_code = ?
                      AND (l.ref_no IS NULL OR l.ref_no = ?)
                    """, Integer.class, branch, batchId, l.kind(), l.clientId(), l.typeCode(), ref);
            if (elsewhere != null && elsewhere > 0) out.add(at + name + "'s " + typeName + " " + accountNo + " is also in another batch of opening balances that is not posted. Keep it in one batch only.");
        }
        return new Checked(out, found);
    }

    /** The member accounts of a posted or cancelled batch, for showing it; nothing is checked. */
    private Checked found(int branch, List<OpeningBalanceRules.Line> lines) {
        Map<Integer, Found> found = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            OpeningBalanceRules.Line l = lines.get(i);
            if (!l.member() || l.clientId() == null || l.typeCode() == null) continue;
            if (l.opens()) {
                OpeningAccounts.Found o = accounts.describe(branch, l);
                found.put(i, new Found(l.refNo() == null ? 0 : l.refNo(), o.status(), o.typeName(), l.refNo() == null ? "not opened" : String.valueOf(l.refNo()), o.memberName(), o.control(), o.accrualControl()));
                continue;
            }
            if (l.refNo() == null) continue;
            Master m =OpeningBalanceRules.SHARE.equals(l.kind()) ? Master.SHARE_CAPITAL : Master.SAVINGS;
            String name = null, control = null, typeName = null, accountNo = String.valueOf(l.refNo());
            int status = 0;
            try {
                name = poster.client(branch, l.clientId()).name();
                MemberLedgerPoster.Account a = poster.account(m, branch, l.clientId(), l.typeCode(), l.refNo());
                status = a.status();
                typeName = a.type();
                accountNo = a.refText() == null ? accountNo : a.refText().trim();
                control = poster.glAccount(branch, m.slc(), l.typeCode(), PRINCIPAL, status);
            } catch (ResponseStatusException e) {
                // shown without it
            }
            found.put(i, new Found(l.refNo(), status, typeName, accountNo, name, control));
        }
        return new Checked(List.of(), found);
    }

    private void lock(int branch, OpeningBalanceRules.Line l) {
        Master m = OpeningBalanceRules.SHARE.equals(l.kind()) ? Master.SHARE_CAPITAL : Master.SAVINGS;
        String p = m.prefix();
        jdbc.queryForList("SELECT 1 FROM " + m.table() + " WHERE " + p + "BR_CODE = ? AND ClientID" + p + " = ? AND " + p + "SLC_CODE = ? AND " + p + "SLT_CODE = ? AND " + p + "REF_NO = ? FOR UPDATE",
            branch, l.clientId(), m.slc(), l.typeCode(), l.refNo());
    }

    private Person legacyPerson(String username, String role) {
        List<Person> p = jdbc.query("SELECT AccessID, COALESCE(AccessDepartment, 0) FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
            (rs, i) -> new Person(rs.getInt(1), rs.getInt(2)), username);
        if (p.isEmpty()) throw conflict("The " + role + " (" + username + ") is not an enabled user, so the opening balances cannot be posted. An administrator switches the account on in Settings > Users.");
        return p.get(0);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void insertLines(long id, List<OpeningBalanceRules.Line> lines) {
        List<Object[]> rows = new ArrayList<>();
        int no = 1;
        for (OpeningBalanceRules.Line l : lines) {
            OpeningBalanceRules.Terms t = l.terms();
            rows.add(new Object[] {id, no++, l.kind(), l.clientId(), l.typeCode(), l.refNo(), l.accountCode(), OpeningBalanceRules.amount(l.debit()), OpeningBalanceRules.amount(l.credit()), l.note(),
                t == null ? null : t.status(), t == null ? null : t.rate(), t == null ? null : t.terms(), t == null || t.start() == null ? null : java.sql.Date.valueOf(t.start()),
                t == null ? null : t.originalAmount(), t == null || t.grantedOn() == null ? null : java.sql.Date.valueOf(t.grantedOn()), t == null ? null : t.accrued()});
        }
        jdbc.batchUpdate("""
                INSERT INTO ecis_opening_line (batch_id, line_no, kind, client_id, type_code, ref_no, account_code, debit, credit, note,
                  status_code, rate, terms, start_date, original_amount, granted_on, accrued) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    /**
     * The lines of a batch. A member line saved without its account number gets the number found for it, once that is the member's only account of the type, so
     * that the posting and the locks use the exact account.
     */
    private List<OpeningBalanceRules.Line> lines(long id) {
        return jdbc.query("""
                SELECT kind, client_id, type_code, ref_no, account_code, debit, credit, note, status_code, rate, terms, start_date, original_amount, granted_on, accrued
                FROM ecis_opening_line WHERE batch_id = ? ORDER BY line_no
                """, (rs, i) -> {
                String kind = rs.getString(1);
                OpeningBalanceRules.Terms terms = null;
                if (OpeningBalanceRules.LOAN.equals(kind) || OpeningBalanceRules.TD.equals(kind)) {
                    java.sql.Date start = rs.getDate(12), granted = rs.getDate(14);
                    terms = new OpeningBalanceRules.Terms(integer(rs, 9), rs.getBigDecimal(10), integer(rs, 11), start == null ? null : start.toLocalDate(), rs.getBigDecimal(13),
                        granted == null ? null : granted.toLocalDate(), rs.getBigDecimal(15));
                }
                return new OpeningBalanceRules.Line(kind, integer(rs, 2), integer(rs, 3), integer(rs, 4), rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8), terms);
            }, id);
    }

    private Row row(long id, boolean lock) {
        List<Row> rows = jdbc.query("SELECT id, branch_code, reference, opening_date, description, status, created_by, approved_by FROM ecis_opening_batch WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> new Row(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), id);
        if (rows.isEmpty()) throw notFound("Those opening balances were not found. They may be in another database: reload the list and open them from there.");
        return rows.get(0);
    }

    private Summary summaryOf(long id) {
        return jdbc.query(SUMMARY + " WHERE b.id = ?", OpeningBalanceController::summary, id).get(0);
    }

    private static Summary summary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Long ctl = rs.getObject(13) == null ? null : rs.getLong(13);
        return new Summary(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
            rs.getInt(10), rs.getBigDecimal(11), rs.getBigDecimal(12), ctl == null ? null : String.format("*%07d", ctl));
    }

    private String title(int branch, String code, Map<String, String> titles) {
        return titles.computeIfAbsent(code, (k) -> {
            String t = first(jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, branch, k));
            return t == null ? "" : t.trim();
        });
    }

    private void require(Row r, String expected) {
        if (!r.status().equals(expected)) throw conflict("These opening balances are " + words(r.status()) + ", not " + words(expected) + ". Reload them to see where they stand.");
    }

    private static String words(String status) {
        return status.toLowerCase().replace("for_review", "waiting for review").replace('_', ' ');
    }

    private static String reason(Reason body, String what) {
        String why = body == null || body.reason() == null ? "" : body.reason().trim();
        if (why.length() < 3) throw bad("Give the reason for " + what + ", so the audit trail shows why. Nothing was changed.");
        return why;
    }

    private static <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    private static Integer integer(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static LocalDate lineDate(String s, int index, String what) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw bad("Line " + (index + 1) + ": the " + what + " must look like 2027-01-15. Nothing was saved.");
        }
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw bad("The opening date must look like 2027-01-01. Nothing was saved.");
        }
    }

    private void audit(Authentication auth, String action, long id, String prev, String next, String reason) {
        AuditLog.write(jdbc, auth.getName(), action, "ecis_opening_batch", String.valueOf(id), prev, next, reason);
    }
}
