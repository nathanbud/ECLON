package ph.coop.core.loan;


import ph.coop.core.accounting.close.PostingPeriods;
import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import ph.coop.core.db.Db;
import ph.coop.core.frontoffice.FrontOfficeAccess;

/**
 * Verify and approve a loan application (statuses New 1, Verified 2, Approved 3). Approving creates the release voucher the way the old system
 * does (documentation/loan-posting-map.md, "Approval creates the release voucher"): a Cash Voucher, module 2 type 21, every line tagged 2, encoded by
 * the application's encoder, dated today, its number stored in loanapp.TR_CTLNO. The voucher is then approved and posted on the Disburse screen
 * (LoanReleaseController). Needs AccessCanVerifyLoanApplication / AccessCanApproveLoanApplication. The verifier and the approver must each differ
 * from the encoder, and the approver from the verifier, unless a system administrator has let that person work alone ({@link SingleHandGrants}).
 */
@RestController
@RequestMapping("/api/loan-applications")
public class LoanApplicationWorkflowController {

    private static final int LOAN_CLASS = 12, NEW = 1, VERIFIED = 2, APPROVED = 3, MODULE = 2, VOUCHER = 21, PENDING = 2;

    /**
     * An application as the lists show it. For an approved one, approvedOn is the day it was approved and releaseStatus where its release voucher
     * stands (WAITING FOR APPROVAL, APPROVED, POSTED; null when the voucher is not found), with releasedOn the day it was posted. All three are null
     * before approval.
     */
    public record Item(long id, String documentNo, int clientId, String clientName, int typeCode, String typeName, int refNo, BigDecimal amount, int terms, int status, String statusName,
                String encodedByName, String date, Integer voucherYear, Long voucherControlNo, int branchCode, boolean waitingForDeductions,
                String approvedOn, String releaseStatus, String releasedOn) {

        Item withRelease(String release, String on) {
            return new Item(id, documentNo, clientId, clientName, typeCode, typeName, refNo, amount, terms, status, statusName, encodedByName, date, voucherYear,
                voucherControlNo, branchCode, waitingForDeductions, approvedOn, release, on);
        }
    }

    /** Approved applications stay on the waiting list this many days after approval while their release is not posted. */
    static final int RELEASE_WAIT_DAYS = 60;
    /** Released applications stay on the list this many days after the release was posted. */
    static final int RELEASED_SHOWN_DAYS = 30;

    record Row(String account, String description, BigDecimal amount) {}

    record Detail(Item item, List<Row> lines, BigDecimal totalDeductions, BigDecimal netProceeds, List<String> problems, List<CoMakerHolds.Saved> coMakerHolds) {}

    public record Approved(long applicationId, int year, long voucherControlNo, String voucherDocumentNo) {}

    /** canAdd is "1 · Profiling", canDeduct "2 · Deduction". */
    private record Staff(int id, int branch, boolean canAdd, boolean canVerify, boolean canApprove, boolean canDeduct) {
        boolean anyStep() { return canAdd || canDeduct || canVerify || canApprove; }
    }

    public record Reason(String reason) {}

    private record VLine(String account, Integer slc, Integer slt, Integer ref, Integer event, Integer cts, BigDecimal amount, String description) {}

    private record Prepared(Item item, int encoder, int dept, int loanStatus, int netRef, List<VLine> lines, BigDecimal deductions, BigDecimal net, List<String> problems) {}

    private final JdbcTemplate jdbc;

    private final ph.coop.core.businessday.BusinessDay businessDay;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Db db;
    private final SingleHandGrants single;
    private final LoanBook book;
    private final CoMakerHolds holds;
    private final LoanSteps steps;
    private final FrontOfficeAccess frontOffice;

    private final ph.coop.core.accounting.ProductRoles roles;

    LoanApplicationWorkflowController(JdbcTemplate jdbc, PlatformTransactionManager txm, Clock clock, Db db, SingleHandGrants single, LoanBook book, CoMakerHolds holds, LoanSteps steps,
                                      FrontOfficeAccess frontOffice, ph.coop.core.businessday.BusinessDay businessDay, ph.coop.core.accounting.ProductRoles roles) {
        this.roles = roles;

        this.businessDay = businessDay;
        this.steps = steps;
        this.frontOffice = frontOffice;
        this.book = book;
        this.holds = holds;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.db = db;
        this.single = single;
    }

