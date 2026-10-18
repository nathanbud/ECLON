package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.MemberLedgerPoster.Account;
import ph.coop.core.frontoffice.MemberLedgerPoster.Client;
import ph.coop.core.frontoffice.MemberLedgerPoster.Direction;
import ph.coop.core.frontoffice.MemberLedgerPoster.Entry;
import ph.coop.core.frontoffice.MemberLedgerPoster.Master;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.frontoffice.MemberLedgerPoster.Posted;
import ph.coop.core.frontoffice.SavingsRules.Need;

/**
 * Posts a cash deposit into, and a cash withdrawal from, a savings account, exactly as the old system did (a Cash Receipt,
 * module 3 type 10, and a Deposit Withdrawal, module 2 type 23); see documentation/savings-posting-map.md. The rows are
 * written by {@link MemberLedgerPoster}. A withdrawal under the minimum balance or over the cashier's limit needs a
 * supervisor's approval first: the cashier asks through the front office override requests (using the text this class gives),
 * another supervisor approves, and the approval is then spent by the withdrawal, once.
 */
@RestController
@RequestMapping("/api/front-office/savings")
class SavingsPostingController {

    record Request(int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate) {}

    /** status is NONE, PENDING, APPROVED or REJECTED for this very withdrawal; description is what to send when asking. */
    record NeedStatus(String kind, String title, String description, BigDecimal amount, BigDecimal limit, String status) {}

    record Check(List<String> problems, List<NeedStatus> needs, boolean ready) {}

    record Receipt(String kind, int branchCode, int year, long controlNo, String documentNo, String date, int clientId, String clientName,
                   String account, BigDecimal amount, BigDecimal balanceAfter, String cashier) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final TransactionTemplate tx;

    SavingsPostingController(JdbcTemplate jdbc, Db db, FrontOfficeAccess access, MemberLedgerPoster poster, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.db = db;
        this.access = access;
        this.poster = poster;
        this.tx = new TransactionTemplate(txManager);
    }

