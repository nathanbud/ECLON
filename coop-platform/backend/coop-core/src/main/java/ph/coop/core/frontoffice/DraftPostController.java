package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.accounting.ledger.Ledger;
import ph.coop.core.accounting.ledger.Money;
import ph.coop.core.accounting.ledger.RequestKeys;
import ph.coop.core.accounting.ledger.WaitingVoucher;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.DraftPostRules.In;
import ph.coop.core.frontoffice.DraftPostRules.Kind;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.HandoverRules.Drawer;
import ph.coop.core.frontoffice.MemberLedgerPoster.Direction;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.member.KycGate;

/**
 * Posts a front office draft (documentation/general-voucher-map.md). An other receipt (Cash Receipt, type 10) and a mixed payment (Misc Disbursement,
 * type 24) are posted at once on the poster's drawer, through the ledger's cash writer; a Cash Voucher (21) or a Check Voucher (22) is encoded to wait
 * for another person's approval ({@link GeneralVoucherController}). The draft is deleted once it has become a voucher. Every check is done again when
 * posting, inside the transaction.
 */
@RestController
@RequestMapping("/api/front-office/drafts")
class DraftPostController {

    private static final int PRINCIPAL = 11, ACTIVE = 15;

    /**
     * The cooperative's own cheque that pays a Check Voucher: the bank (BCID) and its ledger account it is drawn on (a bank can have a current and a
     * savings account), its number, date and payee.
     */
    record OwnChequeIn(Integer bankId, String account, String number, LocalDate date, String payee) {}

    /** cheques: cheques taken on an other receipt; cheque: the cooperative's cheque on a Check Voucher. */
    record PostIn(List<ChequeRules.Input> cheques, OwnChequeIn cheque, String receiptNo, Boolean confirmDuplicate) {}

    /** A line as it will be written: the account, its words, and + debit / - credit. */
    record PlanLine(String account, String description, BigDecimal amount) {}

    /** What posting would do: the kind, the lines (with the cash or payable or bank line the window adds), the problems, what a supervisor must allow. */
    record Plan(String kind, String kindWords, String postsHow, List<PlanLine> lines, BigDecimal amount, BigDecimal drawerCash, List<String> problems,
                List<SavingsPostingController.NeedStatus> needs, boolean ready) {}

    record Posted(String kind, int year, int code, long controlNo, String documentNo, String date, BigDecimal amount, String status) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final DrawerBalances balances;
    private final BankCashController banks;
    private final Ledger ledger;
    private final KycGate kyc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    DraftPostController(JdbcTemplate jdbc, Db db, Clock clock, FrontOfficeAccess access, MemberLedgerPoster poster, DrawerBalances balances, BankCashController banks,
                        Ledger ledger, KycGate kyc, ObjectMapper json, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.access = access;
        this.poster = poster;
        this.balances = balances;
        this.banks = banks;
        this.ledger = ledger;
        this.kyc = kyc;
        this.json = json;
        this.tx = new TransactionTemplate(txm);
    }

    @PostMapping("/{id}/check")
    Plan check(@PathVariable long id, @RequestBody(required = false) PostIn in, Authentication auth) {
        return evaluate(id, in == null ? new PostIn(null, null, null, null) : in, auth, false).plan();
    }

