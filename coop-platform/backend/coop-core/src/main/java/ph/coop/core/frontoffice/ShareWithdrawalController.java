package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import ph.coop.core.frontoffice.MemberLedgerPoster.Master;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;

/**
 * Encodes a withdrawal of share capital as the old system does: a Cash Voucher (module 2, type 21, back office, every line tagged 2) that debits the
 * share capital account and credits the member's vouchers-payable account (29 / 1); see documentation/share-withdrawal-posting-map.md. Nothing reaches
 * the ledgers here. The voucher is then approved and posted on the Disburse tab by {@link LoanReleaseController}, by two other people, and the
 * cash is paid out of the vouchers-payable account later.
 */
@RestController
@RequestMapping("/api/front-office/share-withdrawals")
class ShareWithdrawalController {

    private static final int MODULE = 2, PENDING = 2, SHARE = 31, PAYABLE = 29, PRINCIPAL = 11;

    record Request(int clientId, int typeCode, int refNo, BigDecimal amount, String explanation) {}

    record Encoded(int branchCode, int year, long controlNo, String documentNo, int clientId, String clientName, BigDecimal amount, BigDecimal availableAfter) {}

    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final TransactionTemplate tx;

    ShareWithdrawalController(FrontOfficeAccess access, MemberLedgerPoster poster, JdbcTemplate jdbc, Db db, Clock clock, PlatformTransactionManager txm) {
        this.access = access;
        this.poster = poster;
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.tx = new TransactionTemplate(txm);
    }

    @PostMapping
    Encoded encode(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, ShareWithdrawalRules.CASH_VOUCHER);
        Person p = poster.person(auth.getName());
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so this cannot be encoded.");
        if (req.amount() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount above zero.");
        if (req.amount().stripTrailingZeros().scale() > 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The amount can have at most 2 decimal places.");
        BigDecimal amount = req.amount().setScale(2, java.math.RoundingMode.UNNECESSARY);
        return tx.execute((st) -> {
            int branch = p.branch();
            Account a = poster.account(Master.SHARE_CAPITAL, branch, req.clientId(), req.typeCode(), req.refNo());
            Client c = poster.client(branch, req.clientId());
            BigDecimal pending = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(DTAMT), 0) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTUPD_TAG = ? AND DTAMT > 0
                      AND ClientIDDT = ? AND DTSLC_CODE = ? AND DTSLT_CODE = ? AND DTREF_NO = ? AND DTSLE_CODE = ?
                    """, BigDecimal.class, branch, MODULE, ShareWithdrawalRules.CASH_VOUCHER, PENDING, req.clientId(), SHARE, req.typeCode(), req.refNo(), PRINCIPAL);
            var problems = ShareWithdrawalRules.check(amount, a.status(), a.balance(), pending, c.status());
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
            List<Integer> payable = jdbc.queryForList("SELECT MREF_NO FROM slmastersum WHERE MBR_CODE = ? AND MCLIENTID = ? AND MSLC_CODE = ? AND MSLT_CODE = 1 ORDER BY MREF_NO DESC LIMIT 1",
                Integer.class, branch, req.clientId(), PAYABLE);
            if (payable.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This member has no vouchers-payable account to credit.");
            int payableStatus = 15;
            String shareGl = poster.glAccount(branch, SHARE, req.typeCode(), PRINCIPAL, a.status());
            String payableGl = poster.glAccount(branch, PAYABLE, 1, PRINCIPAL, payableStatus);

            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            LocalDate day = now.toLocalDate();
            int year = day.getYear();
            long ctl = db.nextControlNumber(branch, MODULE, ShareWithdrawalRules.CASH_VOUCHER, year);
            String docNo = SharePaymentRules.documentNo(null, ctl);
            String explanation = req.explanation() == null || req.explanation().isBlank() ? "SC WITHDRAWAL" : req.explanation().trim();
            explanation = explanation.substring(0, Math.min(explanation.length(), 200));
            jdbc.update("""
                    INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, TR_CLIENTID, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """, branch, year, MODULE, ShareWithdrawalRules.CASH_VOUCHER, ctl, docNo, day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), req.clientId(),
                day.format(DateTimeFormatter.BASIC_ISO_DATE), explanation, Timestamp.valueOf(now));
            String sql = """
                    INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                      DTSLE_CODE, DTCTS_NUM, DTSL_DATE, DTADJ_FLAG, DTAMT, DTENCDBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0, 0, 0)
                    """;
            jdbc.update(sql, branch, year, MODULE, ShareWithdrawalRules.CASH_VOUCHER, ctl, 0, p.dept(), shareGl, req.clientId(), SHARE, req.typeCode(),
                req.refNo(), PRINCIPAL, a.status(), day.toString(), amount, p.id(), PENDING);
            jdbc.update(sql, branch, year, MODULE, ShareWithdrawalRules.CASH_VOUCHER, ctl, 1, p.dept(), payableGl, req.clientId(), PAYABLE, 1,
                payable.get(0), PRINCIPAL, payableStatus, day.toString(), amount.negate(), p.id(), PENDING);
            jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, 'share.withdrawal.encode', 'transsum', ?, ?)",
                Timestamp.valueOf(now), auth.getName(), branch + "/" + year + "/" + MODULE + "/" + ShareWithdrawalRules.CASH_VOUCHER + "/" + ctl,
                "client " + req.clientId() + " type " + req.typeCode() + " ref " + req.refNo() + " amount " + amount.toPlainString() + " doc " + docNo);
            return new Encoded(branch, year, ctl, docNo, req.clientId(), c.name(), amount, a.balance().subtract(pending).subtract(amount));
        });
    }
}