    private static final String ITEM_SQL = """
            SELECT a.ID, a.DOC_NO, a.CLIENTID, CONCAT_WS(' ', k.LName, k.FName), a.SLT_CODE, t.SLTypeM_DESC, a.REF_NO, l.LoanPAMT, l.LoanTERMS, a.STATUSID, a.TR_DATE,
                   (SELECT CONCAT_WS(' ', e.AccessFName, e.AccessLName) FROM access e WHERE e.AccessBR_CODE = a.BR_CODE AND e.AccessID = a.ENCDBY),
                   a.TR_CTLNO, a.APPRV_DATE, a.BR_CODE, %s
            FROM loanapp a
            JOIN loan l ON l.LoanBR_CODE = a.BR_CODE AND l.LoanSLC_CODE = a.SLC_CODE AND l.LoanSLT_CODE = a.SLT_CODE AND l.LoanREF_NO = a.REF_NO
            LEFT JOIN client k ON k.ClientIDBrCode = a.BR_CODE AND k.ClientID = a.CLIENTID
            LEFT JOIN sltype t ON t.SLTypeBR_CODE = a.BR_CODE AND t.SLTypeSLC_CODE = a.SLC_CODE AND t.SLTypeSLT_CODE = a.SLT_CODE
            """;

    /** The item query, with "waiting for deductions" from the step table where this database has it. */
    private String itemSql() {
        return ITEM_SQL.formatted(steps.waitingSql("a"));
    }

    private static Item item(java.sql.ResultSet rs) throws java.sql.SQLException {
        int st = rs.getInt(10);
        // The release voucher's number is set when the application is approved; it was dated that day, which gives its year.
        Number ctl = st == APPROVED ? (Number) rs.getObject(13) : null;
        return new Item(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getInt(7), rs.getBigDecimal(8), rs.getInt(9), st,
            st == NEW ? "New" : st == VERIFIED ? "Verified" : st == APPROVED ? "Approved" : "Status " + st, rs.getString(12), String.valueOf(rs.getDate(11)),
            ctl == null ? null : rs.getDate(14).toLocalDate().getYear(), ctl == null ? null : ctl.longValue(), rs.getInt(15), rs.getInt(16) > 0,
            st == APPROVED && rs.getDate(14) != null ? rs.getDate(14).toLocalDate().toString() : null, null, null);
    }

    /**
     * Adds where the release stands to an approved application: read from its release voucher (the lines' update tag and approver, as the Disburse
     * screen reads them) and, once posted, the day from the loan's own release row in the member ledger (class 12, event 11, written by that voucher).
     * Read only; both reads use the tables' primary keys.
     */
    private Item release(Item it) {
        if (it.status() != APPROVED || it.voucherYear() == null || it.voucherControlNo() == null) return it;
        List<Object[]> v = jdbc.query("""
                SELECT COUNT(*), MIN(DTUPD_TAG), MAX(DTAPPRVBY) FROM transdt
                WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ?
                """, (rs, i) -> new Object[] {rs.getInt(1), rs.getInt(2), rs.getObject(3)}, it.branchCode(), it.voucherYear(), MODULE, VOUCHER, it.voucherControlNo());
        if (v.isEmpty() || (int) v.get(0)[0] == 0) return it;
        if ((int) v.get(0)[1] == PENDING) return it.withRelease(v.get(0)[2] == null ? "WAITING FOR APPROVAL" : "APPROVED", null);
        List<java.sql.Date> on = jdbc.queryForList("""
                SELECT MIN(TR_DATE) FROM sldtl
                WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = 11 AND TR_CODE = ? AND TR_CTLNO = ?
                """, java.sql.Date.class, it.branchCode(), it.clientId(), LOAN_CLASS, it.typeCode(), it.refNo(), VOUCHER, it.voucherControlNo());
        return it.withRelease("POSTED", on.isEmpty() || on.get(0) == null ? null : on.get(0).toLocalDate().toString());
    }

    /** Applications waiting to be verified or approved. */
    /**
     * What the signed-in person may do with loan applications (the old system's flags in access), so the form can say so before anything is typed.
     * canRelease is 5 · Loan Release: New on the Cash Voucher (type 21), which approving and posting the release voucher checks.
     */
    public record Permissions(boolean canAdd, boolean canVerify, boolean canApprove, boolean canDeduct, boolean canRelease) {}

