package ph.coop.core.frontoffice;


import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
import java.time.LocalDate;
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
import ph.coop.core.frontoffice.MemberLedgerPoster.Client;
import ph.coop.core.frontoffice.MemberLedgerPoster.Direction;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.frontoffice.MemberLedgerPoster.Posted;
import ph.coop.core.loan.LoanBook;
import ph.coop.core.loan.LoanRules;

/**
 * Posts a cash payment on a member's loan, as the old Cash Receipt did (module 3, type 10; see documentation/loan-posting-map.md):
 * cash into the poster's own drawer, then the principal and the interest credited to the loan, interest first. Interest is the
 * outstanding principal times the rate times the days since interest was last paid, over 365. A loan that is late beyond its grace period
 * carries a penalty, which is taken first, then the interest, then the principal. The program only suggests the penalty (the formula
 * matches the old system in the simple cases and not in the rest, see documentation/loan-posting-map.md): the cashier types the amount,
 * and a different figure from the suggestion needs a supervisor's approval, asked through the front office overrides and spent by the
 * posting. A loan in litigation is refused.
 */
@RestController
@RequestMapping("/api/front-office/loan-payments")
class LoanPaymentController {

    /** penalty empty means "the suggested one". */
    /** cheques is the part of the amount paid by cheque; the rest is cash (which pays the penalty, interest and principal first, as the old system did). */
    record Request(int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate, BigDecimal penalty, java.util.List<ChequeRules.Input> cheques) {
        Request(int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate, BigDecimal penalty) {
            this(clientId, typeCode, refNo, amount, explanation, receiptNo, confirmDuplicate, penalty, null);
        }
        Request(int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate) {
            this(clientId, typeCode, refNo, amount, explanation, receiptNo, confirmDuplicate, null, null);
        }
    }

    /** A different penalty from the suggestion has to be allowed by a supervisor; status is NONE, PENDING, APPROVED or REJECTED. */
    record Approval(String kind, String title, String description, BigDecimal amount, String status) {}

    /** suggestedPenalty is null when the formula does not cover this loan's penalty mode; penalty is the figure that will be posted. */
    record Breakdown(BigDecimal outstanding, LocalDate interestFrom, int days, BigDecimal interestDue, BigDecimal payoff, BigDecimal pastDuePrincipal, long daysPastDue, int graceDays,
                     BigDecimal suggestedPenalty, BigDecimal penalty, Approval approval,
                     BigDecimal interest, BigDecimal principal, BigDecimal balanceAfter, List<String> problems, boolean ready) {}

    record Receipt(int branchCode, int year, long controlNo, String documentNo, String date, int clientId, String clientName, String loan, BigDecimal amount,
                   BigDecimal penalty, BigDecimal interest, BigDecimal principal, BigDecimal balanceAfter, String cashier) {}

    static final String PENALTY_KIND = "LOAN_PENALTY";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final LoanBook book;
    private final TransactionTemplate tx;
    private final java.time.Clock clock;

    LoanPaymentController(JdbcTemplate jdbc, Db db, FrontOfficeAccess access, MemberLedgerPoster poster, LoanBook book, PlatformTransactionManager txManager, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.access = access;
        this.poster = poster;
        this.book = book;
        this.tx = new TransactionTemplate(txManager);
    }

