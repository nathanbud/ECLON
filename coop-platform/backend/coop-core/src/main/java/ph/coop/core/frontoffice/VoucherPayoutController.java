package ph.coop.core.frontoffice;


import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.HandoverRules.Drawer;
import ph.coop.core.frontoffice.MemberLedgerPoster.Client;
import ph.coop.core.frontoffice.MemberLedgerPoster.Direction;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.frontoffice.MemberLedgerPoster.Posted;
import ph.coop.core.frontoffice.SavingsPostingController.NeedStatus;
import ph.coop.core.frontoffice.SavingsRules.Need;

/**
 * Pays a member's vouchers-payable balance out in cash, as the old Cash Voucher payment did (module 2, type 24; documentation/voucher-payout-map.md).
 * Loan releases, share withdrawals and time deposit payouts all end in a credit to this account; this is the step that gives the member the money. The whole
 * balance is paid at once from the cashier's own drawer and posted straight away. A payment over the cashier's limit needs a supervisor's approval, asked
 * through the front office overrides like a savings withdrawal.
 */
@RestController
@RequestMapping("/api/front-office/voucher-payouts")
class VoucherPayoutController {

    private static final int PRINCIPAL = 11;
    /** How far back a posted voucher is looked for when listing who is waiting for money. */
    private static final int WAITING_DAYS = 45;

    record Payable(int clientId, String client, int refNo, int status, BigDecimal balance, LocalDate lastCredit) {}

    record Request(int clientId, int refNo, String explanation, String receiptNo) {}

    record Check(Payable payable, List<String> problems, List<NeedStatus> needs, BigDecimal drawerCash, boolean ready) {}

    record Receipt(int branchCode, int year, long controlNo, String documentNo, String date, int clientId, String clientName, int refNo, BigDecimal amount, String cashier) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final DrawerBalances balances;
    private final TransactionTemplate tx;

    VoucherPayoutController(JdbcTemplate jdbc, Db db, Clock clock, FrontOfficeAccess access, MemberLedgerPoster poster, DrawerBalances balances, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.access = access;
        this.poster = poster;
        this.balances = balances;
        this.tx = new TransactionTemplate(txm);
    }

    // ---- lists --------------------------------------------------------------------------------------------------

