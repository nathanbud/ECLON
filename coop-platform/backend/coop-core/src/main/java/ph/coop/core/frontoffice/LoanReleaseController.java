package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;
import ph.coop.core.frontoffice.MemberLedgerPoster.MemberLine;
import ph.coop.core.frontoffice.MemberLedgerPoster.Person;
import ph.coop.core.loan.SingleHandGrants;

/**
 * Approves and posts a loan release voucher the old system already encoded (Cash Voucher, module 2 type 21, every line tagged 2; see
 * documentation/loan-posting-map.md). Approving sets DTAPPRVBY. Posting sets DTPOSTBY, moves the lines to tag 1 and writes the
 * member ledger rows (sldtl, slsum) and the general ledger lines, dated today. Encoder, approver and poster must be three different people;
 * each needs "new" rights on transaction type 21. The vouchers, and the loan row and schedule behind them, are never created or edited here.
 */
@RestController
@RequestMapping("/api/front-office/loan-releases")
class LoanReleaseController {

    private static final int MODULE = 2, CODE = 21, PENDING = 2, POSTED = 1;

    record Voucher(int branchCode, int year, long controlNo, String documentNo, String date, int clientId, String explanation,
                   BigDecimal loanAmount, int encodedBy, Integer approvedBy, String status, String encodedByName, String approvedByName, String clientName) {}

    record Line(int seq, String account, Integer slc, Integer slt, Integer refNo, Integer event, Integer cts, BigDecimal amount, int dept) {}

    record Detail(Voucher voucher, List<Line> lines, List<String> problems) {}

    private final FrontOfficeAccess access;
    private final MemberLedgerPoster poster;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SingleHandGrants single;

