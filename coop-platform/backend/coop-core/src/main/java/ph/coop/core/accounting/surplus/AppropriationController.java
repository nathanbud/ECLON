package ph.coop.core.accounting.surplus;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.conflict;
import static ph.coop.core.web.ApiErrors.forbidden;
import static ph.coop.core.web.ApiErrors.notFound;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
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
import ph.coop.core.accounting.ControlAccounts;
import ph.coop.core.accounting.close.PostingPeriods;
import ph.coop.core.accounting.ledger.Ledger;
import ph.coop.core.accounting.ledger.Money;
import ph.coop.core.accounting.ledger.Voucher;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;
import ph.coop.core.deposit.DividendRun;

/**
 * Appropriation of the net surplus (Accounting > Dividends > Appropriation; RA 9520 Article 86). Once a year is closed, an accountant prepares how its net surplus
 * (what the closing credited to the undivided net surplus account) is set aside to the statutory funds and allocated to interest on share capital and patronage
 * refund, as the general assembly resolved; another person approves it and a third posts it as one General Journal through the {@link Ledger}: a debit of the
 * undivided net surplus and a credit to each fund's account and to the account the dividend vouchers pay members from. The dividend distribution of that year
 * then may not pay more than was appropriated ({@link Appropriations#distributionProblem}). Rules without the database are in {@link AppropriationRules}.
 */