    @PostMapping("/{id}/post")
    Posted post(@PathVariable long id, @RequestBody(required = false) PostIn body, Authentication auth) {
        PostIn in = body == null ? new PostIn(null, null, null, null) : body;
        return tx.execute((st) -> {
            Evaluated e = evaluate(id, in, auth, true);
            Plan plan = e.plan();
            if (!plan.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", plan.problems()));
            Person p = e.person();
            DraftRules.Payload d = e.draft();
            Posted out = switch (DraftPostRules.Kind.valueOf(plan.kind())) {
                case OTHER_RECEIPT -> receipt(e, in, auth);
                case MIXED_PAYMENT -> payment(e, in, auth);
                case CASH_VOUCHER, CHECK_VOUCHER -> encode(e, in, auth);
            };
            jdbc.update("DELETE FROM ecis_fo_draft WHERE id = ? AND created_by = ?", id, auth.getName());
            AuditLog.write(jdbc, auth.getName(), "fo.draft.post", "ecis_fo_draft", String.valueOf(id), null,
                plan.kind() + " " + p.branch() + "/" + out.year() + "/" + out.code() + "/" + out.controlNo() + " " + out.amount().toPlainString() + " " + out.status()
                    + (d.clientId() == null ? " payor " + d.clientName() : " client " + d.clientId()), null);
            return out;
        });
    }

    // ---- posting ------------------------------------------------------------------------------------------------------

    /** An other receipt: the plain credit lines, the cash (and any cheques) on the poster's drawer. */
    private Posted receipt(Evaluated e, PostIn in, Authentication auth) {
        DraftRules.Payload d = e.draft();
        List<MemberLine> lines = e.lines().stream().map((l) -> new MemberLine(0, 0, 0, 0, 0, l.account(), l.amount())).toList();
        List<ChequeRules.Cheque> cheques = poster.cheques(in.cheques(), Boolean.TRUE.equals(in.confirmDuplicate()));
        int client = d.clientId() == null ? 0 : d.clientId();
        MemberLedgerPoster.Posted r = poster.postVoucher(Direction.IN, e.person(), client, client == 0 ? d.clientName() : null, explanation(d, "OTHER RECEIPT"),
            in.receiptNo(), Boolean.TRUE.equals(in.confirmDuplicate()), lines, "fo.receipt.post", auth.getName(),
            "other receipt " + e.plan().amount().toPlainString() + (cheques.isEmpty() ? "" : " cheques " + MemberLedgerPoster.describe(cheques)), cheques);
        return new Posted(Kind.OTHER_RECEIPT.name(), r.year(), DraftPostRules.RECEIPT, r.controlNo(), r.documentNo(), r.date().toString(), e.plan().amount(), "POSTED");
    }

    /** A mixed payment: payable and plain debits, the cash out of the poster's drawer; a supervisor's approval for an amount over the limit is spent here. */
    private Posted payment(Evaluated e, PostIn in, Authentication auth) {
        DraftRules.Payload d = e.draft();
        Person p = e.person();
        BigDecimal amount = e.plan().amount();
        List<long[]> spent = new ArrayList<>();
        for (SavingsPostingController.NeedStatus n : e.plan().needs()) {
            List<long[]> found = jdbc.query("""
                    SELECT id, decided_by FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ?
                      AND status = 'APPROVED' AND used_ref IS NULL ORDER BY id LIMIT 1 FOR UPDATE
                    """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, p.branch(), n.kind(), p.id(), amount, n.description());
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A supervisor has to allow this first: " + n.title() + ".");
            spent.add(found.get(0));
        }
        List<MemberLine> lines = new ArrayList<>();
        for (In l : e.lines()) {
            if (l.member()) {
                int status = payableStatus(p.branch(), d.clientId(), l.slt(), l.refNo(), false);
                lines.add(new MemberLine(l.slc(), l.slt(), l.refNo(), PRINCIPAL, status, l.account(), l.amount()));
            } else {
                lines.add(new MemberLine(0, 0, 0, 0, 0, l.account(), l.amount()));
            }
        }
        int client = d.clientId() == null ? 0 : d.clientId();
        MemberLedgerPoster.Posted r = poster.postVoucher(Direction.PAYABLE_OUT, p, client, client == 0 ? d.clientName() : null, explanation(d, "MISC DISBURSEMENT"),
            in.receiptNo(), true, lines, "fo.payment.post", auth.getName(), "mixed payment " + amount.toPlainString(), List.of());
        String ref = r.branch() + "/" + r.year() + "/2/24/" + r.controlNo();
        for (long[] o : spent) {
            jdbc.update("UPDATE ecis_fo_override SET used_ref = ? WHERE id = ?", ref, o[0]);
            AuditLog.write(jdbc, auth.getName(), "fo.payment.override.used", "fo_override", String.valueOf(o[0]), null, "allowed by user " + o[1] + " for " + ref + " amount " + amount.toPlainString(), null);
        }
        return new Posted(Kind.MIXED_PAYMENT.name(), r.year(), DraftPostRules.MISC_DISBURSEMENT, r.controlNo(), r.documentNo(), r.date().toString(), amount, "POSTED");
    }

    /** A Cash Voucher or a Check Voucher: written to wait for another person's approval; a Cash Voucher opens a new vouchers-payable account for the payee. */
    private Posted encode(Evaluated e, PostIn in, Authentication auth) {
        DraftRules.Payload d = e.draft();
        Person p = e.person();
        boolean cheque = e.kind() == Kind.CHECK_VOUCHER;
        int code = cheque ? DraftPostRules.CHECK_VOUCHER : DraftPostRules.CASH_VOUCHER;
        int dept = d.dept() == null || d.dept() <= 0 ? p.dept() : d.dept();
        List<WaitingVoucher.Line> lines = new ArrayList<>();
        for (In l : e.lines()) {
            if (l.member()) {
                int status = l.slc() == DraftPostRules.SAVINGS_CLASS ? savingsStatus(p.branch(), d.clientId(), l.slt(), l.refNo()) : payableStatus(p.branch(), d.clientId(), l.slt(), l.refNo(), true);
                lines.add(new WaitingVoucher.Line(l.account(), l.dept() > 0 ? l.dept() : dept, l.slc(), l.slt(), l.refNo(), PRINCIPAL, status, Money.of(l.amount())));
            } else {
                lines.add(new WaitingVoucher.Line(l.account(), l.dept() > 0 ? l.dept() : dept, null, null, null, null, null, Money.of(l.amount())));
            }
        }
        BigDecimal amount = e.plan().amount();
        if (cheque) {
            lines.add(new WaitingVoucher.Line(e.bankAccount(), dept, null, null, null, null, null, Money.of(amount.negate())));
        } else {
            int ref = openVouchersPayable(p.branch(), d.clientId());
            String payable = poster.glAccount(p.branch(), DraftPostRules.PAYABLE_CLASS, 1, PRINCIPAL, ACTIVE);
            lines.add(new WaitingVoucher.Line(payable, dept, DraftPostRules.PAYABLE_CLASS, 1, ref, PRINCIPAL, ACTIVE, Money.of(amount.negate())));
        }
        // the screen's key keeps a second press from encoding twice; without one (an older screen) the draft, deleted below, is the guard
        String key = RequestKeys.current("fo.voucher.encode", auth.getName());
        if (key == null) key = "fo.voucher.encode/" + auth.getName() + "/draft-" + d.hashCode() + "-" + java.util.UUID.randomUUID();
        Ledger.Posted v = ledger.encodeWaiting(new WaitingVoucher(key, auth.getName(), p.branch(), code,
            d.clientId() == null ? 0 : d.clientId(), explanation(d, cheque ? "CHECK VOUCHER" : "CASH VOUCHER"), p.id(), lines));
        if (v.repeated()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This was already encoded as voucher " + v.documentNo() + ". Nothing was written again.");
        if (cheque) {
            OwnChequeIn c = in.cheque();
            jdbc.update("""
                    INSERT INTO ecis_voucher_cheque (branch_code, tr_year, tr_code, tr_ctlno, bank_code, cheque_no, cheque_date, payee, amount, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, p.branch(), v.year(), code, v.controlNo(), c.bankId(), DraftPostRules.ownChequeNumber(c.number()), java.sql.Date.valueOf(c.date()), payee(c, d),
                amount, auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock)));
        }
        return new Posted(e.kind().name(), v.year(), code, v.controlNo(), v.documentNo(), v.date().toString(), amount, "WAITING FOR APPROVAL");
    }

    // ---- checking -----------------------------------------------------------------------------------------------------

    private record Evaluated(Plan plan, Kind kind, DraftRules.Payload draft, Person person, List<In> lines, String bankAccount) {}

    private Evaluated evaluate(long id, PostIn in, Authentication auth, boolean lock) {
        Perms perms = access.of(auth);
        List<String> found = jdbc.queryForList("SELECT payload FROM ecis_fo_draft WHERE id = ? AND created_by = ?" + (lock ? " FOR UPDATE" : ""), String.class, id, auth.getName());
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found.");
        DraftRules.Payload d = read(found.get(0));
        access.requireNew(perms, d.trCode());
        Person p = poster.person(auth.getName());
        Kind kind = DraftPostRules.kindOf(d.trCode());
        List<String> problems = new ArrayList<>();
        if (kind == null) {
            problems.add(DraftPostRules.notPosted(d.trCode()));
            return new Evaluated(new Plan(null, null, null, List.of(), BigDecimal.ZERO, null, problems, List.of(), false), null, d, p, List.of(), null);
        }

        List<In> lines = new ArrayList<>();
        int n = 0;
        for (DraftRules.Line l : d.lines()) {
            n++;
            Integer ref = null;
            try {
                ref = l.acctNum() == null || l.acctNum().isBlank() ? null : Integer.valueOf(l.acctNum().trim());
            } catch (NumberFormatException x) {
                if (l.slc() != null && l.slc() > 0) problems.add("Entry " + n + ": the account number must be a number.");
            }
            BigDecimal amount = new BigDecimal(l.debit()).subtract(new BigDecimal(l.credit()));
            lines.add(new In(n, l.accountCode(), l.slc() != null && l.slc() > 0 ? l.slc() : null, l.slc() != null && l.slc() > 0 ? l.slt() : null, ref, amount, l.dept() == null ? 0 : l.dept()));
        }
        String payableAccount = kind == Kind.CASH_VOUCHER ? poster.glAccount(p.branch(), DraftPostRules.PAYABLE_CLASS, 1, PRINCIPAL, ACTIVE) : null;
        String bankAccount = null;
        if (kind == Kind.CHECK_VOUCHER && in.cheque() != null && in.cheque().bankId() != null) {
            String wanted = in.cheque().account();
            bankAccount = banks.banks(auth).stream().filter((b) -> b.bankId() == in.cheque().bankId() && (wanted == null || wanted.isBlank() || wanted.equals(b.ledgerAccount())))
                .map(BankCashController.BankRow::ledgerAccount).findFirst().orElse(null);
            if (bankAccount == null) problems.add("That bank account is not one of the cooperative's.");
        }
        DraftPostRules.Plan rules = DraftPostRules.plan(d.trCode(), lines, DraftRules.totals(d.lines()).balanced(), d.clientId(), d.clientName(), payableAccount, bankAccount);
        problems.addAll(rules.problems());
        BigDecimal amount = rules.total();
        if (p.dept() <= 0) problems.add("Your account has no department set, so this cannot be posted (every entry carries the department of the person who makes it). Ask an administrator to set your department in Settings > Users, then try again.");

        // the member's accounts: they must belong to the member, be active, and hold what is paid out of them
        Integer client = d.clientId();
        if (client != null && client > 0) {
            MemberLedgerPoster.Client c = poster.client(p.branch(), client);
            if (c.status() == 3 || c.status() == 98 || c.status() == 99) problems.add("This member's account is closed, disapproved or not yet approved.");
        }
        for (In l : rules.lines()) {
            if (!l.member() || client == null || l.refNo() == null) continue;
            if (l.slc() == DraftPostRules.PAYABLE_CLASS) {
                List<Integer> st = jdbc.queryForList("SELECT OSLSTATUS FROM osl WHERE OSLBR_CODE = ? AND ClientIDOSL = ? AND OSLSLC_CODE = 29 AND OSLSLT_CODE = ? AND OSLREF_NO = ?"
                    + (lock ? " FOR UPDATE" : ""), Integer.class, p.branch(), client, l.slt(), l.refNo());
                if (st.isEmpty()) { problems.add("Entry " + l.n() + ": the member has no vouchers-payable account " + l.refNo() + " of that type."); continue; }
                String expected = glOrNull(p.branch(), 29, l.slt(), st.get(0) == null ? 0 : st.get(0));
                if (expected != null && !expected.equals(l.account())) problems.add("Entry " + l.n() + ": that payable account posts to " + expected + ", not " + l.account() + ".");
                String over = DraftPostRules.overBalance(l, payableBalance(p.branch(), client, l.slt(), l.refNo()), BigDecimal.ZERO, st.get(0) == null ? 0 : st.get(0), ACTIVE);
                if (over != null) problems.add(over);
            } else if (l.slc() == DraftPostRules.SAVINGS_CLASS) {
                MemberLedgerPoster.Account a;
                try {
                    a = poster.account(MemberLedgerPoster.Master.SAVINGS, p.branch(), client, l.slt(), l.refNo());
                } catch (ResponseStatusException x) {
                    problems.add("Entry " + l.n() + ": the member has no such savings account."); continue;
                }
                String expected = glOrNull(p.branch(), 22, l.slt(), a.status());
                if (expected != null && !expected.equals(l.account())) problems.add("Entry " + l.n() + ": that savings account posts to " + expected + ", not " + l.account() + ".");
                SavingsRules.Check w = SavingsRules.checkWithdrawal(l.amount(), a.status(), a.balance(), a.held(), a.minBalance(), 1, null, l.amount());
                problems.addAll(w.problems().stream().map((x) -> "Entry " + l.n() + ": " + x).toList());
                problems.addAll(w.needs().stream().map((x) -> "Entry " + l.n() + ": " + x.title() + ".").toList());
                String k = kyc.withdrawalProblem(p.branch(), client, LocalDate.now(clock));
                if (k != null) problems.add(k);
            }
        }

        BigDecimal drawer = null;
        List<SavingsPostingController.NeedStatus> needs = new ArrayList<>();
        if (kind == Kind.OTHER_RECEIPT || kind == Kind.MIXED_PAYMENT) {
            List<Integer> events = HandoverController.eventsOf(p.head(), p.assignment());
            if (p.number() <= 0 || events.isEmpty()) problems.add("Only a cashier or teller with a drawer can take or pay out cash.");
            else drawer = balances.of(p.branch(), new Drawer(events.get(0), p.number()));
            if (kind == Kind.MIXED_PAYMENT && amount.signum() > 0) {
                if (drawer != null && drawer.compareTo(amount) < 0) problems.add("Your drawer holds " + money(drawer) + ", less than the " + money(amount) + " to pay out. Ask the head cashier for cash first (Cash Handover).");
                BigDecimal limit = p.disbursementLimit().min(p.maxWithdrawal());
                // as for a voucher payment: over the smaller of the two limits (a limit of 0 means every payment) a supervisor allows it first
                if (amount.compareTo(limit) > 0) needs.add(need(p, amount, limit, id));
            }
            if (kind == Kind.OTHER_RECEIPT && in.cheques() != null && !in.cheques().isEmpty()) {
                BigDecimal cheques = in.cheques().stream().map((c) -> c.amount() == null ? BigDecimal.ZERO : c.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (cheques.compareTo(amount) > 0) problems.add("The cheques add up to " + money(cheques) + ", more than the receipt of " + money(amount) + ".");
            }
        }
        if (kind == Kind.CHECK_VOUCHER) {
            OwnChequeIn c = in.cheque();
            if (c == null) problems.add("Enter the cheque: the bank account, number and date.");
            else {
                if (DraftPostRules.ownChequeNumber(c.number()) == null) problems.add("The cheque number must be 1 to 15 digits.");
                if (c.date() == null) problems.add("Enter the date on the cheque.");
                else if (c.date().isBefore(LocalDate.now(clock).minusDays(ChequeRules.MAX_AGE_DAYS)) || c.date().isAfter(LocalDate.now(clock).plusDays(ChequeRules.MAX_AHEAD_DAYS)))
                    problems.add("The cheque date must be within " + ChequeRules.MAX_AGE_DAYS + " days back and " + ChequeRules.MAX_AHEAD_DAYS + " days ahead.");
                if (c.bankId() != null && DraftPostRules.ownChequeNumber(c.number()) != null && db.tableExists("ecis_voucher_cheque")) {
                    Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_voucher_cheque WHERE branch_code = ? AND bank_code = ? AND cheque_no = ?", Integer.class,
                        p.branch(), c.bankId(), DraftPostRules.ownChequeNumber(c.number()));
                    if (used != null && used > 0) problems.add("Cheque " + DraftPostRules.ownChequeNumber(c.number()) + " of this bank account is already on another voucher.");
                }
            }
            if (!db.tableExists("ecis_voucher_cheque")) problems.add("Check Vouchers are not set up yet. An administrator has to apply the general voucher migration.");
        }

        List<PlanLine> shown = new ArrayList<>();
        for (In l : rules.lines()) shown.add(new PlanLine(l.account(), describe(p.branch(), l.account()), l.amount()));
        String postsHow;
        switch (kind) {
            case OTHER_RECEIPT -> {
                shown.add(0, new PlanLine(cashAccount(p), "Cash received at your drawer", amount));
                postsHow = "Posted at once on your drawer. It cannot be edited afterwards.";
            }
            case MIXED_PAYMENT -> {
                shown.add(new PlanLine(cashAccount(p), "Cash paid out of your drawer", amount.negate()));
                postsHow = "Posted at once out of your drawer. It cannot be edited afterwards.";
            }
            case CASH_VOUCHER -> {
                shown.add(new PlanLine(payableAccount, "Vouchers payable of the payee (a new account)", amount.negate()));
                postsHow = "Waits for another person's approval, then is posted; the payee is then paid on Disburse > Pay out vouchers payable.";
            }
            default -> {
                shown.add(new PlanLine(bankAccount == null ? "" : bankAccount, "The bank account the cheque is drawn on", amount.negate()));
                postsHow = "Waits for another person's approval, then is posted with its cheque.";
            }
        }
        List<String> all = problems.stream().filter((x) -> x != null).distinct().toList();
        boolean ready = all.isEmpty() && needs.stream().allMatch((x) -> "APPROVED".equals(x.status()));
        Plan plan = new Plan(kind.name(), words(kind), postsHow, shown, amount, drawer, all, needs, ready);
        return new Evaluated(plan, kind, d, p, rules.lines(), bankAccount);
    }

    private SavingsPostingController.NeedStatus need(Person p, BigDecimal amount, BigDecimal limit, long draftId) {
        String title = "the amount is over the cashier's disbursement limit of " + money(limit);
        String description = ("Misc disbursement of " + amount.toPlainString() + " from draft " + draftId + ": " + title);
        description = description.substring(0, Math.min(description.length(), 250));
        String status = "NONE";
        if (db.tableExists("ecis_fo_override") && db.columnExists("ecis_fo_override", "used_ref")) {
            List<String> latest = jdbc.queryForList("""
                    SELECT status FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ? AND used_ref IS NULL
                    ORDER BY id DESC LIMIT 1
                    """, String.class, p.branch(), VoucherPayoutRules.LIMIT_KIND, p.id(), amount, description);
            if (!latest.isEmpty()) status = latest.get(0);
        }
        return new SavingsPostingController.NeedStatus(VoucherPayoutRules.LIMIT_KIND, title, description, amount, limit, status);
    }

    static String words(Kind k) {
        return switch (k) {
            case OTHER_RECEIPT -> "Other receipt";
            case MIXED_PAYMENT -> "Misc disbursement";
            case CASH_VOUCHER -> "Cash Voucher";
            case CHECK_VOUCHER -> "Check Voucher";
        };
    }

    private String cashAccount(Person p) {
        List<Integer> events = HandoverController.eventsOf(p.head(), p.assignment());
        return events.isEmpty() ? "" : HandoverRules.account(events.get(0));
    }

    private String describe(int branch, String account) {
        return jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, branch, account).stream().findFirst().orElse("");
    }

    private String glOrNull(int branch, int slc, int slt, int cts) {
        return jdbc.queryForList("SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = 11 AND GLControlCTS_NUM = ?",
            String.class, branch, slc, slt, cts).stream().findFirst().orElse(null);
    }

    private BigDecimal payableBalance(int branch, int client, int slt, int ref) {
        return jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = 29 AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11",
            BigDecimal.class, branch, client, slt, ref);
    }

    private int payableStatus(int branch, int client, int slt, int ref, boolean lock) {
        List<Integer> st = jdbc.queryForList("SELECT OSLSTATUS FROM osl WHERE OSLBR_CODE = ? AND ClientIDOSL = ? AND OSLSLC_CODE = 29 AND OSLSLT_CODE = ? AND OSLREF_NO = ?",
            Integer.class, branch, client, slt, ref);
        return st.isEmpty() || st.get(0) == null ? ACTIVE : st.get(0);
    }

    private int savingsStatus(int branch, int client, int slt, int ref) {
        return poster.account(MemberLedgerPoster.Master.SAVINGS, branch, client, slt, ref).status();
    }

    /** A new vouchers-payable account (class 29, type 1) for the payee, as a loan release opens one: the next number of the branch, active. */
    private int openVouchersPayable(int branch, int clientId) {
        List<Integer> last = jdbc.queryForList("SELECT MREF_NO FROM slmastersum WHERE MBR_CODE = ? AND MSLC_CODE = 29 AND MSLT_CODE = 1 ORDER BY MREF_NO DESC LIMIT 1 FOR UPDATE", Integer.class, branch);
        int ref = last.isEmpty() ? 1 : last.get(0) + 1;
        jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (?, 29, 1, ?, ?)", branch, ref, clientId);
        jdbc.update("INSERT INTO osl (OSLBR_CODE, OSLSLC_CODE, OSLSLT_CODE, OSLREF_NO, ClientIDOSL, OSLTR_DATE, OSLSTATUS, OSLPAMT) VALUES (?, 29, 1, ?, ?, ?, 15, 0.00)",
            branch, ref, clientId, LocalDate.now(clock).toString());
        return ref;
    }

    private static String payee(OwnChequeIn c, DraftRules.Payload d) {
        String p = c.payee() != null && !c.payee().isBlank() ? c.payee().trim() : d.clientName() == null ? "" : d.clientName().trim();
        return p.substring(0, Math.min(p.length(), 60));
    }

    private static String explanation(DraftRules.Payload d, String fallback) {
        return d.explanation() == null || d.explanation().isBlank() ? fallback : d.explanation();
    }

    private static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    private DraftRules.Payload read(String text) {
        try {
            return json.readValue(text, DraftRules.Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