    /** What the payment would be split into, and what stops it, without posting anything. */
    @PostMapping("/check")
    Breakdown check(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, SharePaymentRules.CASH_RECEIPT);
        Person p = poster.person(auth.getName());
        BigDecimal amount = req.amount() == null || req.amount().stripTrailingZeros().scale() > 2 ? null : req.amount().setScale(2, java.math.RoundingMode.UNNECESSARY);
        return breakdown(p, req, amount, poster.client(p.branch(), req.clientId()), LocalDate.now(clock));
    }

    @PostMapping
    Receipt post(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, SharePaymentRules.CASH_RECEIPT);
        Person p = poster.person(auth.getName());
        if (req.amount() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount above zero.");
        if (req.amount().stripTrailingZeros().scale() > 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The amount can have at most 2 decimal places.");
        BigDecimal amount = req.amount().setScale(2, java.math.RoundingMode.UNNECESSARY);
        return tx.execute((st) -> {
            Client c = poster.client(p.branch(), req.clientId());
            LocalDate today = LocalDate.now(clock);
            Breakdown b = breakdown(p, req, amount, c, today);
            if (!b.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", b.problems()));
            LoanBook.Terms t = book.terms(p.branch(), req.clientId(), req.typeCode(), req.refNo()).orElseThrow();

            List<MemberLine> lines = new ArrayList<>();
            if (b.principal().signum() > 0) {
                lines.add(new MemberLine(12, req.typeCode(), req.refNo(), 11, t.status(), poster.glAccount(p.branch(), 12, req.typeCode(), 11, t.status()), b.principal().negate()));
            }
            if (b.interest().signum() > 0) {
                lines.add(new MemberLine(12, req.typeCode(), req.refNo(), 23, 99, poster.glAccount(p.branch(), 12, req.typeCode(), 23, 99), b.interest().negate()));
            }
            if (b.penalty().signum() > 0) {
                lines.add(new MemberLine(12, req.typeCode(), req.refNo(), 41, 99, poster.glAccount(p.branch(), 12, req.typeCode(), 41, 99), b.penalty().negate()));
            }
            // a penalty that is not the suggested one was allowed by a supervisor; that approval is spent here and nowhere else
            long[] spent = null;
            if (b.approval() != null) {
                List<long[]> found = jdbc.query("""
                        SELECT id, decided_by FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ?
                          AND status = 'APPROVED' AND used_ref IS NULL ORDER BY id LIMIT 1 FOR UPDATE
                        """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, p.branch(), PENALTY_KIND, p.id(), b.approval().amount(), b.approval().description());
                if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A supervisor has to allow this first: " + b.approval().title() + ".");
                spent = found.get(0);
            }
            String explanation = req.explanation() == null || req.explanation().isBlank() ? "LOAN PAYMENT" : req.explanation();
            List<ChequeRules.Cheque> cheques = poster.cheques(req.cheques(), req.confirmDuplicate());
            Posted r = poster.postVoucher(Direction.IN, p, req.clientId(), explanation, req.receiptNo(), req.confirmDuplicate(), lines, "loan.payment.post", auth.getName(),
                "loan " + req.typeCode() + "/" + req.refNo() + " amount " + amount.toPlainString() + " penalty " + b.penalty().toPlainString()
                    + (b.suggestedPenalty() == null ? " (no suggestion)" : " (suggested " + b.suggestedPenalty().toPlainString() + ")")
                    + " interest " + b.interest().toPlainString() + " principal " + b.principal().toPlainString()
                    + (cheques.isEmpty() ? "" : " cheques " + MemberLedgerPoster.describe(cheques)), cheques);
            if (spent != null) {
                String ref = r.branch() + "/" + r.year() + "/3/10/" + r.controlNo();
                jdbc.update("UPDATE ecis_fo_override SET used_ref = ? WHERE id = ?", ref, spent[0]);
                AuditLog.write(jdbc, null, auth.getName(), "loan.payment.override.used", "fo_override", String.valueOf(spent[0]), null, "allowed by user " + spent[1] + " for " + ref + " " + b.approval().description(), null);
            }
            return new Receipt(r.branch(), r.year(), r.controlNo(), r.documentNo(), r.date().toString(), req.clientId(), c.name(),
                (t.type() == null ? "Loan " + req.typeCode() : t.type()) + " " + req.refNo(), amount, b.penalty(), b.interest(), b.principal(), b.balanceAfter(), p.name());
        });
    }

    private Breakdown breakdown(Person p, Request req, BigDecimal amount, Client c, LocalDate today) {
        LoanBook.Terms t = book.terms(p.branch(), req.clientId(), req.typeCode(), req.refNo())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That client has no such loan."));
        LoanBook.Quote q = book.quote(p.branch(), req.clientId(), req.typeCode(), req.refNo(), t, today);
        List<String> problems = new ArrayList<>();
        if (amount == null || amount.signum() <= 0) problems.add("Enter an amount above zero.");
        if (t.outstanding().signum() <= 0) problems.add("This loan has nothing left to pay.");
        if (t.status() == 13) problems.add("This loan is in litigation, so payments cannot be taken at the window.");
        if (t.status() != 11 && t.status() != 12 && t.status() != 13 && t.status() != 14) problems.add("This loan has an unknown status (" + t.status() + ").");
        if (c.status() == 3 || c.status() == 98 || c.status() == 99) problems.add("This client's account is closed, disapproved or not yet approved.");

        // the penalty: the suggestion unless the cashier typed another figure
        BigDecimal suggested = q.suggestedPenalty();
        BigDecimal penalty = suggested == null ? BigDecimal.ZERO : suggested;
        if (req.penalty() != null) {
            if (req.penalty().signum() < 0 || req.penalty().stripTrailingZeros().scale() > 2) problems.add("The penalty must be zero or more, with at most 2 decimal places.");
            else penalty = req.penalty().setScale(2, java.math.RoundingMode.UNNECESSARY);
        }
        Approval approval = null;
        BigDecimal basis = suggested == null ? BigDecimal.ZERO : suggested;
        if (penalty.compareTo(basis) != 0) {
            BigDecimal diff = penalty.subtract(basis).abs();
            String title = "penalty of " + penalty.toPlainString() + " instead of " + (suggested == null ? "none suggested" : "the suggested " + suggested.toPlainString());
            String description = "Loan payment on " + (t.type() == null ? "type " + req.typeCode() : t.type()) + " " + req.refNo() + " of client " + req.clientId() + ": " + title;
            description = description.substring(0, Math.min(description.length(), 250));
            approval = new Approval(PENALTY_KIND, title, description, diff, approvalStatus(p, diff, description));
        }

        if (amount != null && req.cheques() != null) {
            BigDecimal byCheque = req.cheques().stream().map((ch) -> ch.amount() == null ? BigDecimal.ZERO : ch.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (byCheque.compareTo(amount) > 0) problems.add("The cheques add up to " + byCheque.toPlainString() + ", more than the payment of " + amount.toPlainString() + ".");
        }
        BigDecimal interest = BigDecimal.ZERO, principal = BigDecimal.ZERO;
        if (amount != null && amount.signum() > 0) {
            if (penalty.compareTo(amount) > 0) problems.add("The amount has to cover the penalty of " + penalty.toPlainString() + " first.");
            else if (amount.compareTo(q.payoff().add(penalty)) > 0) problems.add("The most that can be paid is " + q.payoff().add(penalty).toPlainString() + " (the balance of " + t.outstanding().toPlainString()
                + ", " + q.interestDue().toPlainString() + " interest and " + penalty.toPlainString() + " penalty).");
            LoanRules.Split s = LoanRules.split(amount.subtract(penalty).max(BigDecimal.ZERO), q.interestDue());
            interest = s.interest();
            principal = s.principal().min(t.outstanding());
        }
        boolean allowed = approval == null || "APPROVED".equals(approval.status());
        return new Breakdown(t.outstanding(), t.interestPaidTo(), q.days(), q.interestDue(), q.payoff(), q.pastDuePrincipal(), q.daysPastDue(), t.graceDays(),
            suggested, penalty, approval, interest, principal, t.outstanding().subtract(principal), problems, problems.isEmpty() && amount != null && allowed);
    }

    /** The latest answer to the cashier's request for exactly this penalty, unless it was already spent. */
    private String approvalStatus(Person p, BigDecimal diff, String description) {
        if (!db.tableExists("ecis_fo_override") || !db.columnExists("ecis_fo_override", "used_ref")) return "NONE";
        List<String> latest = jdbc.queryForList("""
                SELECT status FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ? AND used_ref IS NULL
                ORDER BY id DESC LIMIT 1
                """, String.class, p.branch(), PENALTY_KIND, p.id(), diff, description);
        return latest.isEmpty() ? "NONE" : latest.get(0);
    }
}