    LoanReleaseController(FrontOfficeAccess access, MemberLedgerPoster poster, JdbcTemplate jdbc, PlatformTransactionManager txm, Clock clock, SingleHandGrants single) {
        this.single = single;
        this.access = access;
        this.poster = poster;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    /** Release vouchers waiting to be approved or posted (this year and last). */
    @GetMapping("/pending")
    List<Voucher> pending(Authentication auth) {
        Perms perms = access.of(auth);
        access.requireFind(perms, CODE);
        int year = LocalDate.now(clock).getYear();
        return jdbc.query(VOUCHER_SQL + """
                 WHERE s.TR_BRCODE = ? AND s.TR_YEAR >= ? AND s.TR_MODULE = ? AND s.TR_CODE = ?
                   AND EXISTS (SELECT 1 FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                               AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO AND d.DTUPD_TAG = ?)
                 ORDER BY s.TR_YEAR DESC, s.TR_CTLNO DESC LIMIT 200
                """, (rs, i) -> voucher(rs), perms.branch(), year - 1, MODULE, CODE, PENDING).stream().map((v) -> named(v)).toList();
    }

    @GetMapping("/{year}/{controlNo}")
    Detail detail(@PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireFind(perms, CODE);
        return load(perms.branch(), year, controlNo, false);
    }

    @PostMapping("/{year}/{controlNo}/approve")
    Detail approve(@PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, CODE);
        Person p = poster.person(auth.getName());
        return tx.execute((st) -> {
            Detail d = load(p.branch(), year, controlNo, true);
            Voucher v = d.voucher();
            if (v.approvedBy() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This voucher is already approved.");
            if (!d.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", d.problems()));
            boolean alone = single.has(auth.getName());
            if (v.encodedBy() == p.id() && !alone) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You encoded this voucher, so someone else must approve it.");
            if (!alone && approvedApplication(p.branch(), year, controlNo) == p.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You approved the loan application, so someone else must approve its release voucher.");
            jdbc.update("UPDATE transdt SET DTAPPRVBY = ? WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG = ?",
                p.id(), p.branch(), year, MODULE, CODE, controlNo, PENDING);
            audit(auth, "loan.release.approve", p, year, controlNo, "approved by " + p.id());
            return load(p.branch(), year, controlNo, false);
        });
    }

    @PostMapping("/{year}/{controlNo}/post")
    Detail post(@PathVariable int year, @PathVariable long controlNo, Authentication auth) {
        Perms perms = access.of(auth);
        access.requireNew(perms, CODE);
        Person p = poster.person(auth.getName());
        return tx.execute((st) -> {
            Detail d = load(p.branch(), year, controlNo, true);
            Voucher v = d.voucher();
            if (!d.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", d.problems()));
            if (v.approvedBy() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This voucher has not been approved yet.");
            if ((v.approvedBy() == p.id() || v.encodedBy() == p.id()) && !single.has(auth.getName())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The person who encoded or approved a release voucher cannot post it.");
            }
            LocalDate day = LocalDate.now(clock);
            Timestamp stamp = Timestamp.valueOf(LocalDateTime.now(clock).withNano(0));
            int updated = jdbc.update("UPDATE transdt SET DTPOSTBY = ?, DTUPD_TAG = ? WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG = ?",
                p.id(), POSTED, p.branch(), year, MODULE, CODE, controlNo, PENDING);
            if (updated != d.lines().size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "The voucher changed while posting; try again.");
            for (Line l : d.lines()) {
                if (l.slc() != null && l.slc() > 0) {
                    poster.writeLedgerRow(p.branch(), v.clientId(), new MemberLine(l.slc(), l.slt(), l.refNo(), l.event(), l.cts() == null ? 99 : l.cts(), l.account(), l.amount()),
                        day, CODE, controlNo, v.documentNo());
                }
                poster.writeGlLine(p.branch(), l.account(), day, CODE, l.dept(), l.amount(), stamp);
            }
            audit(auth, "loan.release.post", p, year, controlNo, "client " + v.clientId() + " loan " + v.loanAmount().toPlainString() + " doc " + v.documentNo());
            return load(p.branch(), year, controlNo, false);
        });
    }

    // ---- loading ------------------------------------------------------------------------------------------------

    private static final String VOUCHER_SQL = """
            SELECT s.TR_BRCODE, s.TR_YEAR, s.TR_CTLNO, s.TR_DOCNO, s.TR_DATE, s.TR_CLIENTID, COALESCE(s.EXPLANATION, ''),
              (SELECT COALESCE(MAX(d.DTAMT), 0) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                 AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO AND d.DTSLC_CODE IN (12, 31) AND d.DTSLE_CODE = 11),
              (SELECT COALESCE(MAX(d.DTENCDBY), 0) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                 AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO),
              (SELECT MAX(d.DTAPPRVBY) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                 AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO),
              (SELECT MIN(d.DTUPD_TAG) FROM transdt d WHERE d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE
                 AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO)
            FROM transsum s
            """;

    private static Voucher voucher(java.sql.ResultSet rs) throws java.sql.SQLException {
        int enc = rs.getInt(9);
        int apr = rs.getInt(10);
        boolean aprNull = rs.wasNull();
        int tag = rs.getInt(11);
        return new Voucher(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getBigDecimal(8),
            enc, aprNull ? null : apr, tag == POSTED ? "POSTED" : aprNull ? "WAITING FOR APPROVAL" : "APPROVED", null, null, null);
    }

    /** Loads a voucher and checks it is a loan release in the state the old system leaves it in; lock holds its lines for the update. */
    private Detail load(int branch, int year, long ctl, boolean lock) {
        List<Voucher> found = jdbc.query(VOUCHER_SQL + " WHERE s.TR_BRCODE = ? AND s.TR_YEAR = ? AND s.TR_MODULE = ? AND s.TR_CODE = ? AND s.TR_CTLNO = ?",
            (rs, i) -> voucher(rs), branch, year, MODULE, CODE, ctl);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such voucher.");
        List<Line> lines = jdbc.query("""
                SELECT DTSEQ_NO, DTACCT_CODE, DTSLC_CODE, DTSLT_CODE, DTREF_NO, DTSLE_CODE, DTCTS_NUM, DTAMT, COALESCE(DTDEPT, 0)
                FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? ORDER BY DTSEQ_NO
                """ + (lock ? " FOR UPDATE" : ""), (rs, i) -> new Line(rs.getInt(1), rs.getString(2), (Integer) rs.getObject(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                    (Integer) rs.getObject(6), (Integer) rs.getObject(7), rs.getBigDecimal(8), rs.getInt(9)), branch, year, MODULE, CODE, ctl);
        Voucher v = named(found.get(0));
        List<String> problems = new ArrayList<>();
        if ("POSTED".equals(v.status())) {
            problems.add("This voucher is already posted.");
        } else {
            Integer states = jdbc.queryForObject("SELECT COUNT(DISTINCT DTUPD_TAG) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ?",
                Integer.class, branch, year, MODULE, CODE, ctl);
            Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG = ?",
                Integer.class, branch, year, MODULE, CODE, ctl, PENDING);
            if (states == null || states != 1 || pending == null || pending != lines.size()) problems.add("The lines of this voucher are not waiting to be posted.");
        }
        if (lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add).signum() != 0) problems.add("The voucher does not balance.");
        if (lines.stream().noneMatch((l) -> l.slc() != null && (l.slc() == 12 || l.slc() == 31) && l.event() != null && l.event() == 11 && l.amount().signum() > 0)) problems.add("This is not a loan release or share withdrawal voucher.");
        if (lines.stream().anyMatch((l) -> l.slc() != null && l.slc() > 0 && (l.slt() == null || l.refNo() == null || l.event() == null))) problems.add("A line has an incomplete ledger account.");
        if (v.clientId() <= 0) problems.add("The voucher has no client.");
        Integer cancelled = jdbc.queryForObject("SELECT COUNT(*) FROM loanapp WHERE BR_CODE = ? AND TR_CODE = ? AND TR_CTLNO = ? AND STATUSID = 5 AND APPRV_DATE >= ? AND APPRV_DATE < ?",
            Integer.class, branch, CODE, ctl, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
        if (cancelled != null && cancelled > 0 && !"POSTED".equals(v.status())) problems.add("The loan application for this voucher was cancelled, so the voucher cannot be approved or posted.");
        return new Detail(v, lines, problems);
    }

    /** Adds the names of the encoder, the approver and the client, for display. */
    private Voucher named(Voucher v) {
        String enc = userName(v.branchCode(), v.encodedBy());
        String apr = v.approvedBy() == null ? null : userName(v.branchCode(), v.approvedBy());
        List<String> client = jdbc.queryForList("SELECT CONCAT_WS(' ', LName, FName) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", String.class, v.branchCode(), v.clientId());
        return new Voucher(v.branchCode(), v.year(), v.controlNo(), v.documentNo(), v.date(), v.clientId(), v.explanation(), v.loanAmount(), v.encodedBy(), v.approvedBy(), v.status(),
            enc, apr, client.isEmpty() ? null : client.get(0));
    }

    private String userName(int branch, int id) {
        List<String> n = jdbc.queryForList("SELECT CONCAT_WS(' ', AccessFName, AccessLName) FROM access WHERE AccessBR_CODE = ? AND AccessID = ?", String.class, branch, id);
        return n.isEmpty() ? "#" + id : n.get(0);
    }

    /** The person who approved the loan application this voucher was made for, or -1 when it has no application (an older or hand-made voucher). */
    private int approvedApplication(int branch, int year, long ctl) {
        List<Integer> r = jdbc.queryForList("SELECT APPRVBY FROM loanapp WHERE BR_CODE = ? AND TR_CODE = ? AND TR_CTLNO = ? AND APPRV_DATE >= ? AND APPRV_DATE < ? AND APPRVBY IS NOT NULL ORDER BY ID DESC LIMIT 1",
            Integer.class, branch, CODE, ctl, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
        return r.isEmpty() ? -1 : r.get(0);
    }

    private void audit(Authentication auth, String action, Person p, int year, long ctl, String detail) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, 'transsum', ?, ?)",
            Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), auth.getName(), action, p.branch() + "/" + year + "/" + MODULE + "/" + CODE + "/" + ctl, detail);
    }
}