    @GetMapping("/permissions")
    public Permissions permissions(Authentication auth) {
        Staff s = staff(auth.getName());
        return new Permissions(s.canAdd(), s.canVerify(), s.canApprove(), s.canDeduct(), frontOffice.mayCreate(auth, VOUCHER));
    }

    @GetMapping("/pending")
    List<Item> pending(Authentication auth) {
        Staff s = staff(auth.getName());
        if (!s.anyStep()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not work on loan applications.");
        List<Item> out = new ArrayList<>(jdbc.query(itemSql() + " WHERE a.BR_CODE = ? AND a.STATUSID IN (?, ?) ORDER BY a.ID DESC LIMIT 200", (rs, i) -> item(rs), s.branch(), NEW, VERIFIED));
        // then the approved ones on their way to release: waiting in Front Office, or released in the last RELEASED_SHOWN_DAYS days
        LocalDate today = LocalDate.now(clock);
        LocalDate releasedSince = today.minusDays(RELEASED_SHOWN_DAYS);
        for (Item it : jdbc.query(itemSql() + " WHERE a.BR_CODE = ? AND a.STATUSID = ? AND a.APPRV_DATE >= ? ORDER BY a.ID DESC LIMIT 200",
                (rs, i) -> item(rs), s.branch(), APPROVED, java.sql.Date.valueOf(today.minusDays(RELEASE_WAIT_DAYS)))) {
            Item r = release(it);
            boolean released = "POSTED".equals(r.releaseStatus());
            if (!released || (r.releasedOn() != null && !LocalDate.parse(r.releasedOn()).isBefore(releasedSince))) out.add(r);
        }
        return out;
    }

    @GetMapping("/{id}")
    Detail detail(@PathVariable long id, Authentication auth) {
        Staff s = staff(auth.getName());
        if (!s.anyStep()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not work on loan applications.");
        Prepared p = prepare(s.branch(), id, false);
        return new Detail(release(p.item()), rows(p), p.deductions(), p.net(), p.problems(), holds.forApplication(s.branch(), id));
    }

    @PostMapping("/{id}/verify")
    public Item verify(@PathVariable long id, Authentication auth) {
        Staff s = staff(auth.getName());
        if (!s.canVerify()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not verify loan applications.");
        return tx.execute((st) -> {
            Prepared p = prepare(s.branch(), id, true);
            if (p.item().status() != NEW) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a new application can be verified.");
            if (p.item().waitingForDeductions()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This application is waiting for its deductions. Someone who may do the Deduction step fills them in first.");
            if (p.encoder() == s.id() && !single.has(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You encoded this application, so someone else must verify it.");
            jdbc.update("UPDATE loanapp SET STATUSID = ? WHERE BR_CODE = ? AND ID = ? AND STATUSID = ?", VERIFIED, s.branch(), id, NEW);
            audit(auth, "loan.application.verify", s.branch(), id, "verified");
            return jdbc.query(itemSql() + " WHERE a.BR_CODE = ? AND a.ID = ?", (rs, i) -> item(rs), s.branch(), id).get(0);
        });
    }

    @PostMapping("/{id}/approve")
    public Approved approve(@PathVariable long id, Authentication auth) {
        Staff s = staff(auth.getName());
        if (!s.canApprove()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not approve loan applications.");
        return tx.execute((st) -> {
            Prepared p = prepare(s.branch(), id, true);
            if (p.item().status() != NEW && p.item().status() != VERIFIED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a new or verified application can be approved.");
            if (p.item().waitingForDeductions()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This application is waiting for its deductions. Someone who may do the Deduction step fills them in first.");
            if (!p.problems().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", p.problems()));
            if (!single.has(auth.getName())) {
                if (p.encoder() == s.id()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You encoded this application, so someone else must approve it.");
                List<String> verifiers = jdbc.queryForList("SELECT username FROM ecis_audit_log WHERE action = 'loan.application.verify' AND entity_id = ? ORDER BY at DESC LIMIT 1", String.class, s.branch() + "/" + id);
                if (!verifiers.isEmpty() && verifiers.get(0).equalsIgnoreCase(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You verified this application, so someone else must approve it.");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate day = businessDay.postingDate(s.branch());
            int year = day.getYear();
            PostingPeriods.requireOpen(jdbc, s.branch(), day);
            long ctl = db.nextControlNumber(s.branch(), MODULE, VOUCHER, year);
            String docNo = String.format("%08d", ctl);
            String explanation = "per Approved " + p.item().typeName() + " of " + upperName(s.branch(), p.item().clientId());
            jdbc.update("""
                    INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, TR_CLIENTID, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """, s.branch(), year, MODULE, VOUCHER, ctl, docNo, day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), p.item().clientId(),
                day.format(DateTimeFormatter.BASIC_ISO_DATE), explanation, Timestamp.valueOf(now.withNano(0)));
            int seq = 1;
            for (VLine l : p.lines()) {
                boolean member = l.slc() != null;
                jdbc.update("""
                        INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                          DTSLE_CODE, DTCTS_NUM, DTSL_DATE, DTADJ_FLAG, DTCOCI_TYPE, DTAMT, DTENCDBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0, 0, 0)
                        """, s.branch(), year, MODULE, VOUCHER, ctl, seq++, p.dept(), l.account(), member ? p.item().clientId() : null, l.slc(), l.slt(), l.ref(), l.event(), l.cts(),
                    member ? day.toString() : null, member ? 1 : null, l.amount(), p.encoder(), PENDING);
            }
            jdbc.update("UPDATE loanapp SET STATUSID = ?, APPRV_DATE = ?, APPRVBY = ?, TR_CTLNO = ? WHERE BR_CODE = ? AND ID = ? AND STATUSID IN (?, ?)",
                APPROVED, day.toString(), s.id(), ctl, s.branch(), id, NEW, VERIFIED);
            audit(auth, "loan.application.approve", s.branch(), id, "voucher " + year + "/" + ctl + " amount " + p.item().amount().toPlainString());
            return new Approved(id, year, ctl, docNo);
        });
    }

    /**
     * Cancels an application that is not released (status 5, with the date and the person in APPRV_DATE / APPRVBY, as the old system does; the loan
     * row, schedule and any voucher number stay). A voucher that was already posted blocks it. Anyone who may add, verify or approve applications may cancel;
     * the reason goes to the audit log. An unposted voucher is left in place but can no longer be posted (LoanReleaseController).
     */
    @PostMapping("/{id}/cancel")
    public Item cancel(@PathVariable long id, @org.springframework.web.bind.annotation.RequestBody Reason in, Authentication auth) {
        Staff s = staff(auth.getName());
        if (!s.canAdd() && !s.canVerify() && !s.canApprove()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not cancel loan applications.");
        if (in == null || in.reason() == null || in.reason().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason for cancelling.");
        return tx.execute((st) -> {
            Prepared p = prepare(s.branch(), id, true);
            int status = p.item().status();
            if (status != NEW && status != VERIFIED && status != APPROVED) throw new ResponseStatusException(HttpStatus.CONFLICT, "This application can no longer be cancelled.");
            if (status == APPROVED) {
                List<Map<String, Object>> v = jdbc.queryForList("SELECT TR_CTLNO, APPRV_DATE FROM loanapp WHERE BR_CODE = ? AND ID = ?", s.branch(), id);
                Object ctl = v.get(0).get("TR_CTLNO");
                if (ctl != null) {
                    int year = ((java.sql.Date) v.get(0).get("APPRV_DATE")).toLocalDate().getYear();
                    Integer posted = jdbc.queryForObject("SELECT COUNT(*) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = ? AND DTTR_CODE = ? AND DTTR_CTLNO = ? AND DTUPD_TAG <> ?",
                        Integer.class, s.branch(), year, MODULE, VOUCHER, ((Number) ctl).longValue(), PENDING);
                    if (posted != null && posted > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "The release voucher is already posted, so the application cannot be cancelled.");
                }
            }
            jdbc.update("UPDATE loanapp SET STATUSID = 5, APPRV_DATE = ?, APPRVBY = ? WHERE BR_CODE = ? AND ID = ? AND STATUSID = ?", businessDay.today(s.branch()).toString(), s.id(), s.branch(), id, status);
            audit(auth, "loan.application.cancel", s.branch(), id, in.reason().trim());
            return jdbc.query(itemSql() + " WHERE a.BR_CODE = ? AND a.ID = ?", (rs, i) -> item(rs), s.branch(), id).get(0);
        });
    }

    /**
     * Disapproves an application that is new or verified (status 4, with the date and the person in APPRV_DATE / APPRVBY, as the two real ones from 2023 and 2024
     * show; no voucher is made). Needs the right to approve; the person who encoded it cannot disapprove it (unless allowed to do every step alone). The reason
     * goes to the audit log. An approved application is cancelled, not disapproved.
     */
    @PostMapping("/{id}/disapprove")
    public Item disapprove(@PathVariable long id, @org.springframework.web.bind.annotation.RequestBody Reason in, Authentication auth) {
        Staff s = staff(auth.getName());
        if (!s.canApprove()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not disapprove loan applications.");
        if (in == null || in.reason() == null || in.reason().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason for disapproving.");
        return tx.execute((st) -> {
            Prepared p = prepare(s.branch(), id, true);
            int status = p.item().status();
            if (status != NEW && status != VERIFIED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a new or verified application can be disapproved.");
            if (p.encoder() == s.id() && !single.has(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You encoded this application, so someone else must disapprove it.");
            jdbc.update("UPDATE loanapp SET STATUSID = 4, APPRV_DATE = ?, APPRVBY = ? WHERE BR_CODE = ? AND ID = ? AND STATUSID = ?", businessDay.today(s.branch()).toString(), s.id(), s.branch(), id, status);
            audit(auth, "loan.application.disapprove", s.branch(), id, in.reason().trim());
            return jdbc.query(itemSql() + " WHERE a.BR_CODE = ? AND a.ID = ?", (rs, i) -> item(rs), s.branch(), id).get(0);
        });
    }

    // ---- building the voucher -----------------------------------------------------------------------------------

    private List<Row> rows(Prepared p) {
        List<Row> out = new ArrayList<>();
        for (VLine l : p.lines()) out.add(new Row(l.account(), l.description(), l.amount()));
        return out;
    }

    private Prepared prepare(int branch, long id, boolean lock) {
        // locked on its own: PostgreSQL does not lock through the outer joins of ITEM_SQL
        if (lock) jdbc.queryForList("SELECT ID FROM loanapp WHERE BR_CODE = ? AND ID = ? FOR UPDATE", Long.class, branch, id);
        List<Item> found = jdbc.query(itemSql() + " WHERE a.BR_CODE = ? AND a.ID = ?", (rs, i) -> item(rs), branch, id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such loan application.");
        Item it = found.get(0);
        Map<String, Object> a = jdbc.queryForMap("SELECT ENCDBY, COALESCE(DEPT, 1) DEPT, NPREF_NO, NPSLT_CODE FROM loanapp WHERE BR_CODE = ? AND ID = ?", branch, id);
        int status = jdbc.queryForObject("SELECT COALESCE(LoanSTATUS, 11) FROM loan WHERE LoanBR_CODE = ? AND LoanSLC_CODE = ? AND LoanSLT_CODE = ? AND LoanREF_NO = ?", Integer.class, branch, LOAN_CLASS, it.typeCode(), it.refNo());
        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> ded = jdbc.queryForList("SELECT * FROM loandeduc WHERE BR_CODE = ? AND LOANAPPID = ? ORDER BY SEQNO", branch, id);
        BigDecimal fee = BigDecimal.ZERO;
        List<ReleasePlan.Deduction> others = new ArrayList<>();
        for (Map<String, Object> d : ded) {
            BigDecimal amt = (BigDecimal) d.get("AMT");
            Integer slc = d.get("SLC_CODE") == null ? null : ((Number) d.get("SLC_CODE")).intValue();
            Integer sle = d.get("SLE_CODE") == null ? null : ((Number) d.get("SLE_CODE")).intValue();
            if (slc != null && slc == LOAN_CLASS && sle != null && sle == 31) { fee = fee.add(amt); continue; }
            String gl = (String) d.get("ACCT_CODE");
            if (slc == null) others.add(new ReleasePlan.Deduction((String) d.get("DEDUCDESC"), 0, 0, 0, 0, gl, amt));
            else others.add(new ReleasePlan.Deduction((String) d.get("DEDUCDESC"), slc, num(d.get("SLT_CODE")), num(d.get("REF_NO")), sle == null ? 0 : sle, null, amt));
        }
        ReleasePlan.Plan plan = ReleasePlan.build(it.amount(), fee, others);
        problems.addAll(plan.problems());
        List<VLine> lines = new ArrayList<>();
        int netRef = ((Number) a.get("NPREF_NO")).intValue();
        if (plan.ok()) {
            int k = 0;
            lines.add(vline(branch, problems, LOAN_CLASS, it.typeCode(), it.refNo(), 11, status, plan.lines().get(k++).amount(), "Loan released"));
            if (fee.signum() > 0) lines.add(vline(branch, problems, LOAN_CLASS, it.typeCode(), it.refNo(), 31, 99, plan.lines().get(k++).amount(), "Filing fee/Service fee"));
            for (ReleasePlan.Deduction d : others) {
                ReleasePlan.Line pl = plan.lines().get(k++);
                if (d.glAccount() != null) lines.add(new VLine(d.glAccount(), null, null, null, null, null, pl.amount(), d.description()));
                else if (d.slc() == LOAN_CLASS && d.event() == 11) lines.add(payoffLine(branch, problems, it, d, pl.amount()));
                else lines.add(vline(branch, problems, d.slc(), d.slt(), d.refNo(), d.event(), d.slc() == LOAN_CLASS ? 99 : 15, pl.amount(), d.description()));
            }
            // the net proceeds go to the vouchers-payable type the application recorded when it was encoded
            int netType = a.get("NPSLT_CODE") != null ? ((Number) a.get("NPSLT_CODE")).intValue() : roles.vouchersPayable(branch, "the release (the loan's net proceeds)");
            lines.add(vline(branch, problems, 29, netType, netRef, 11, 15, plan.lines().get(k).amount(), "Net proceeds"));
        }
        return new Prepared(it, ((Number) a.get("ENCDBY")).intValue(), ((Number) a.get("DEPT")).intValue(), status, netRef, lines, plan.totalDeductions(), plan.netProceeds(), problems);
    }

    /** Pays principal of an earlier loan of the member: the line carries that loan's own status as its number, and it must still owe at least that much. */
    private VLine payoffLine(int branch, List<String> problems, Item it, ReleasePlan.Deduction d, BigDecimal amount) {
        var terms = book.terms(branch, it.clientId(), d.slt(), d.refNo());
        if (terms.isEmpty()) {
            problems.add(d.description() + ": loan " + d.slt() + "/" + d.refNo() + " was not found.");
            return new VLine(null, d.slc(), d.slt(), d.refNo(), d.event(), 11, amount, d.description());
        }
        if (terms.get().outstanding().compareTo(d.amount()) < 0) problems.add(d.description() + ": the loan now owes only " + terms.get().outstanding().toPlainString() + " principal.");
        return vline(branch, problems, d.slc(), d.slt(), d.refNo(), d.event(), terms.get().status(), amount, d.description());
    }

    /** One member-ledger voucher line; its general ledger account comes from glcontrol (class, type, event, number). */
    private VLine vline(int branch, List<String> problems, int slc, int slt, int ref, int event, int cts, BigDecimal amount, String description) {
        List<String> gl = jdbc.queryForList("SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = ? AND GLControlCTS_NUM = ? LIMIT 1",
            String.class, branch, slc, slt, event, cts);
        if (gl.isEmpty()) {
            problems.add(description + ": no general ledger account is set for " + slc + "/" + slt + " event " + event + " number " + cts + ".");
            return new VLine(null, slc, slt, ref, event, cts, amount, description);
        }
        return new VLine(gl.get(0), slc, slt, ref, event, cts, amount, description);
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private String upperName(int branch, int clientId) {
        List<String> n = jdbc.queryForList("SELECT CONCAT_WS(' ', CONCAT(LName, ','), FName, SName, MName) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", String.class, branch, clientId);
        return n.isEmpty() || n.get(0) == null ? "" : n.get(0).toUpperCase();
    }

    private Staff staff(String username) {
        List<Staff> r = jdbc.query("SELECT AccessID, AccessBR_CODE, COALESCE(AccessCanAddLoanApplication, 0), COALESCE(AccessCanVerifyLoanApplication, 0), COALESCE(AccessCanApproveLoanApplication, 0), "
                + steps.deductionFlagSql("") + " FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
            (rs, i) -> new Staff(rs.getInt(1), rs.getInt(2), rs.getInt(3) == 1, rs.getInt(4) == 1, rs.getInt(5) == 1, rs.getInt(6) == 1), username);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are signed in with an account that is not in the cooperative's staff list, so this cannot be done with it. Sign in with your own staff account, or ask an administrator to add you in Settings > Users.");
        return r.get(0);
    }

    private void audit(Authentication auth, String action, int branch, long id, String detail) {
        AuditLog.write(jdbc, auth.getName(), action, "loanapp", branch + "/" + id, null, detail, null);
    }
}
