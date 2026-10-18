package ph.coop.core.frontoffice;

import java.math.BigDecimal;
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
import ph.coop.core.frontoffice.MemberLedgerPoster.Account;
import ph.coop.core.frontoffice.MemberLedgerPoster.Client;
import ph.coop.core.frontoffice.MemberLedgerPoster.Direction;
import ph.coop.core.frontoffice.MemberLedgerPoster.Entry;
import ph.coop.core.frontoffice.MemberLedgerPoster.Master;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.frontoffice.MemberLedgerPoster.Posted;

/**
 * Posts a cash payment into a member's share capital account, exactly as the old Cash Receipt did (module 3, type 10); see
 * documentation/share-payment-posting-map.md. The rows are written by {@link MemberLedgerPoster}. Dated today; the person must
 * be a cashier or teller allowed to add cash receipts.
 */
@RestController
@RequestMapping("/api/front-office/share-payments")
class SharePaymentController {

    record Request(int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate) {}

    record Receipt(int branchCode, int year, long controlNo, String documentNo, String date, int clientId, String clientName,
                   String account, BigDecimal amount, BigDecimal balanceAfter, String cashier) {}

    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final TransactionTemplate tx;

    SharePaymentController(FrontOfficeAccess access, MemberLedgerPoster poster, PlatformTransactionManager txManager) {
        this.access = access;
        this.poster = poster;
        this.tx = new TransactionTemplate(txManager);
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
            Account a = poster.account(Master.SHARE_CAPITAL, p.branch(), req.clientId(), req.typeCode(), req.refNo());
            Client c = poster.client(p.branch(), req.clientId());
            var problems = SharePaymentRules.check(amount, a.status(), a.balance(), a.maxBalance(), c.status());
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
            Posted r = poster.post(new Entry(Master.SHARE_CAPITAL, Direction.IN, p.branch(), req.clientId(), req.typeCode(), req.refNo(), amount,
                req.explanation(), req.receiptNo(), req.confirmDuplicate()), p, a, auth.getName());
            return new Receipt(r.branch(), r.year(), r.controlNo(), r.documentNo(), r.date().toString(), req.clientId(), c.name(),
                (a.type() == null ? "Share capital" : a.type()) + (a.refText() == null ? " " + req.refNo() : " " + a.refText()), amount, a.balance().add(amount), p.name());
        });
    }
}