@RestController
@RequestMapping("/api/appropriations")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class AppropriationController {

    record LineIn(String kind, String name, BigDecimal percent, BigDecimal amount, String account) {}

    record AppropriationIn(int branchCode, int year, String entryDate, String resolution, List<LineIn> lines) {}

    record Reason(String reason) {}

    record SettingsIn(String registeredOn, String reason) {}

    record Settings(LocalDate registeredOn, String sourceAccount, String sourceTitle, String payoutAccount, String payoutTitle, boolean mayEdit) {}

    record LineOut(int lineNo, String kind, String name, BigDecimal percent, BigDecimal amount, String account, String accountTitle) {}

    record EntryOut(String account, String title, BigDecimal debit, BigDecimal credit) {}

    record Summary(long id, int branchCode, int year, String reference, LocalDate entryDate, String resolution, BigDecimal netSurplus, String status, String createdBy,
                   String approvedBy, String postedBy, BigDecimal allocated, String documentNo) {}

    record Detail(Summary summary, List<LineOut> lines, AppropriationRules.Totals totals, Map<String, BigDecimal> percents, BigDecimal reserveMinimum,
                  List<EntryOut> entry, List<String> problems, String cancelReason) {}

    /** Where a year stands before an appropriation is prepared: its net surplus (null while open), why one cannot be prepared, and lines to start from. */
    record Start(int year, BigDecimal netSurplus, BigDecimal reserveMinimum, List<String> problems, List<LineIn> lines, Settings settings, Summary existing) {}

    private record Row(long id, int branch, int year, String reference, LocalDate date, String resolution, BigDecimal netSurplus, String status, String createdBy, String approvedBy) {}

    private record Person(int id, int dept) {}

    private static final String SUMMARY = """
            SELECT a.id, a.branch_code, a.year, a.reference, a.entry_date, a.resolution, a.net_surplus, a.status, a.created_by, a.approved_by, a.posted_by,
                   (SELECT COALESCE(SUM(l.amount), 0) FROM ecis_appropriation_line l WHERE l.appropriation_id = a.id), a.tr_ctlno
            FROM ecis_appropriation a
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Appropriations appropriations;
    private final ControlAccounts controls;
    private final Ledger ledger;
    private final Clock clock;

    AppropriationController(JdbcTemplate jdbc, Db db, Appropriations appropriations, ControlAccounts controls, Ledger ledger, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.appropriations = appropriations;
        this.controls = controls;
        this.ledger = ledger;
        this.clock = clock;
    }

    private void requireSetUp() {
        if (!appropriations.stored()) throw conflict(DatabaseChanges.notSetUp("the appropriation of the net surplus"));
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private String payout() {
        return DividendRun.FUND_ACCOUNT;
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    List<Summary> list(@RequestParam int branchCode) {
        requireSetUp();
        return jdbc.query(SUMMARY + " WHERE a.branch_code = ? ORDER BY a.year DESC, a.id DESC", AppropriationController::summary, branchCode);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    Settings settings(@RequestParam int branchCode, Authentication auth) {
        Map<String, String> titles = new HashMap<>();
        String src = appropriations.surplusAccount();
        return new Settings(appropriations.registeredOn(), src, title(branchCode, src, titles), payout(), title(branchCode, payout(), titles), admin(auth));
    }

    /** Records the date the cooperative was registered (the first five years need a reserve fund of at least half the net surplus). Administrators, with a reason. */
    @PutMapping("/settings")
    @Transactional
    Settings putSettings(@RequestParam int branchCode, @RequestBody SettingsIn in, Authentication auth) {
        if (!admin(auth)) throw forbidden("Only an administrator records the cooperative's registration date. Ask one to set it here.");
        String why = reason(in == null ? null : new Reason(in.reason()), "changing the registration date");
        LocalDate on = in.registeredOn() == null || in.registeredOn().isBlank() ? null : parse(in.registeredOn(), "registration date");
        if (on != null && on.isAfter(LocalDate.now(clock))) throw bad("The registration date " + on + " has not come yet. Give the date on the CDA certificate of registration. Nothing was saved.");
        LocalDate before = appropriations.registeredOn();
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("DELETE FROM ecis_setting WHERE setting_key = ?", Appropriations.REGISTERED_ON);
        if (on != null) jdbc.update("INSERT INTO ecis_setting (setting_key, setting_value, updated_by, updated_at) VALUES (?, ?, ?, ?)", Appropriations.REGISTERED_ON, on.toString(), auth.getName(), Timestamp.valueOf(now));
        AuditLog.write(jdbc, auth.getName(), "accounting.appropriation.registered", "ecis_setting", Appropriations.REGISTERED_ON, before == null ? null : before.toString(), on == null ? null : on.toString(), why);
        return settings(branchCode, auth);
    }

    /** Where a year stands, and the lines to start a new appropriation from: the last posted one's funds, else the statutory funds of the dividend settings. */
    @GetMapping("/start")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    Start start(@RequestParam int branchCode, @RequestParam int year, Authentication auth) {
        requireSetUp();
        BigDecimal net = appropriations.closedSurplus(branchCode, year);
        List<String> problems = new ArrayList<>();
        if (net == null) problems.add("The books for " + year + " are not closed yet. Close the year in Accounting > Year-end close first; the net surplus to appropriate is what the closing puts in the undivided net surplus.");
        else if (net.signum() <= 0) problems.add("The closing of " + year + " shows " + (net.signum() == 0 ? "no net surplus" : "a net loss of " + net.negate().toPlainString()) + ", so there is nothing to appropriate.");
        List<Summary> live = jdbc.query(SUMMARY + " WHERE a.branch_code = ? AND a.year = ? AND a.status <> 'CANCELLED' ORDER BY a.id DESC", AppropriationController::summary, branchCode, year);
        if (!live.isEmpty()) problems.add("The " + year + " appropriation " + live.get(0).reference() + " is already " + words(live.get(0).status()) + ". Open it from the list; cancel it first to prepare the year again.");
        return new Start(year, net, AppropriationRules.reserveMinimum(year, appropriations.registeredOn()), problems, startingLines(branchCode), settings(branchCode, auth), live.isEmpty() ? null : live.get(0));
    }

    private List<LineIn> startingLines(int branch) {
        List<LineIn> out = new ArrayList<>();
        List<Long> last = jdbc.queryForList("SELECT id FROM ecis_appropriation WHERE branch_code = ? AND status = 'POSTED' ORDER BY year DESC, id DESC", Long.class, branch);
        if (!last.isEmpty()) {
            jdbc.query("SELECT kind, name, percent, amount, account_code FROM ecis_appropriation_line WHERE appropriation_id = ? ORDER BY line_no", (rs) -> {
                String kind = rs.getString(1);
                boolean fund = AppropriationRules.FUNDS.contains(kind);
                out.add(new LineIn(kind, rs.getString(2), fund ? rs.getBigDecimal(3) : null, fund ? null : BigDecimal.ZERO, rs.getString(5)));
            }, last.get(0));
            return out;
        }
        if (db.tableExists("ecis_distribution_fund")) {
            jdbc.query("SELECT name, percent, account FROM ecis_distribution_fund WHERE branch_code = ? AND active = 1 ORDER BY position, id",
                (rs) -> { out.add(new LineIn(AppropriationRules.kindFor(rs.getString(1)), rs.getString(1), rs.getBigDecimal(2), null, rs.getString(3))); }, branch);
        }
        out.add(new LineIn(AppropriationRules.ISC, "Interest on share capital", null, BigDecimal.ZERO, payout()));
        out.add(new LineIn(AppropriationRules.PR, "Patronage refund", null, BigDecimal.ZERO, payout()));
        return out;
    }

    record PostedOut(Appropriations.Posted posted) {}

    /** A year's posted appropriation, for the dividend distribution to start from (null inside when none is posted). */
    @GetMapping("/posted")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    PostedOut posted(@RequestParam int branchCode, @RequestParam int year) {
        return new PostedOut(appropriations.posted(branchCode, year));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN','AUDITOR')")
    Detail get(@PathVariable long id) {
        requireSetUp();
        Row r = row(id, false);
        List<AppropriationRules.Line> lines = lines(id);
        boolean open = !r.status().equals("POSTED") && !r.status().equals("CANCELLED");
        List<String> problems = open ? problems(r, lines) : List.of();
        Map<String, String> titles = new HashMap<>();
        List<LineOut> out = new ArrayList<>();
        List<AppropriationRules.Line> priced = AppropriationRules.priced(lines, r.netSurplus());
        for (int i = 0; i < priced.size(); i++) {
            AppropriationRules.Line l = priced.get(i);
            out.add(new LineOut(i + 1, l.kind(), l.name(), l.percent(), l.amount(), l.account(), l.account() == null ? null : title(r.branch(), l.account(), titles)));
        }
        List<EntryOut> entry = AppropriationRules.entries(r.netSurplus(), lines, appropriations.surplusAccount()).stream()
            .map((e) -> new EntryOut(e.account(), title(r.branch(), e.account(), titles), e.amount().signum() > 0 ? e.amount() : BigDecimal.ZERO, e.amount().signum() < 0 ? e.amount().negate() : BigDecimal.ZERO))
            .toList();
        String why = first(jdbc.queryForList("SELECT cancel_reason FROM ecis_appropriation WHERE id = ?", String.class, id));
        return new Detail(summaryOf(id), out, AppropriationRules.totals(r.netSurplus(), lines), AppropriationRules.percents(lines),
            AppropriationRules.reserveMinimum(r.year(), appropriations.registeredOn()), entry, problems, why);
    }

    // ---- steps --------------------------------------------------------------------------------------------------

    /** Saves a new draft for a closed year with a net surplus. A draft may be saved while it still has problems; it is sent on only without any. */
    @PostMapping
    @Transactional
    Summary create(@RequestBody AppropriationIn in, Authentication auth) {
        requireSetUp();
        if (in == null || in.year() < 1900) throw bad("Choose the year whose net surplus is appropriated. Nothing was saved.");
        BigDecimal net = appropriations.closedSurplus(in.branchCode(), in.year());
        if (net == null || net.signum() <= 0) throw conflict(AppropriationRules.problems(in.year(), net, List.of(), null, null, null, null, null, null).get(0) + " Nothing was saved.");
        LocalDate date = parse(in.entryDate(), "date of the entry");
        List<AppropriationRules.Line> lines = draftable(in);
        long id;
        try {
            id = db.insertReturningId("INSERT INTO ecis_appropriation (branch_code, year, entry_date, resolution, net_surplus, status, created_by, created_at) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?)", "id",
                in.branchCode(), in.year(), java.sql.Date.valueOf(date), resolution(in.resolution()), net, auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock)));
        } catch (DuplicateKeyException e) {
            throw conflict("An appropriation of " + in.year() + " is already being prepared or is posted. Open it from the list; nothing was saved.");
        }
        // MySQL keeps no partial unique index: the one live appropriation a year is checked here as well
        Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_appropriation WHERE branch_code = ? AND year = ? AND status <> 'CANCELLED'", Integer.class, in.branchCode(), in.year());
        if (live != null && live > 1) throw conflict("An appropriation of " + in.year() + " is already being prepared or is posted. Open it from the list; nothing was saved.");
        jdbc.update("UPDATE ecis_appropriation SET reference = ? WHERE id = ?", "NS-" + in.year() + "-" + String.format("%04d", id), id);
        insertLines(id, lines);
        audit(auth, "accounting.appropriation.create", id, null, "DRAFT", in.year() + ", net surplus " + net.toPlainString() + ", " + lines.size() + " lines");
        return summaryOf(id);
    }

    /** Replaces a draft's date, resolution and lines; its maker or an administrator, only while it is a draft. The net surplus is read from the books again. */
    @PutMapping("/{id}")
    @Transactional
    Summary update(@PathVariable long id, @RequestBody AppropriationIn in, Authentication auth) {
        requireSetUp();
        Row r = row(id, true);
        require(r, "DRAFT");
        mayChange(r, auth, "changes it");
        LocalDate date = parse(in.entryDate(), "date of the entry");
        List<AppropriationRules.Line> lines = draftable(in);
        BigDecimal net = appropriations.closedSurplus(r.branch(), r.year());
        jdbc.update("UPDATE ecis_appropriation SET entry_date = ?, resolution = ?, net_surplus = ? WHERE id = ? AND status = 'DRAFT'", java.sql.Date.valueOf(date), resolution(in.resolution()),
            net == null ? r.netSurplus() : net, id);
        jdbc.update("DELETE FROM ecis_appropriation_line WHERE appropriation_id = ?", id);
        insertLines(id, lines);
        audit(auth, "accounting.appropriation.update", id, "DRAFT", "DRAFT", lines.size() + " lines");
        return summaryOf(id);
    }

    @PostMapping("/{id}/submit")
    @Transactional
    Summary submit(@PathVariable long id, Authentication auth) {
        requireSetUp();
        Row r = row(id, true);
        require(r, "DRAFT");
        mayChange(r, auth, "sends it for review");
        requireNoProblems(r, "sent for review");
        jdbc.update("UPDATE ecis_appropriation SET status = 'FOR_REVIEW', submitted_at = ? WHERE id = ? AND status = 'DRAFT'", Timestamp.valueOf(LocalDateTime.now(clock)), id);
        audit(auth, "accounting.appropriation.submit", id, "DRAFT", "FOR_REVIEW", null);
        return summaryOf(id);
    }

    @PostMapping("/{id}/approve")
    @Transactional
    Summary approve(@PathVariable long id, Authentication auth) {
        requireSetUp();
        Row r = row(id, true);
        require(r, "FOR_REVIEW");
        String problem = AppropriationRules.separation(r.createdBy(), auth.getName(), null);
        if (problem != null) throw forbidden(problem);
        requireNoProblems(r, "approved");
        jdbc.update("UPDATE ecis_appropriation SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ? AND status = 'FOR_REVIEW'", auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock)), id);
        audit(auth, "accounting.appropriation.approve", id, "FOR_REVIEW", "APPROVED", null);
        return summaryOf(id);
    }

    /** Sends an appropriation waiting for review or posting back to its maker as a draft, with the reason. */
    @PostMapping("/{id}/return")
    @Transactional
    Summary sendBack(@PathVariable long id, @RequestBody Reason body, Authentication auth) {
        requireSetUp();
        String why = reason(body, "sending the appropriation back");
        Row r = row(id, true);
        if (!List.of("FOR_REVIEW", "APPROVED").contains(r.status())) throw conflict("Only an appropriation waiting for review or posting can be sent back; this one is " + words(r.status()) + ".");
        if (r.createdBy().equalsIgnoreCase(auth.getName())) throw forbidden("You prepared this appropriation. To change it, ask the reviewer to send it back, or cancel it and prepare it again.");
        jdbc.update("UPDATE ecis_appropriation SET status = 'DRAFT', approved_by = NULL, approved_at = NULL, submitted_at = NULL WHERE id = ? AND status = ?", id, r.status());
        audit(auth, "accounting.appropriation.return", id, r.status(), "DRAFT", why);
        return summaryOf(id);
    }

    /** Posts an approved appropriation as one General Journal through the ledger, after checking it again against the books as they are now. */
    @PostMapping("/{id}/post")
    @Transactional
    Summary post(@PathVariable long id, Authentication auth) {
        requireSetUp();
        Row r = row(id, true);
        require(r, "APPROVED");
        String problem = AppropriationRules.separation(r.createdBy(), r.approvedBy(), auth.getName());
        if (problem != null) throw forbidden(problem);
        BigDecimal net = appropriations.closedSurplus(r.branch(), r.year());
        if (net == null || net.compareTo(r.netSurplus()) != 0) {
            throw conflict("The net surplus of " + r.year() + " in the books (" + (net == null ? "the year is no longer closed" : net.toPlainString()) + ") is not the "
                + r.netSurplus().toPlainString() + " this appropriation was approved for, so nothing was posted. Send it back to its maker, who saves it again with the books' figure.");
        }
        requireNoProblems(r, "posted");
        Person creator = legacyPerson(r.createdBy(), "person who prepared it");
        Person approver = legacyPerson(r.approvedBy(), "approver");
        Person poster = legacyPerson(auth.getName(), "person posting");
        List<AppropriationRules.Line> lines = lines(id);
        List<Voucher.Line> out = AppropriationRules.entries(r.netSurplus(), lines, appropriations.surplusAccount()).stream()
            .map((e) -> new Voucher.Line(e.account(), creator.dept(), Money.of(e.amount()))).toList();
        if (out.isEmpty()) throw conflict("The appropriation sets nothing aside, so there is nothing to post. Send it back to its maker to add the funds and allocations.");
        String text = "Appropriation of the " + r.year() + " net surplus per " + r.resolution();
        Ledger.Posted p = ledger.write(new Voucher("appropriation-" + id, auth.getName(), r.branch(), 1, 1, r.date(), text.substring(0, Math.min(text.length(), 250)),
            Voucher.Style.GENERAL_JOURNAL, new Voucher.People(creator.id(), approver.id(), poster.id()), out, List.of()));
        int n = jdbc.update("UPDATE ecis_appropriation SET status = 'POSTED', posted_by = ?, posted_at = ?, tr_year = ?, tr_ctlno = ? WHERE id = ? AND status = 'APPROVED'",
            auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock)), p.year(), p.controlNo(), id);
        if (n != 1) throw conflict("The appropriation changed while you were posting it. Nothing was posted; reload it and try again.");
        audit(auth, "accounting.appropriation.post", id, "APPROVED", "POSTED", "General Journal " + p.documentNo() + ", " + out.size() + " lines");
        return summaryOf(id);
    }

    /** Withdraws an appropriation that is not posted; its maker or an administrator, with a reason. A posted one is corrected by a journal. */
    @PostMapping("/{id}/cancel")
    @Transactional
    Summary cancel(@PathVariable long id, @RequestBody Reason body, Authentication auth) {
        requireSetUp();
        String why = reason(body, "cancelling the appropriation");
        Row r = row(id, true);
        if (r.status().equals("POSTED")) throw conflict("This appropriation is posted and cannot be cancelled. Correct it with a journal that moves the amounts between the funds, approved by the general assembly.");
        if (r.status().equals("CANCELLED")) throw conflict("This appropriation is already cancelled. Prepare the year again if it is still to be appropriated.");
        mayChange(r, auth, "cancels it");
        jdbc.update("UPDATE ecis_appropriation SET status = 'CANCELLED', cancel_reason = ? WHERE id = ? AND status = ?", why.substring(0, Math.min(why.length(), 200)), id, r.status());
        audit(auth, "accounting.appropriation.cancel", id, r.status(), "CANCELLED", why);
        return summaryOf(id);
    }

    // ---- checks -------------------------------------------------------------------------------------------------

    private List<String> problems(Row r, List<AppropriationRules.Line> lines) {
        List<String> p = new ArrayList<>(AppropriationRules.problems(r.year(), r.netSurplus(), lines, r.resolution(), r.date(), LocalDate.now(clock), appropriations.registeredOn(),
            appropriations.surplusAccount(), payout()));
        p.addAll(accountProblems(r.branch(), lines));
        if (r.date() != null) {
            String period = PostingPeriods.problem(jdbc, r.branch(), r.date());
            if (period != null) p.add(period);
        }
        return p;
    }

    /** Each account exists, takes postings, is switched on, is not a members' control account and is not an income or expense account. */
    private List<String> accountProblems(int branch, List<AppropriationRules.Line> lines) {
        List<String> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (AppropriationRules.Line l : lines) if (l.account() != null && !l.account().isBlank() && !seen.contains(l.account().trim())) seen.add(l.account().trim());
        seen.add(0, appropriations.surplusAccount());
        for (String code : seen) {
            List<Map<String, Object>> a = jdbc.queryForList("SELECT COAacct_tag, COALESCE(COAisdisabled, 0) AS off FROM coa WHERE COABR_CODE = ? AND COAID = ?", branch, code);
            boolean source = code.equals(appropriations.surplusAccount());
            String which = source ? "The undivided net surplus account " + code : "Account " + code;
            if (a.isEmpty()) out.add(which + " is not in the chart of accounts. Check the number, or add the account in Chart of Accounts first.");
            else if ("S".equals(a.get(0).get("COAacct_tag"))) out.add(which + " is a heading (summary account) and takes no postings. Use a detail account under it.");
            else if (((Number) a.get(0).get("off")).intValue() != 0) out.add(which + " is switched off. Switch it on in Chart of Accounts, or use another account.");
            else if (controls.isControl(branch, code)) out.add(which + " is the control account of members' accounts. Credit the fund's own account instead.");
            else if (!source && (code.startsWith("4") || code.startsWith("5"))) out.add(which + " is an income or expense account. A fund is equity or a liability: choose its own account.");
        }
        return out;
    }

    private void requireNoProblems(Row r, String what) {
        List<String> problems = problems(r, lines(r.id()));
        if (!problems.isEmpty()) {
            List<String> shown = problems.size() > 8 ? problems.subList(0, 8) : problems;
            throw conflict("The appropriation cannot be " + what + " yet, so nothing was changed. " + String.join(" ", shown) + (problems.size() > 8 ? " Open it to see the rest." : ""));
        }
    }

    private List<AppropriationRules.Line> draftable(AppropriationIn in) {
        List<LineIn> raw = in.lines() == null ? List.of() : in.lines();
        if (raw.size() > AppropriationRules.MAX_LINES) throw bad("An appropriation has at most " + AppropriationRules.MAX_LINES + " lines. Join funds that go to the same account. Nothing was saved.");
        List<AppropriationRules.Line> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            LineIn l = raw.get(i);
            String kind = l == null || l.kind() == null ? null : l.kind().trim().toUpperCase(java.util.Locale.ROOT);
            if (kind == null || !AppropriationRules.KINDS.contains(kind)) throw bad("Line " + (i + 1) + " does not say whether it is a statutory fund, interest on share capital or patronage refund. Choose one, then save again. Nothing was saved.");
            String name = l.name() == null ? "" : l.name().trim();
            if (name.length() > 80) name = name.substring(0, 80);
            boolean fund = AppropriationRules.FUNDS.contains(kind);
            String account = fund ? (l.account() == null || l.account().isBlank() ? null : l.account().trim()) : payout();
            out.add(new AppropriationRules.Line(kind, name, fund ? l.percent() : null, fund ? null : l.amount(), account));
        }
        return out;
    }

    private Person legacyPerson(String username, String role) {
        List<Person> p = jdbc.query("SELECT AccessID, COALESCE(AccessDepartment, 0) FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
            (rs, i) -> new Person(rs.getInt(1), rs.getInt(2)), username);
        if (p.isEmpty()) throw conflict("The " + role + " (" + username + ") is not an enabled user, so the appropriation cannot be posted. An administrator switches the account on in Settings > Users.");
        return p.get(0);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void insertLines(long id, List<AppropriationRules.Line> lines) {
        BigDecimal net = jdbc.queryForObject("SELECT net_surplus FROM ecis_appropriation WHERE id = ?", BigDecimal.class, id);
        List<Object[]> rows = new ArrayList<>();
        int no = 1;
        for (AppropriationRules.Line l : AppropriationRules.priced(lines, net)) {
            String name = l.name() == null || l.name().isBlank() ? kindName(l.kind()) : l.name();
            rows.add(new Object[] {id, no++, l.kind(), name, l.percent(), l.amount() == null ? BigDecimal.ZERO : l.amount().setScale(2, java.math.RoundingMode.HALF_UP), l.account()});
        }
        jdbc.batchUpdate("INSERT INTO ecis_appropriation_line (appropriation_id, line_no, kind, name, percent, amount, account_code) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case AppropriationRules.RESERVE -> "Reserve fund";
            case AppropriationRules.EDUCATION -> "Education and training fund";
            case AppropriationRules.COMMUNITY -> "Community development fund";
            case AppropriationRules.OPTIONAL -> "Optional fund";
            case AppropriationRules.ISC -> "Interest on share capital";
            case AppropriationRules.PR -> "Patronage refund";
            default -> "Other fund";
        };
    }

    /** The lines as stored; a fund's amount is worked out again from its percent and the net surplus. */
    private List<AppropriationRules.Line> lines(long id) {
        return jdbc.query("SELECT kind, name, percent, amount, account_code FROM ecis_appropriation_line WHERE appropriation_id = ? ORDER BY line_no", (rs, i) -> {
            String kind = rs.getString(1);
            boolean fund = AppropriationRules.FUNDS.contains(kind);
            return new AppropriationRules.Line(kind, rs.getString(2), rs.getBigDecimal(3), fund ? null : rs.getBigDecimal(4), rs.getString(5));
        }, id);
    }

    private Row row(long id, boolean lock) {
        List<Row> rows = jdbc.query("SELECT id, branch_code, year, reference, entry_date, resolution, net_surplus, status, created_by, approved_by FROM ecis_appropriation WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> new Row(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getDate(5).toLocalDate(), rs.getString(6), rs.getBigDecimal(7), rs.getString(8), rs.getString(9), rs.getString(10)), id);
        if (rows.isEmpty()) throw notFound("That appropriation was not found. It may be in another database: reload the list and open it from there.");
        return rows.get(0);
    }

    private Summary summaryOf(long id) {
        return jdbc.query(SUMMARY + " WHERE a.id = ?", AppropriationController::summary, id).get(0);
    }

    private static Summary summary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Long ctl = rs.getObject(13) == null ? null : rs.getLong(13);
        return new Summary(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getDate(5).toLocalDate(), rs.getString(6), rs.getBigDecimal(7), rs.getString(8), rs.getString(9),
            rs.getString(10), rs.getString(11), rs.getBigDecimal(12), ctl == null ? null : String.format("*%07d", ctl));
    }

    private String title(int branch, String code, Map<String, String> titles) {
        if (code == null) return null;
        return titles.computeIfAbsent(code, (k) -> {
            String t = first(jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, branch, k));
            return t == null ? "" : t.trim();
        });
    }

    private void mayChange(Row r, Authentication auth, String what) {
        if (!r.createdBy().equalsIgnoreCase(auth.getName()) && !admin(auth)) throw forbidden("Only the person who prepared this appropriation, or an administrator, " + what + ". Ask them.");
    }

    private void require(Row r, String expected) {
        if (!r.status().equals(expected)) throw conflict("This appropriation is " + words(r.status()) + ", not " + words(expected) + ". Reload it to see where it stands.");
    }

    private void audit(Authentication auth, String action, long id, String before, String after, String reason) {
        AuditLog.write(jdbc, auth.getName(), action, "ecis_appropriation", String.valueOf(id), before, after, reason);
    }

    private static String resolution(String r) {
        String t = r == null ? "" : r.trim();
        return t.length() > AppropriationRules.RESOLUTION_MAX ? t.substring(0, AppropriationRules.RESOLUTION_MAX) : t;
    }

    private static LocalDate parse(String text, String what) {
        if (text == null || text.isBlank()) throw bad("Give the " + what + ", such as 2027-03-15. Nothing was saved.");
        try {
            return LocalDate.parse(text.trim());
        } catch (RuntimeException e) {
            throw bad("The " + what + " \"" + text + "\" is not a date. Write it as year-month-day, such as 2027-03-15. Nothing was saved.");
        }
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
}