    /** Accounts credited by a posted voucher in the last weeks that still hold money: the people waiting to be paid. */
    @GetMapping("/waiting")
    List<Payable> waiting(Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, VoucherPayoutRules.TRANSACTION);
        int branch = perms.branch();
        LocalDate today = LocalDate.now(clock);
        return jdbc.query("""
                SELECT z.cid, COALESCE(NULLIF(k.AccountName, ''), CONCAT_WS(', ', k.LName, k.FName)), z.ref, z.last_date, z.bal FROM (
                  SELECT x.ClientIDDT AS cid, x.DTREF_NO AS ref, MAX(t.TR_DATE) AS last_date,
                    (SELECT COALESCE(-SUM(l.AMT), 0) FROM sldtl l WHERE l.SL_BRCODE = t.TR_BRCODE AND l.SL_CLIENTID = x.ClientIDDT AND l.SLC_CODE = 29 AND l.SLT_CODE = 1
                       AND l.REF_NO = x.DTREF_NO AND l.SLE_CODE = 11) AS bal
                  FROM transsum t JOIN transdt x ON x.DTTR_BRCODE = t.TR_BRCODE AND x.DTTR_YEAR = t.TR_YEAR AND x.DTTR_MODULE = t.TR_MODULE AND x.DTTR_CODE = t.TR_CODE AND x.DTTR_CTLNO = t.TR_CTLNO
                  WHERE t.TR_BRCODE = ? AND t.TR_YEAR IN (?, ?) AND t.TR_MODULE = 2 AND t.TR_CODE IN (20, 21, 22) AND t.TR_DATE >= ?
                    AND x.DTSLC_CODE = 29 AND x.DTSLT_CODE = 1 AND x.DTAMT < 0 AND x.DTUPD_TAG IN (1, 5)
                  GROUP BY t.TR_BRCODE, x.ClientIDDT, x.DTREF_NO
                ) z LEFT JOIN client k ON k.ClientIDBrCode = ? AND k.ClientID = z.cid
                WHERE z.bal > 0.005 ORDER BY z.last_date DESC, z.cid LIMIT 200
                """, (rs, i) -> new Payable(rs.getInt(1), rs.getString(2), rs.getInt(3), VoucherPayoutRules.ACTIVE, rs.getBigDecimal(5), rs.getDate(4).toLocalDate()),
            branch, today.getYear(), today.getYear() - 1, java.sql.Date.valueOf(today.minusDays(WAITING_DAYS)), branch);
    }

    /** One member's vouchers-payable accounts that hold money. */
    @GetMapping("/member/{clientId}")
    List<Payable> member(@PathVariable int clientId, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, VoucherPayoutRules.TRANSACTION);
        int branch = perms.branch();
        List<Payable> out = new ArrayList<>();
        for (int[] a : jdbc.query("SELECT OSLREF_NO, OSLSTATUS FROM osl WHERE OSLBR_CODE = ? AND ClientIDOSL = ? AND OSLSLC_CODE = 29 AND OSLSLT_CODE = 1 ORDER BY OSLREF_NO",
            (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, branch, clientId)) {
            BigDecimal bal = balanceOf(branch, clientId, a[0]);
            if (bal.signum() > 0) out.add(new Payable(clientId, poster.client(branch, clientId).name(), a[0], a[1], bal, null));
        }
        return out;
    }

    // ---- check and pay ------------------------------------------------------------------------------------------

    @PostMapping("/check")
    Check check(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, VoucherPayoutRules.TRANSACTION);
        Person p = poster.person(auth.getName());
        return evaluate(req, p, false);
    }

    @PostMapping
    Receipt pay(@RequestBody Request req, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, VoucherPayoutRules.TRANSACTION);
        Person p = poster.person(auth.getName());
        return tx.execute((st) -> {
            Check c = evaluate(req, p, true);
            if (!c.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", c.problems()));
            BigDecimal amount = c.payable().balance();

            // an approval the old system let a supervisor give is spent here, once
            List<long[]> spent = new ArrayList<>();
            for (NeedStatus n : c.needs()) {
                List<long[]> found = jdbc.query("""
                        SELECT id, decided_by FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ?
                          AND status = 'APPROVED' AND used_ref IS NULL ORDER BY id LIMIT 1 FOR UPDATE
                        """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, p.branch(), n.kind(), p.id(), amount, n.description());
                if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A supervisor has to allow this first: " + n.title() + ".");
                spent.add(found.get(0));
            }

            String glAccount = poster.glAccount(p.branch(), VoucherPayoutRules.PAYABLE_CLASS, VoucherPayoutRules.PAYABLE_TYPE, PRINCIPAL, c.payable().status());
            MemberLine line = new MemberLine(VoucherPayoutRules.PAYABLE_CLASS, VoucherPayoutRules.PAYABLE_TYPE, req.refNo(), PRINCIPAL, c.payable().status(), glAccount, amount);
            String explanation = req.explanation() == null || req.explanation().isBlank() ? "VOUCHER PAYMENT" : req.explanation();
            // a second payment to the same member inside a minute is allowed: each account is checked against its own balance
            Posted r = poster.postVoucher(Direction.PAYABLE_OUT, p, req.clientId(), explanation, req.receiptNo(), true, List.of(line), "voucher.payout.post", auth.getName(),
                "payable ref " + req.refNo() + " amount " + amount.toPlainString());

            String ref = r.branch() + "/" + r.year() + "/2/24/" + r.controlNo();
            for (long[] o : spent) {
                jdbc.update("UPDATE ecis_fo_override SET used_ref = ? WHERE id = ?", ref, o[0]);
                AuditLog.write(jdbc, null, auth.getName(), "voucher.payout.override.used", "fo_override", String.valueOf(o[0]), null, "allowed by user " + o[1] + " for " + ref + " amount " + amount.toPlainString(), null);
            }
            return new Receipt(r.branch(), r.year(), r.controlNo(), r.documentNo(), r.date().toString(), req.clientId(), c.payable().client(), req.refNo(), amount, p.name());
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private BigDecimal balanceOf(int branch, int clientId, int refNo) {
        return jdbc.queryForObject("""
                SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = 29 AND SLT_CODE = 1 AND REF_NO = ? AND SLE_CODE = 11
                """, BigDecimal.class, branch, clientId, refNo);
    }

    /** Everything that stands in the way, and what a supervisor would have to allow. When {@code lock}, the account row is locked so two cashiers cannot pay it twice. */
    private Check evaluate(Request req, Person p, boolean lock) {
        List<Integer> status = jdbc.queryForList("SELECT OSLSTATUS FROM osl WHERE OSLBR_CODE = ? AND ClientIDOSL = ? AND OSLSLC_CODE = 29 AND OSLSLT_CODE = 1 AND OSLREF_NO = ?"
            + (lock ? " FOR UPDATE" : ""), Integer.class, p.branch(), req.clientId(), req.refNo());
        if (status.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That client has no such vouchers-payable account.");
        Client c = poster.client(p.branch(), req.clientId());
        BigDecimal balance = balanceOf(p.branch(), req.clientId(), req.refNo());
        List<Integer> events = HandoverController.eventsOf(p.head(), p.assignment());
        BigDecimal cash = events.isEmpty() || p.number() <= 0 ? BigDecimal.ZERO : balances.of(p.branch(), new Drawer(events.get(0), p.number()));
        BigDecimal limit = p.disbursementLimit().min(p.maxWithdrawal());
        VoucherPayoutRules.Check rules = VoucherPayoutRules.check(balance, status.get(0) == null ? 0 : status.get(0), c.status(), cash, limit);
        Payable payable = new Payable(req.clientId(), c.name(), req.refNo(), status.get(0) == null ? 0 : status.get(0), balance, null);
        List<NeedStatus> needs = new ArrayList<>();
        for (Need n : rules.needs()) needs.add(statusOf(n, payable, p));
        boolean ready = rules.problems().isEmpty() && needs.stream().allMatch((n) -> "APPROVED".equals(n.status()));
        return new Check(payable, rules.problems(), needs, cash, ready);
    }

    /** The exact text of a request for this payment; the same text is used to ask, to look up the answer, and to spend it. */
    private static String describe(Need n, Payable a) {
        String text = "Voucher payment of " + a.balance().toPlainString() + " to client " + a.clientId() + " (payable " + a.refNo() + "): " + n.title();
        return text.substring(0, Math.min(text.length(), 250));
    }

    private NeedStatus statusOf(Need n, Payable a, Person p) {
        String description = describe(n, a);
        String status = "NONE";
        if (db.tableExists("ecis_fo_override") && db.columnExists("ecis_fo_override", "used_ref")) {
            List<String> latest = jdbc.queryForList("""
                    SELECT status FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ? AND used_ref IS NULL
                    ORDER BY id DESC LIMIT 1
                    """, String.class, p.branch(), n.kind(), p.id(), a.balance(), description);
            if (!latest.isEmpty()) status = latest.get(0);
        }
        return new NeedStatus(n.kind(), n.title(), description, a.balance(), n.limit(), status);
    }
}
