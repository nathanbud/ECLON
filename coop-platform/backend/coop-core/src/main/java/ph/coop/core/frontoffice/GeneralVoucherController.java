package ph.coop.core.frontoffice;

import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.accounting.ledger.Ledger;
import ph.coop.core.accounting.ledger.Money;
import ph.coop.core.accounting.ledger.WaitingVoucher;
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.loan.SingleHandGrants;

/**
 * Cash Vouchers (type 21) and Check Vouchers (type 22) waiting for approval, other than loan releases, share withdrawals and time deposit payouts, which
 * have their own list (Loan releases waiting). Approving needs New on the type and must be someone other than the encoder; posting needs New on the type
 * and must be someone other than both (as for releases; the old system let the encoder post). The ledger writes them
 * ({@link Ledger#approveWaiting}, {@link Ledger#postWaiting}); see documentation/general-voucher-map.md.
 */
@RestController
@RequestMapping("/api/front-office/vouchers")
class GeneralVoucherController {

    record Line(int seq, String account, String description, Integer slc, Integer slt, Integer refNo, BigDecimal amount) {}

    record Cheque(int bankCode, String bank, String number, String date, String payee) {}

    record Item(int year, int code, String type, long controlNo, String documentNo, String date, int clientId, String clientName, String explanation, BigDecimal amount,
                String status, int encodedBy, String encodedByName, Integer approvedBy, String approvedByName, Cheque cheque) {}

    record Detail(Item voucher, List<Line> lines, boolean mayApprove, boolean mayPost, List<String> problems) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final Ledger ledger;
    private final SingleHandGrants single;
    private final TransactionTemplate tx;

    GeneralVoucherController(JdbcTemplate jdbc, Db db, Clock clock, FrontOfficeAccess access, MemberLedgerPoster poster, Ledger ledger, SingleHandGrants single,
                             PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.access = access;
        this.poster = poster;
        this.ledger = ledger;
        this.single = single;
        this.tx = new TransactionTemplate(txm);
    }

    /** A release, share withdrawal or time deposit payout has a debit on a loan (12), time deposit (24) or share capital (31) principal or interest line. */
    static final String NOT_A_RELEASE = """
             AND NOT EXISTS (SELECT 1 FROM transdt r WHERE r.DTTR_BRCODE = s.TR_BRCODE AND r.DTTR_YEAR = s.TR_YEAR AND r.DTTR_MODULE = s.TR_MODULE
                   AND r.DTTR_CODE = s.TR_CODE AND r.DTTR_CTLNO = s.TR_CTLNO AND r.DTSLC_CODE IN (12, 24, 31) AND r.DTSLE_CODE IN (11, 71) AND r.DTAMT > 0)
            """;

