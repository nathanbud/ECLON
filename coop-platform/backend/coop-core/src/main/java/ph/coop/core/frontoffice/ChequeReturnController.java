package ph.coop.core.frontoffice;


import ph.coop.core.accounting.close.PostingPeriods;
import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.HandoverRules.Drawer;
import ph.coop.core.frontoffice.MemberLedgerPoster.Master;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;

/**
 * A cheque the bank sent back. The old system records it as a General Journal (module 1, type 1) mapped in documentation/cheque-receipt-posting-map.md:
 * the member's savings account is debited by the cheque (one ledger row, event 11, adjustment flag 1, no tender type) and the place the cheque
 * is now is credited: a teller's or cashier's cheque account (sub-ledger type 3), the vault's, or the bank account. The original receipt rows are
 * never touched, and the cheque stays marked cleared. The person who posts and a different supervisor, who approves through the same override
 * request used for loan penalties (kind CHEQUE_RETURN), are on the journal. Only cheques that paid a savings deposit are handled.
 */
@RestController
@RequestMapping("/api/front-office/cheque-returns")
class ChequeReturnController {

    static final String KIND = "CHEQUE_RETURN";

    record Candidate(int year, long ctlNo, int seq, String documentNo, String receiptDate, int clientId, String client, String number, String bank, BigDecimal amount,
                     Integer typeCode, Integer refNo, boolean returned, String problem) {}

    /** where: DRAWER (event 52 or 53 and its number), VAULT, or BANK (bankId). */
    record Request(int year, long ctlNo, int seq, String where, Integer event, Integer number, Integer bankId) {}

    record Check(String description, BigDecimal amount, String approval, List<String> problems, boolean ready) {}

    record Posted(int year, long controlNo, String documentNo, String client, BigDecimal amount, String where) {}

    private record Found(Candidate c, int branch) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final MemberLedgerPoster poster;
    private final BankCashController banks;
    private final OverrideController overrides;
    private final DrawerBalances balances;
    private final TransactionTemplate tx;

    ChequeReturnController(JdbcTemplate jdbc, Db db, Clock clock, MemberLedgerPoster poster, BankCashController banks, OverrideController overrides, DrawerBalances balances,
                           PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.poster = poster;
        this.banks = banks;
        this.overrides = overrides;
        this.balances = balances;
        this.tx = new TransactionTemplate(txm);
    }

    private static boolean role(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_" + role));
    }