    private static BigDecimal amountOf(Request req) {
        if (req.amount() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount above zero.");
        if (req.amount().stripTrailingZeros().scale() > 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The amount can have at most 2 decimal places.");
        return req.amount().setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    // ---- deposit ------------------------------------------------------------------------------------------------

    @PostMapping("/deposits")
    Receipt deposit(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, SharePaymentRules.CASH_RECEIPT);
        Person p = poster.person(auth.getName());
        BigDecimal amount = amountOf(req);
        return tx.execute((st) -> {
            Account a = poster.account(Master.SAVINGS, p.branch(), req.clientId(), req.typeCode(), req.refNo());
            Client c = poster.client(p.branch(), req.clientId());
            List<String> problems = SavingsRules.checkDeposit(amount, a.status(), c.status());
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
            Posted r = poster.post(new Entry(Master.SAVINGS, Direction.IN, p.branch(), req.clientId(), req.typeCode(), req.refNo(), amount,
                req.explanation(), req.receiptNo(), req.confirmDuplicate()), p, a, auth.getName());
            return receipt("DEPOSIT", r, req, c, a, amount, a.balance().add(amount), p);
        });
    }

    // ---- withdrawal ---------------------------------------------------------------------------------------------

    /** What would stop this withdrawal, and what a supervisor would have to allow, without posting anything. */
    @PostMapping("/withdrawals/check")
    Check check(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, SavingsRules.WITHDRAWAL);
        Person p = poster.person(auth.getName());
        BigDecimal amount = req.amount() == null || req.amount().stripTrailingZeros().scale() > 2 ? null : req.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        Account a = poster.account(Master.SAVINGS, p.branch(), req.clientId(), req.typeCode(), req.refNo());
        Client c = poster.client(p.branch(), req.clientId());
        SavingsRules.Check rules = rulesFor(amount, a, c, p);
        List<NeedStatus> needs = new ArrayList<>();
        for (Need n : rules.needs()) needs.add(statusOf(n, req, a, amount, p));
        boolean ready = rules.problems().isEmpty() && needs.stream().allMatch((n) -> "APPROVED".equals(n.status()));
        return new Check(rules.problems(), needs, ready);
    }

    @PostMapping("/withdrawals")
    Receipt withdraw(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, SavingsRules.WITHDRAWAL);
        Person p = poster.person(auth.getName());
        BigDecimal amount = amountOf(req);
        return tx.execute((st) -> {
            Account a = poster.account(Master.SAVINGS, p.branch(), req.clientId(), req.typeCode(), req.refNo());
            Client c = poster.client(p.branch(), req.clientId());
            SavingsRules.Check rules = rulesFor(amount, a, c, p);
            if (!rules.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", rules.problems()));

            // every rule the old system let a supervisor allow needs its own approval, spent here and nowhere else
            List<long[]> spent = new ArrayList<>();
            for (Need n : rules.needs()) {
                String description = describe(n, req, a, amount);
                List<long[]> found = jdbc.query("""
                        SELECT id, decided_by FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ?
                          AND status = 'APPROVED' AND used_ref IS NULL ORDER BY id LIMIT 1 FOR UPDATE
                        """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, p.branch(), n.kind(), p.id(), amount, description);
                if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A supervisor has to allow this first: " + n.title() + ".");
                spent.add(found.get(0));
            }

            Posted r = poster.post(new Entry(Master.SAVINGS, Direction.OUT, p.branch(), req.clientId(), req.typeCode(), req.refNo(), amount,
                req.explanation(), req.receiptNo(), req.confirmDuplicate()), p, a, auth.getName());

            for (long[] o : spent) {
                jdbc.update("UPDATE ecis_fo_override SET used_ref = ? WHERE id = ?", r.branch() + "/" + r.year() + "/2/23/" + r.controlNo(), o[0]);
                jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (NOW(), ?, 'savings.withdrawal.override.used', 'fo_override', ?, ?)",
                    auth.getName(), String.valueOf(o[0]), "allowed by user " + o[1] + " for " + r.branch() + "/" + r.year() + "/2/23/" + r.controlNo() + " amount " + amount.toPlainString());
            }
            return receipt("WITHDRAWAL", r, req, c, a, amount, a.balance().subtract(amount), p);
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private SavingsRules.Check rulesFor(BigDecimal amount, Account a, Client c, Person p) {
        BigDecimal limit = p.disbursementLimit().min(p.maxWithdrawal());
        return SavingsRules.checkWithdrawal(amount, a.status(), a.balance(), a.held(), a.minBalance(), c.status(), limit);
    }

    /** The exact text of a request for this withdrawal; the same text is used to ask, to look up the answer, and to spend it. */
    private static String describe(Need n, Request req, Account a, BigDecimal amount) {
        String text = "Savings withdrawal of " + amount.toPlainString() + " from " + (a.type() == null ? "type " + req.typeCode() : a.type()) + " "
            + (a.refText() == null ? String.valueOf(req.refNo()) : a.refText()) + " of client " + req.clientId() + ": " + n.title();
        return text.substring(0, Math.min(text.length(), 250));
    }

    private NeedStatus statusOf(Need n, Request req, Account a, BigDecimal amount, Person p) {
        String description = describe(n, req, a, amount);
        String status = "NONE";
        if (amount != null && db.tableExists("ecis_fo_override") && db.columnExists("ecis_fo_override", "used_ref")) {
            List<String> latest = jdbc.queryForList("""
                    SELECT status FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ? AND used_ref IS NULL
                    ORDER BY id DESC LIMIT 1
                    """, String.class, p.branch(), n.kind(), p.id(), amount, description);
            if (!latest.isEmpty()) status = latest.get(0);
        }
        return new NeedStatus(n.kind(), n.title(), description, amount, n.limit(), status);
    }

    private static Receipt receipt(String kind, Posted r, Request req, Client c, Account a, BigDecimal amount, BigDecimal balanceAfter, Person p) {
        return new Receipt(kind, r.branch(), r.year(), r.controlNo(), r.documentNo(), r.date().toString(), req.clientId(), c.name(),
            (a.type() == null ? "Savings" : a.type()) + (a.refText() == null ? " " + req.refNo() : " " + a.refText()), amount, balanceAfter, p.name());
    }
}