    /** The Cash and Check Vouchers of this year and last that wait for approval or posting. */
    @GetMapping("/waiting")
    List<Item> waiting(Authentication auth) {
        Perms perms = access.of(auth);
        if (!perms.canFind(21) && !perms.canFind(22) && !perms.canNew(21) && !perms.canNew(22)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot look up vouchers.");
        int year = LocalDate.now(clock).getYear();
        List<long[]> keys = jdbc.query("""
                SELECT s.TR_YEAR, s.TR_CODE, s.TR_CTLNO FROM transsum s
                WHERE s.TR_BRCODE = ? AND s.TR_YEAR >= ? AND s.TR_MODULE = 2 AND s.TR_CODE IN (21, 22)
                  AND EXISTS (SELECT 1 FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                              AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO AND d.DTUPD_TAG = 2)
                """ + NOT_A_RELEASE + " ORDER BY s.TR_YEAR DESC, s.TR_CTLNO DESC LIMIT 200",
            (rs, i) -> new long[] {rs.getInt(1), rs.getInt(2), rs.getLong(3)}, perms.branch(), year - 1);
        List<Item> out = new ArrayList<>();
        for (long[] k : keys) {
            if (!perms.canFind((int) k[1]) && !perms.canNew((int) k[1])) continue;
            out.add(item(perms.branch(), (int) k[0], (int) k[1], k[2]));
        }
        return out;
    }

    @GetMapping("/{code}/{year}/{controlNo}")
    Detail detail(@PathVariable int code, @PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        requireType(code);
        access.requireFind(perms, code);
        return load(perms, auth, code, year, controlNo);
    }

    @PostMapping("/{code}/{year}/{controlNo}/approve")
    Detail approve(@PathVariable int code, @PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        requireType(code);
        access.requireNew(perms, code);
        Person p = poster.person(auth.getName());
        tx.executeWithoutResult((st) -> {
            ledger.voucherLines(p.branch(), year, code, controlNo, true);
            Detail d = load(perms, auth, code, year, controlNo);
            if (!d.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", d.problems()));
            if (!"WAITING FOR APPROVAL".equals(d.voucher().status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "This voucher is not waiting for approval.");
            if (d.voucher().encodedBy() == p.id() && !single.has(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You encoded this voucher, so someone else must approve it.");
            ledger.approveWaiting(p.branch(), year, code, controlNo, p.id(), auth.getName());
        });
        return load(perms, auth, code, year, controlNo);
    }

    @PostMapping("/{code}/{year}/{controlNo}/post")
    Detail post(@PathVariable int code, @PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        requireType(code);
        access.requireNew(perms, code);
        Person p = poster.person(auth.getName());
        tx.executeWithoutResult((st) -> {
            ledger.voucherLines(p.branch(), year, code, controlNo, true);
            Detail d = load(perms, auth, code, year, controlNo);
            Item v = d.voucher();
            if (!d.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", d.problems()));
            if (!"APPROVED".equals(v.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "WAITING FOR APPROVAL".equals(v.status()) ? "This voucher cannot be posted yet because nobody has approved it. Another person with the right to approve vouchers must approve it first." : "This voucher is already posted.");
            if ((v.approvedBy() != null && v.approvedBy() == p.id() || v.encodedBy() == p.id()) && !single.has(auth.getName()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The person who encoded or approved a voucher cannot post it.");
            // savings paid by cheque: the balance is checked again when the money leaves
            for (Line l : d.lines()) {
                if (l.slc() != null && l.slc() == DraftPostRules.SAVINGS_CLASS && l.amount().signum() > 0) {
                    MemberLedgerPoster.Account a = poster.account(MemberLedgerPoster.Master.SAVINGS, p.branch(), v.clientId(), l.slt(), l.refNo());
                    SavingsRules.Check w = SavingsRules.checkWithdrawal(l.amount(), a.status(), a.balance(), a.held(), BigDecimal.ZERO, 1, null, l.amount());
                    if (!w.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", w.problems()));
                }
            }
            WaitingVoucher.OwnCheque cheque = v.cheque() == null ? null
                : new WaitingVoucher.OwnCheque(v.cheque().number(), v.cheque().bankCode(), LocalDate.parse(v.cheque().date()), v.cheque().payee(), Money.of(v.amount()));
            ledger.postWaiting(p.branch(), year, code, controlNo, v.documentNo(), v.clientId(), p.id(), cheque, p.dept(), auth.getName());
        });
        return load(perms, auth, code, year, controlNo);
    }

    // ---- loading ------------------------------------------------------------------------------------------------------

    private static void requireType(int code) {
        if (code != 21 && code != 22) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Only Cash Vouchers and Check Vouchers are handled here.");
    }

    private Detail load(Perms perms, Authentication auth, int code, int year, long ctl) {
        Integer releases = jdbc.queryForObject("SELECT COUNT(*) FROM transsum s WHERE s.TR_BRCODE = ? AND s.TR_YEAR = ? AND s.TR_MODULE = 2 AND s.TR_CODE = ? AND s.TR_CTLNO = ?" + NOT_A_RELEASE,
            Integer.class, perms.branch(), year, code, ctl);
        Item v = item(perms.branch(), year, code, ctl);
        if (releases == null || releases == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This is a loan release, share withdrawal or time deposit voucher; it is approved under Loan releases waiting.");
        List<Line> lines = ledger.voucherLines(perms.branch(), year, code, ctl, false).stream()
            .map((l) -> new Line(l.seq(), l.account(), describe(perms.branch(), l.account()), l.slc(), l.slt(), l.refNo(), l.amount())).toList();
        List<String> problems = new ArrayList<>();
        if (lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add).signum() != 0) problems.add("The voucher does not balance.");
        if (code == 22 && v.cheque() == null && !"POSTED".equals(v.status())) problems.add("This Check Voucher has no cheque recorded in this application, so it cannot be posted here.");
        boolean mine = perms.canNew(code);
        int me = perms.userId();
        boolean alone = single.has(auth.getName());
        boolean mayApprove = mine && "WAITING FOR APPROVAL".equals(v.status()) && (v.encodedBy() != me || alone) && problems.isEmpty();
        boolean mayPost = mine && "APPROVED".equals(v.status()) && ((v.approvedBy() == null || v.approvedBy() != me) && v.encodedBy() != me || alone) && problems.isEmpty();
        return new Detail(v, lines, mayApprove, mayPost, problems);
    }

    private Item item(int branch, int year, int code, long ctl) {
        List<Item> found = jdbc.query("""
                SELECT s.TR_DOCNO, s.TR_DATE, COALESCE(s.TR_CLIENTID, 0), COALESCE(s.EXPLANATION, ''),
                  (SELECT COALESCE(SUM(d.DTAMT), 0) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                     AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO AND d.DTAMT > 0),
                  (SELECT MIN(d.DTUPD_TAG) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                     AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO),
                  (SELECT COALESCE(MAX(d.DTENCDBY), 0) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                     AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO),
                  (SELECT MAX(d.DTAPPRVBY) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                     AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO)
                FROM transsum s WHERE s.TR_BRCODE = ? AND s.TR_YEAR = ? AND s.TR_MODULE = 2 AND s.TR_CODE = ? AND s.TR_CTLNO = ?
                """, (rs, i) -> {
            int tag = rs.getInt(6);
            int apr = rs.getInt(8);
            boolean aprNull = rs.wasNull();
            int client = rs.getInt(3);
            String status = tag == 1 ? "POSTED" : aprNull ? "WAITING FOR APPROVAL" : "APPROVED";
            return new Item(year, code, code == 21 ? "Cash Voucher" : "Check Voucher", ctl, rs.getString(1), rs.getString(2), client, clientName(branch, client), rs.getString(4),
                rs.getBigDecimal(5), status, rs.getInt(7), userName(branch, rs.getInt(7)), aprNull ? null : apr, aprNull ? null : userName(branch, apr), cheque(branch, year, code, ctl));
        }, branch, year, code, ctl);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That voucher was not found. It may have been cancelled or the number mistyped: reload the list and open it from there.");
        return found.get(0);
    }

    private Cheque cheque(int branch, int year, int code, long ctl) {
        if (code != 22 || !db.tableExists("ecis_voucher_cheque")) return null;
        return jdbc.query("""
                SELECT c.bank_code, COALESCE(NULLIF(b.BCDesc, ''), CONCAT('Bank ', c.bank_code)), c.cheque_no, c.cheque_date, c.payee
                FROM ecis_voucher_cheque c LEFT JOIN bankcode b ON b.BCBR_CODE = c.branch_code AND b.BCID = c.bank_code
                WHERE c.branch_code = ? AND c.tr_year = ? AND c.tr_code = ? AND c.tr_ctlno = ?
                """, (rs, i) -> new Cheque(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate().toString(), rs.getString(5)), branch, year, code, ctl)
            .stream().findFirst().orElse(null);
    }

    private String clientName(int branch, int client) {
        if (client <= 0) return null;
        return jdbc.queryForList("SELECT COALESCE(NULLIF(AccountName, ''), CONCAT_WS(', ', LName, FName)) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", String.class, branch, client)
            .stream().findFirst().orElse(null);
    }

    private String userName(int branch, int id) {
        if (id <= 0) return null;
        List<String> n = jdbc.queryForList("SELECT CONCAT_WS(' ', AccessFName, AccessLName) FROM access WHERE AccessBR_CODE = ? AND AccessID = ?", String.class, branch, id);
        return n.isEmpty() ? "#" + id : n.get(0);
    }

    private String describe(int branch, String account) {
        return jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, branch, account).stream().findFirst().orElse("");
    }
}
