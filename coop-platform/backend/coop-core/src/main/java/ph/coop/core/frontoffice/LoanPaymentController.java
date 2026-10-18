package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
 * outstanding principal times the rate times the days since interest was last paid, over 365. A payment on a loan that is late beyond
 * its grace period would carry a penalty the app cannot compute yet, so it is refused; so is a loan in litigation.
 */
@RestController
@RequestMapping("/api/front-office/loan-payments")
class LoanPaymentController {

    record Request(int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate) {}

    record Breakdown(BigDecimal outstanding, LocalDate interestFrom, int days, BigDecimal interestDue, BigDecimal payoff, BigDecimal pastDuePrincipal, long daysPastDue, int graceDays,
                     BigDecimal interest, BigDecimal principal, BigDecimal balanceAfter, List<String> problems, boolean ready) {}

    record Receipt(int branchCode, int year, long controlNo, String documentNo, String date, int clientId, String clientName, String loan, BigDecimal amount,
                   BigDecimal interest, BigDecimal principal, BigDecimal balanceAfter, String cashier) {}

    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final LoanBook book;
    private final TransactionTemplate tx;
    private final java.time.Clock clock;

    LoanPaymentController(FrontOfficeAccess access, MemberLedgerPoster poster, LoanBook book, PlatformTransactionManager txManager, java.time.Clock clock) {
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
            String explanation = req.explanation() == null || req.explanation().isBlank() ? "LOAN PAYMENT" : req.explanation();
            Posted r = poster.postVoucher(Direction.IN, p, req.clientId(), explanation, req.receiptNo(), req.confirmDuplicate(), lines, "loan.payment.post", auth.getName(),
                "loan " + req.typeCode() + "/" + req.refNo() + " amount " + amount.toPlainString() + " interest " + b.interest().toPlainString() + " principal " + b.principal().toPlainString());
            return new Receipt(r.branch(), r.year(), r.controlNo(), r.documentNo(), r.date().toString(), req.clientId(), c.name(),
                (t.type() == null ? "Loan " + req.typeCode() : t.type()) + " " + req.refNo(), amount, b.interest(), b.principal(), b.balanceAfter(), p.name());
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
        if (q.pastDuePrincipal().signum() > 0 && q.daysPastDue() > t.graceDays()) {
            problems.add("This loan is " + q.daysPastDue() + " days past due, beyond its grace period of " + t.graceDays() + " days. The old system adds a penalty here that this program cannot work out yet, so post this payment there.");
        }
        BigDecimal interest = BigDecimal.ZERO, principal = BigDecimal.ZERO;
        if (amount != null && amount.signum() > 0) {
            if (amount.compareTo(q.payoff()) > 0) problems.add("The most that can be paid is " + q.payoff().toPlainString() + " (the balance of " + t.outstanding().toPlainString() + " and " + q.interestDue().toPlainString() + " interest).");
            LoanRules.Split s = LoanRules.split(amount, q.interestDue());
            interest = s.interest();
            principal = s.principal().min(t.outstanding());
        }
        return new Breakdown(t.outstanding(), t.interestPaidTo(), q.days(), q.interestDue(), q.payoff(), q.pastDuePrincipal(), q.daysPastDue(), t.graceDays(),
            interest, principal, t.outstanding().subtract(principal), problems, problems.isEmpty() && amount != null);
    }
}