    private Person who(Authentication auth) {
        Person p = poster.person(auth.getName());
        if (!p.head() && !role(auth, "ADMIN") && !role(auth, "ACCOUNTANT"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier, an accountant or an administrator records a returned cheque.");
        return p;
    }

    private static String key(int year, long ctl, int seq) { return year + "/" + ctl + "/" + seq; }

    /** The received cheques with this number (any bank), newest first, with what each one paid. */
    @GetMapping("/find")
    List<Candidate> find(@RequestParam String number, Authentication auth) {
        return findFor(who(auth), number);
    }

    private List<Candidate> findFor(Person p, String number) {
        String n = ChequeRules.number(number);
        if (n == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type the cheque number (digits only, up to 15).");
        int from = LocalDate.now(clock).getYear() - 3;
        return jdbc.query("""
                SELECT c.CKTR_YEAR, c.CKTR_CTLNO, c.CKSEQ_NO, t.TR_DOCNO, t.TR_DATE, COALESCE(t.TR_CLIENTID, 0), t.CLIENTNAME, c.CKCHECKNO, COALESCE(NULLIF(b.BCDesc2, ''), b.BCDesc), c.CKAMT
                FROM transck c
                JOIN transsum t ON t.TR_BRCODE = c.CKTR_BRCODE AND t.TR_YEAR = c.CKTR_YEAR AND t.TR_MODULE = c.CKTR_MODULE AND t.TR_CODE = c.CKTR_CODE AND t.TR_CTLNO = c.CKTR_CTLNO
                LEFT JOIN bankcode b ON b.BCBR_CODE = c.CKTR_BRCODE AND b.BCID = c.CKBANK_CODE
                WHERE c.CKTR_BRCODE = ? AND c.CKTR_YEAR >= ? AND c.CKTR_CODE = 10 AND c.CKCOCI_TYPE = 2 AND c.CKCHECKNO = ?
                ORDER BY t.TR_DATE DESC, c.CKTR_CTLNO DESC
                """, (rs, i) -> candidate(p.branch(), rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getDate(5).toLocalDate(), rs.getInt(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getBigDecimal(10)), p.branch(), from, n);
    }

    /** What the cheque paid, from the ledger rows of its receipt. A cheque that paid anything but a savings deposit is not handled yet. */
    private Candidate candidate(int branch, int year, long ctl, int seq, String doc, LocalDate date, int clientId, String clientName, String number, String bank, BigDecimal amount) {
        List<Object[]> rows = jdbc.query("""
                SELECT SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, AMT FROM sldtl
                WHERE SL_BRCODE = ? AND TR_CODE = 10 AND TR_CTLNO = ? AND CHECKNO = ? AND COCI_TYPE = 2 AND TR_DATE = ?
                """, (rs, i) -> new Object[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getBigDecimal(6)}, branch, ctl, number, date.toString());
        String problem = null;
        Integer type = null, ref = null;
        BigDecimal paid = BigDecimal.ZERO;
        if (rows.isEmpty()) problem = "The ledger has no row paid by this cheque.";
        for (Object[] r : rows) {
            if ((int) r[1] != 22 || (int) r[4] != 11) { problem = "This cheque paid something other than a savings deposit. Returning those is not built yet."; break; }
            if (type != null && (!type.equals(r[2]) || !ref.equals(r[3]))) { problem = "This cheque paid more than one account. Returning it is not built yet."; break; }
            type = (Integer) r[2];
            ref = (Integer) r[3];
            paid = paid.add(((BigDecimal) r[5]).negate());
        }
        if (problem == null && paid.compareTo(amount) != 0) problem = "The ledger shows " + paid.toPlainString() + " paid by this cheque, not " + amount.toPlainString() + ".";
        String name = clientName;
        if (name == null || name.isBlank()) {
            name = jdbc.queryForList("SELECT COALESCE(NULLIF(AccountName, ''), CONCAT_WS(', ', LName, FName)) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", String.class, branch, clientId)
                .stream().findFirst().orElse("");
        }
        return new Candidate(year, ctl, seq, doc, date.toString(), clientId, name, number, bank, amount, type, ref, isReturned(key(year, ctl, seq)), problem);
    }

    private boolean isReturned(String key) {
        if (!db.tableExists("ecis_fo_override") || !db.columnExists("ecis_fo_override", "used_ref")) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_fo_override WHERE kind = ? AND used_ref IS NOT NULL AND description LIKE ?", Integer.class, KIND, "RETURN " + key + " %");
        return n != null && n > 0;
    }

    private Candidate one(Person p, Request r) {
        String number = jdbc.queryForList("SELECT CKCHECKNO FROM transck WHERE CKTR_BRCODE = ? AND CKTR_YEAR = ? AND CKTR_CODE = 10 AND CKTR_CTLNO = ? AND CKSEQ_NO = ? AND CKCOCI_TYPE = 2",
            String.class, p.branch(), r.year(), r.ctlNo(), r.seq()).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such cheque."));
        return findFor(p, number).stream().filter((c) -> c.year() == r.year() && c.ctlNo() == r.ctlNo() && c.seq() == r.seq()).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such cheque."));
    }

    /** The place the cheque is now, as a short text and the ledger line that is credited. */
    private record Place(String text, String account, Integer slt, Integer event, Integer number, BigDecimal held) {}

    private Place place(Person p, Request r, Authentication auth) {
        String where = r.where() == null ? "" : r.where();
        switch (where) {
            case "DRAWER": {
                if (r.event() == null || r.number() == null || (r.event() != HandoverRules.CASHIER && r.event() != HandoverRules.TELLER))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the cashier or teller who holds the cheque.");
                Drawer d = new Drawer(r.event(), r.number());
                return new Place(d.label(), HandoverRules.chequeAccount(d.event()), 3, HandoverRules.chequeEvent(d.event()), d.number(), balances.chequesOf(p.branch(), d));
            }
            case "VAULT": {
                Drawer d = new Drawer(HandoverRules.VAULT, HandoverRules.VAULT_NUMBER);
                return new Place("Vault", HandoverRules.chequeAccount(d.event()), 3, HandoverRules.chequeEvent(d.event()), d.number(), balances.chequesOf(p.branch(), d));
            }
            case "BANK": {
                BankCashController.BankRow b = banks.banks(auth).stream().filter((x) -> r.bankId() != null && x.bankId() == r.bankId()).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the bank account the cheque was deposited to."));
                return new Place(b.name(), b.ledgerAccount(), null, null, null, null);
            }
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say where the cheque is now: with a cashier or teller, in the vault, or in the bank.");
        }
    }

    private String description(Candidate c, Place pl) {
        return "RETURN " + key(c.year(), c.ctlNo(), c.seq()) + " " + c.number() + " TO " + pl.text();
    }

    private String approvalStatus(Person p, BigDecimal amount, String description) {
        if (!db.tableExists("ecis_fo_override") || !db.columnExists("ecis_fo_override", "used_ref")) return "NONE";
        List<String> latest = jdbc.queryForList("""
                SELECT status FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ? AND used_ref IS NULL ORDER BY id DESC LIMIT 1
                """, String.class, p.branch(), KIND, p.id(), amount, description);
        return latest.isEmpty() ? "NONE" : latest.get(0);
    }

    private List<String> problems(Candidate c, Place pl, Person p) {
        List<String> out = new ArrayList<>();
        if (c.problem() != null) out.add(c.problem());
        if (c.returned()) out.add("This cheque has already been recorded as returned.");
        if (pl.held() != null && pl.held().compareTo(c.amount()) < 0)
            out.add(pl.text() + " holds only " + pl.held().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " in cheques. Choose where the cheque really is, or move the cheques there first.");
        if (p.dept() <= 0) out.add("Your account has no department set, so this cannot be posted.");
        return out;
    }

    /** What posting would do, and where the supervisor's approval stands. */
    @PostMapping("/check")
    Check check(@RequestBody Request req, Authentication auth) {
        Person p = who(auth);
        Candidate c = one(p, req);
        Place pl = place(p, req, auth);
        String d = description(c, pl);
        List<String> problems = problems(c, pl, p);
        String approval = approvalStatus(p, c.amount(), d);
        return new Check(d, c.amount(), approval, problems, problems.isEmpty() && "APPROVED".equals(approval));
    }

    /** Ask a different supervisor to approve this return. */
    @PostMapping("/ask")
    OverrideController.Override ask(@RequestBody Request req, Authentication auth) {
        Person p = who(auth);
        Candidate c = one(p, req);
        Place pl = place(p, req, auth);
        List<String> problems = problems(c, pl, p);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", problems));
        return overrides.ask(new OverrideController.Request(null, KIND, description(c, pl), c.amount(), null), auth);
    }

    @PostMapping("/post")
    Posted post(@RequestBody Request req, Authentication auth) {
        Person p = who(auth);
        if (!db.canUpdateColumn("transck", "CKISCLEARED") || !db.canInsertInto("transsum", "transdt", "gltrans", "sldtl"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Returned cheques are not set up yet. An administrator has to run database/migrations/V026_cheque_clearing.sql.");
        return tx.execute((st) -> {
            Candidate c = one(p, req);
            Place pl = place(p, req, auth);
            List<String> problems = problems(c, pl, p);
            if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", problems));
            String d = description(c, pl);
            List<long[]> found = jdbc.query("""
                    SELECT id, decided_by FROM ecis_fo_override WHERE branch_code = ? AND kind = ? AND requested_by = ? AND amount = ? AND description = ?
                      AND status = 'APPROVED' AND used_ref IS NULL ORDER BY id LIMIT 1 FOR UPDATE
                    """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, p.branch(), KIND, p.id(), c.amount(), d);
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A supervisor has to approve this return first.");
            long approver = found.get(0)[1];

            MemberLedgerPoster.Account acct = poster.account(Master.SAVINGS, p.branch(), c.clientId(), c.typeCode(), c.refNo());
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            LocalDate day = now.toLocalDate();
            int year = day.getYear();
            PostingPeriods.requireOpen(jdbc, p.branch(), day);
            long ctl = db.nextControlNumber(p.branch(), 1, 1, year);
            String docNo = SharePaymentRules.documentNo(null, ctl);
            String explanation = "RETURNED CHECK " + c.number() + " deposited " + LocalDate.parse(c.receiptDate()).format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) + " " + c.documentNo();
            Timestamp stamp = Timestamp.valueOf(now);
            jdbc.update("""
                    INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_CLIENTID, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                    VALUES (?, ?, 1, 1, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """, p.branch(), year, ctl, docNo, c.clientId(), day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), day.format(DateTimeFormatter.BASIC_ISO_DATE),
                explanation.substring(0, Math.min(explanation.length(), 200)), stamp);

            String memberAccount = poster.glAccount(p.branch(), 22, c.typeCode(), 11, acct.status());
            String sql = """
                    INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSLC_CODE, DTSLT_CODE, DTREF_NO, DTSLE_CODE, DTCTS_NUM,
                      DTSL_DATE, DTAMT, DTAMTDUE, DTENCDBY, DTAPPRVBY, DTPOSTBY, DTUPD_TAG, DTREFERENCE, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                    VALUES (?, ?, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 1, '', 0, 0, 0)
                    """;
            jdbc.update(sql, p.branch(), year, ctl, 0, p.dept(), memberAccount, 22, c.typeCode(), c.refNo(), 11, acct.status(), day.toString(), c.amount(), p.id(), approver, p.id());
            jdbc.update(sql, p.branch(), year, ctl, 1, p.dept(), pl.account(), pl.slt() == null ? null : 11, pl.slt(), pl.slt() == null ? null : 1, pl.event(), pl.number(), day.toString(),
                c.amount().negate(), p.id(), approver, p.id());
            poster.writeGlLine(p.branch(), memberAccount, day, 1, p.dept(), c.amount(), stamp);
            poster.writeGlLine(p.branch(), pl.account(), day, 1, p.dept(), c.amount().negate(), stamp);
            poster.writeLedgerRow(p.branch(), c.clientId(), new MemberLine(22, c.typeCode(), c.refNo(), 11, acct.status(), memberAccount, c.amount()), day, 1, ctl, docNo, null, null);

            // the cheque is cleared in the old data even when it came back; this also releases the hold on uncleared money (the debit above already took it away)
            jdbc.update("UPDATE transck SET CKISCLEARED = 1 WHERE CKTR_BRCODE = ? AND CKTR_YEAR = ? AND CKTR_CODE = 10 AND CKTR_CTLNO = ? AND CKSEQ_NO = ? AND COALESCE(CKISCLEARED, 0) = 0",
                p.branch(), c.year(), c.ctlNo(), c.seq());
            String ref = p.branch() + "/" + year + "/1/1/" + ctl;
            jdbc.update("UPDATE ecis_fo_override SET used_ref = ? WHERE id = ?", ref, found.get(0)[0]);
            AuditLog.write(jdbc, auth.getName(), "cheque.return.post", "transsum", ref, null, "cheque " + c.number() + " " + c.amount().toPlainString() + " from " + c.documentNo() + " to " + pl.text() + ", approved by user " + approver, null);
            return new Posted(year, ctl, docNo, c.client(), c.amount(), pl.text());
        });
    }
}
