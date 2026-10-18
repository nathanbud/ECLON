package ph.coop.core.loan;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates a loan application at status New, the way the old system writes it (documentation/loan-posting-map.md, "The loan application"):
 * one row for the loan's own account in slmastersum (loanapp, loan and amortsched all point at it), one row each in loanapp and loan, the schedule in amortsched, the deductions the clerk accepted in loandeduc, and the co-makers in comaker.
 * Insert only. The setup comes from the loan type (sltype) unless the clerk gives the amount, term, purpose, class or collateral. The deduction
 * amounts are typed by the clerk (the old formulas do not reproduce real amounts). Needs the legacy flag AccessCanAddLoanApplication.
 * Verify, approve and the release voucher are later steps.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/loan-applications")
public class LoanApplicationController {

    private static final int LOAN_CLASS = 12, MONTHS = 4, STATUS_NEW = 1, LOAN_CURRENT = 11, VOUCHER_CODE = 21;
    private static final int BEHAVIOR_FEE = 1, BEHAVIOR_MEMBER = 3, BEHAVIOR_PAYOFF = 4, BEHAVIOR_NET = 6;

    record DeductionRow(long id, String description, int behavior, String formula, boolean needsMemberAccount, boolean hasMemberAccount) {}

    public record Template(int typeCode, String typeName, int terms, int termUnit, int paymentMode, BigDecimal interestRate, boolean hasVouchersPayable, int nextRefNo, List<DeductionRow> deductions, List<Option> purposes, List<Option> collateralTypes, List<PayoffOption> payoffs) {}

    /** An open loan of the member that the new loan could pay off, with today's suggested interest. */
    public record PayoffOption(int typeCode, String typeName, int refNo, int status, BigDecimal outstanding, BigDecimal interestDue, long daysPastDue) {}

    /** What is taken from the new loan to pay off an earlier loan of the member: its principal, interest and penalty (the penalty is typed; the formula is not known). */
    public record Payoff(int typeCode, int refNo, BigDecimal principal, BigDecimal interest, BigDecimal penalty) {}

    record Option(int id, String name) {}

    public record DeductionAmount(long id, BigDecimal amount) {}

    public record Create(int typeCode, BigDecimal amount, Integer terms, Integer purpose, Integer loanClass, Integer borrowerClass, Integer collateralType, BigDecimal collateralAmount,
                  String collateralDescription, String remarks, List<Integer> coMakers, List<DeductionAmount> deductions, List<Payoff> payoffs) {
        public Create(int typeCode, BigDecimal amount, Integer terms, Integer purpose, Integer loanClass, Integer borrowerClass, Integer collateralType, BigDecimal collateralAmount,
                      String collateralDescription, String remarks, List<Integer> coMakers, List<DeductionAmount> deductions) {
            this(typeCode, amount, terms, purpose, loanClass, borrowerClass, collateralType, collateralAmount, collateralDescription, remarks, coMakers, deductions, List.of());
        }
    }

    public record Created(long applicationId, String documentNo, int refNo, LocalDate firstDue, LocalDate maturity, BigDecimal totalInterest, BigDecimal totalDeductions, BigDecimal netProceeds, int scheduleRows) {}

    private record Type(String name, Map<String, Object> d) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final LoanBook book;

    LoanApplicationController(JdbcTemplate jdbc, PlatformTransactionManager txm, Clock clock, LoanBook book) {
        this.book = book;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    /** What the clerk starts from: the type's defaults and the deductions list, with what the member lacks. */
    @GetMapping("/template")
    public Template template(@PathVariable int branchCode, @PathVariable int clientId, @RequestParam int type) {
        Type t = type(branchCode, type);
        Integer vp = memberRef(branchCode, clientId, 29, 1);
        List<DeductionRow> rows = new ArrayList<>();
        for (Map<String, Object> r : deductionTemplate(branchCode, type)) {
            int behavior = num(r, "SLBEHAVIOR");
            if (behavior == BEHAVIOR_NET) continue;
            boolean needs = r.get("DSLC_CODE") != null;
            rows.add(new DeductionRow(((Number) r.get("ID")).longValue(), (String) r.get("DESCR"), behavior, (String) r.get("FORMULA"), needs,
                needs && memberRef(branchCode, clientId, num(r, "DSLC_CODE"), num(r, "DSLT_CODE")) != null));
        }
        return new Template(type, t.name(), num(t.d(), "SLTypeTERMS"), num(t.d(), "SLTypeTERM_PERD"), num(t.d(), "SLTypePPMT_MODE"), (BigDecimal) t.d().get("SLTypeINT_RATE"), vp != null,
            nextRef(branchCode, type), rows,
            jdbc.query("SELECT LoanPurposeID, LoanPurposeDesc FROM loanpurpose ORDER BY LoanPurposeDesc", (rs, i) -> new Option(rs.getInt(1), rs.getString(2))),
            jdbc.query("SELECT CollateralTypeID, CollateralTypeDesc FROM collateraltype ORDER BY CollateralTypeID", (rs, i) -> new Option(rs.getInt(1), rs.getString(2))),
            payoffOptions(branchCode, clientId));
    }

    @PostMapping
    public Created create(@PathVariable int branchCode, @PathVariable int clientId, @RequestBody Create in, Authentication auth) {
        Encoder enc = encoder(auth.getName());
        if (!enc.mayAdd()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not add loan applications.");
        if (enc.branch() != branchCode) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This member belongs to another branch.");
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().scale() > 2) throw bad("The loan amount must be more than zero, with at most 2 decimals.");
        Type t = type(branchCode, in.typeCode());
        List<Integer> clients = jdbc.queryForList("SELECT ClientID FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, branchCode, clientId);
        if (clients.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client.");
        Integer existingVp = memberRef(branchCode, clientId, 29, 1);

        int terms = in.terms() == null ? num(t.d(), "SLTypeTERMS") : in.terms();
        int unit = num(t.d(), "SLTypeTERM_PERD");
        if (unit != MONTHS) throw bad("Only loan types counted in months can be applied for here so far.");
        if (terms <= 0) throw bad("The term must be more than zero.");
        BigDecimal rate = (BigDecimal) t.d().get("SLTypeINT_RATE");
        int pmode = num(t.d(), "SLTypePPMT_MODE");
        LocalDate today = LocalDate.now(clock);
        List<LoanSchedule.Row> schedule;
        try {
            if (num(t.d(), "SLTypeAMORTYPE") != 1) throw new IllegalArgumentException("Only diminishing-balance loan types can be applied for here so far.");
            schedule = LoanSchedule.diminishing(in.amount(), terms, pmode, rate, today);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }

        return tx.execute((st) -> {
            // the old system opens the member's vouchers-payable account (osl, status 15, amount 0.00) with their first application
            int vp = existingVp != null ? existingVp : openVouchersPayable(branchCode, clientId, today);
            int ref = lastRef(branchCode, in.typeCode(), true) + 1;
            long appId = nextAppId(branchCode);
            String docNo = String.format("%08d", appId);

            List<Map<String, Object>> template = deductionTemplate(branchCode, in.typeCode());
            Map<Long, BigDecimal> typed = new HashMap<>();
            if (in.deductions() != null) for (DeductionAmount a : in.deductions()) typed.put(a.id(), a.amount());
            List<Object[]> deducRows = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            int seq = 0;
            for (Map<String, Object> r : template) {
                BigDecimal amt = typed.remove(((Number) r.get("ID")).longValue());
                int behavior = num(r, "SLBEHAVIOR");
                if (behavior == BEHAVIOR_NET || amt == null || amt.signum() == 0) continue;
                String desc = (String) r.get("DESCR");
                if (amt.signum() < 0 || amt.scale() > 2) throw bad(desc + ": the amount must not be negative and has at most 2 decimals.");
                if (behavior == BEHAVIOR_PAYOFF) throw bad(desc + ": paying off an earlier loan is not supported yet.");
                Integer slc = objInt(r, "DSLC_CODE"), slt = objInt(r, "DSLT_CODE"), sle = objInt(r, "DSLE_CODE"), memberRef = null;
                String gl = (String) r.get("DACCT_CODE");
                if (slc != null) {
                    memberRef = memberRef(branchCode, clientId, slc, slt);
                    if (memberRef == null) throw bad(desc + ": the member has no account " + slc + "/" + slt + " to credit. Open it first.");
                } else if (gl == null) {
                    slc = LOAN_CLASS; slt = in.typeCode(); memberRef = ref;
                }
                seq++;
                total = total.add(amt);
                deducRows.add(new Object[] {branchCode, appId, seq, desc, slc, slt, sle, memberRef, clientId, gl, amt, behavior, objInt(r, "ISTRANSITORYDEDUCITEM"), objInt(r, "IsHidePrinting"),
                    objInt(r, "DeductClass"), objInt(r, "IsIncludeEffectiveIntRate")});
            }
            if (!typed.isEmpty()) throw bad("Some deductions do not belong to this loan type.");
            Payoff firstPaid = null;
            java.util.Set<String> seen = new java.util.HashSet<>();
            if (in.payoffs() != null) {
                for (Payoff po : in.payoffs()) {
                    if (!seen.add(po.typeCode() + "/" + po.refNo())) throw bad("Loan " + po.typeCode() + "/" + po.refNo() + " is listed twice.");
                    LoanBook.Terms old = book.terms(branchCode, clientId, po.typeCode(), po.refNo()).orElseThrow(() -> bad("The member has no loan " + po.typeCode() + "/" + po.refNo() + "."));
                    if (old.status() == 13) throw bad("Loan " + po.typeCode() + "/" + po.refNo() + " is in litigation and cannot be paid off here.");
                    BigDecimal pr = money(po.principal()), ir = money(po.interest()), pe = money(po.penalty());
                    if (pr.signum() <= 0) throw bad("The principal to pay off on loan " + po.typeCode() + "/" + po.refNo() + " must be more than zero.");
                    if (pr.compareTo(old.outstanding()) > 0) throw bad("Loan " + po.typeCode() + "/" + po.refNo() + " owes only " + old.outstanding().toPlainString() + " principal.");
                    String label = (old.type() == null ? "Loan " + po.typeCode() : old.type()) + " : ";
                    Object[][] parts = {{label + "Principal", 11, pr}, {label + "Interest Paid", 23, ir}, {label + "Penalty/Fines", 41, pe}};
                    for (Object[] part : parts) {
                        BigDecimal amt = (BigDecimal) part[2];
                        if (amt.signum() == 0) continue;
                        seq++;
                        total = total.add(amt);
                        deducRows.add(new Object[] {branchCode, appId, seq, part[0], LOAN_CLASS, po.typeCode(), part[1], po.refNo(), clientId, null, amt, BEHAVIOR_PAYOFF, 0, 0, null, 1});
                    }
                    if (firstPaid == null) firstPaid = po;
                }
            }
            int borrowerClass = in.borrowerClass() == null ? 1 : in.borrowerClass();
            if (borrowerClass == 3 && firstPaid == null) throw bad("A restructured loan must pay off the loan it restructures.");
            final Payoff replaced = borrowerClass == 3 ? firstPaid : null;
            if (total.compareTo(in.amount()) >= 0) throw bad("The deductions (" + total.toPlainString() + ") must be less than the loan amount.");

            LocalDate firstDue = schedule.get(1).dueDate(), maturity = schedule.get(schedule.size() - 1).dueDate();
            jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (?, ?, ?, ?, ?)", branchCode, LOAN_CLASS, in.typeCode(), ref, clientId);
            jdbc.update("""
                    INSERT INTO loanapp (BR_CODE, ID, SLC_CODE, SLT_CODE, REF_NO, CLIENTID, STATUSID, TR_DATE, RTR_DATE, ENCDBY, TR_CODE, NPSLC_CODE, NPSLT_CODE, NPREF_NO, DEDUCGROUP, DOC_NO, DEPT)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 29, 1, ?, ?, ?, ?)
                    """, branchCode, appId, LOAN_CLASS, in.typeCode(), ref, clientId, STATUS_NEW, today, today, enc.id(), VOUCHER_CODE, vp, deductionGroup(template), docNo, enc.dept());

            Map<String, Object> d = t.d();
            jdbc.update("""
                    INSERT INTO loan (LoanBR_CODE, LoanSLC_CODE, LoanSLT_CODE, LoanREF_NO, ClientIDLoan, LoanLOANCLASS, LoanPURPOSE, LoanPAMT, LoanSTATUS, LoanTR_DATE, LoanTERMS, LoanTERM_PERD,
                      LoanPPMT_MODE, LoanPPMT_MFAC, LoanPPMT_MOD2, LoanINT_RATE, LoanAMORTYPE, LoanDIMB_FREQ, LoanIPMT_MODE, LoanIPMT_MFAC, LoanIPMT_MOD2, LoanPEN_RATE, LoanPEN_RATE_AM,
                      LoanPEN_MODE, LoanPEN_MFAC, LoanPEN_MOD2, LoanDD_FLAG, LoanINT_GP, LoanPRIN_GP, LoanFID_DATE, LoanFPD_DATE, LoanMAT_DATE, LoanND_FLAG, LoanMCLASS, LoanRESTRUCT,
                      LoanCOLLTYPE, LoanCOLLAMT, LoanCOLLDESC, LoanRemarks, LoanCI, LoanXXREF_NO, LoanPENGP, LoanPREPAIDPYMTS, LoanISDISTRIBUTEUNPAIDINT, LoanIsExcludeSundays,
                      LoanIsExcludeHolidays, LoanCOLLECTTYPE, LoanFunder, LoanForceGenAmort, LoanIsFringeBenefit, LoanINSAMT, LoanAmortLock, LoanForceBillingAmort, LoanXSLT_CODE, LoanXREF_NO)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NA', '0000', ?, ?, 0, 0, 0, ?, 1, 0, 0, 0, 0, 0, ?, ?)
                    """, branchCode, LOAN_CLASS, in.typeCode(), ref, clientId, in.loanClass() == null ? 1 : in.loanClass(), in.purpose(), in.amount(), LOAN_CURRENT, today, terms, unit,
                    pmode, d.get("SLTypePPMT_MFAC"), d.get("SLTypePPMT_MOD2"), rate, d.get("SLTypeAMORTYPE"), d.get("SLTypeDIMB_FREQ"), d.get("SLTypeIPMT_MODE"), d.get("SLTypeIPMT_MFAC"),
                    d.get("SLTypeIPMT_MOD2"), d.get("SLTypePEN_RATE"), d.get("SLTypePEN_RATE_AM"), d.get("SLTypePEN_MODE"), d.get("SLTypePEN_MFAC"), d.get("SLTypePEN_MOD2"), d.get("SLTypeDD_FLAG"),
                    d.get("SLTypeINT_GP"), d.get("SLTypePRIN_GP"), firstDue, firstDue, maturity, d.get("SLTypeND_FLAG"), borrowerClass,
                    replaced != null ? 1 : 0, in.collateralType(), in.collateralAmount(), in.collateralDescription(), in.remarks(), d.get("SLTypePENGP"), d.get("SLTypePREPAIDPYMTS"), d.get("SLTypeCOLLECTTYPE"),
                    replaced == null ? null : replaced.typeCode(), replaced == null ? null : replaced.refNo());

            boolean first = true;
            for (LoanSchedule.Row r : schedule) {
                jdbc.update("""
                        INSERT INTO amortsched (BR_CODE, CLIENTID, SLC_CODE, SLT_CODE, REF_NO, DUEDATE, PRINDUE, INTDUE, PRINBAL, PENFLAG, DBFLAG, CBUAMT, SAVAMT, COLLFEE, PRINBAL2, SRVFEE,
                          PRINDUE_PP, INTDUE_PP, PRINBAL_PP, INSDUE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, 0, 0, ?, 0, ?, ?, ?, 0)
                        """, branchCode, clientId, LOAN_CLASS, in.typeCode(), ref, r.dueDate(), r.principalDue(), r.interestDue(), r.principalBalance(), first ? null : "Y", r.principalBalance(),
                        r.principalDue(), r.interestDue(), r.principalBalance());
                first = false;
            }
            for (Object[] row : deducRows) {
                jdbc.update("""
                        INSERT INTO loandeduc (BR_CODE, LOANAPPID, SEQNO, DEDUCDESC, SLC_CODE, SLT_CODE, SLE_CODE, REF_NO, CLIENTID, ACCT_CODE, AMT, SLBEHAVIOR, ISTRANSITORYDEDUCITEM, IsHidePrinting,
                          DeductClass, IsIncludeEffectiveIntRate) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, row);
            }
            if (in.coMakers() != null) {
                for (Integer cm : in.coMakers()) {
                    List<String> names = jdbc.query("SELECT CONCAT_WS(' ', CONCAT(LName, ','), FName, MName) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", (rs, i) -> rs.getString(1), branchCode, cm);
                    if (names.isEmpty()) throw bad("Co-maker " + cm + " is not a member.");
                    if (cm == clientId) throw bad("A borrower cannot be their own co-maker.");
                    jdbc.update("INSERT INTO comaker (CoMakerBR_CODE, CoMakerClientID, CoMakerSLC_CODE, CoMakerSLT_CODE, CoMakerREF_NO, ClientIDCoMaker, CoMakerClientName) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        branchCode, clientId, LOAN_CLASS, in.typeCode(), ref, cm, names.get(0));
                }
            }
            jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, 'loan.application.create', 'loanapp', ?, ?)",
                Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), auth.getName(), branchCode + "/" + appId,
                "client " + clientId + " type " + in.typeCode() + " ref " + ref + " amount " + in.amount().toPlainString() + " terms " + terms + " deductions " + total.toPlainString());
            return new Created(appId, docNo, ref, firstDue, maturity, LoanSchedule.totalInterest(schedule), total, in.amount().subtract(total), schedule.size());
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private record Encoder(int id, int branch, int dept, boolean mayAdd) {}

    private Encoder encoder(String username) {
        List<Encoder> rows = jdbc.query("SELECT AccessID, AccessBR_CODE, COALESCE(AccessDepartment, 1), COALESCE(AccessCanAddLoanApplication, 0) FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
            (rs, i) -> new Encoder(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4) == 1), username);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not a staff account.");
        return rows.get(0);
    }

    private Type type(int branch, int code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ? AND COALESCE(SLTypeIsDisabled, 0) = 0", branch, LOAN_CLASS, code);
        if (rows.isEmpty()) throw bad("Loan type " + code + " does not exist or is disabled.");
        return new Type((String) rows.get(0).get("SLTypeM_DESC"), rows.get(0));
    }

    private List<Map<String, Object>> deductionTemplate(int branch, int type) {
        return jdbc.queryForList("SELECT * FROM loansldeduc WHERE BR_CODE = ? AND SLC_CODE = ? AND SLT_CODE = ? ORDER BY DEDUCORDER, ID", branch, LOAN_CLASS, type);
    }

    private static Integer deductionGroup(List<Map<String, Object>> template) {
        return template.isEmpty() ? null : objInt(template.get(0), "DEDUCGROUP");
    }

    private Integer memberRef(int branch, int clientId, int slc, int slt) {
        List<Integer> r = jdbc.queryForList("SELECT MREF_NO FROM slmastersum WHERE MBR_CODE = ? AND MCLIENTID = ? AND MSLC_CODE = ? AND MSLT_CODE = ? ORDER BY MREF_NO LIMIT 1", Integer.class, branch, clientId, slc, slt);
        return r.isEmpty() ? null : r.get(0);
    }

    /** The highest reference used by this loan type (0 when none), locking the row so two clerks do not take the same number. */
    private int lastRef(int branch, int type, boolean lock) {
        List<Integer> r = jdbc.queryForList("SELECT LoanREF_NO FROM loan WHERE LoanBR_CODE = ? AND LoanSLC_CODE = ? AND LoanSLT_CODE = ? ORDER BY LoanREF_NO DESC LIMIT 1" + (lock ? " FOR UPDATE" : ""),
            Integer.class, branch, LOAN_CLASS, type);
        return r.isEmpty() ? 0 : r.get(0);
    }

    /** Open loans of the member (not in litigation) that still have principal to pay. */
    private List<PayoffOption> payoffOptions(int branch, int clientId) {
        List<PayoffOption> out = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        for (Map<String, Object> l : jdbc.queryForList("SELECT LoanSLT_CODE t, LoanREF_NO r FROM loan WHERE LoanBR_CODE = ? AND ClientIDLoan = ? AND LoanSLC_CODE = ? AND LoanSTATUS IN (11, 12, 14) ORDER BY LoanTR_DATE DESC",
            branch, clientId, LOAN_CLASS)) {
            int t = num(l, "t"), r = num(l, "r");
            book.terms(branch, clientId, t, r).filter((x) -> x.outstanding().signum() > 0).ifPresent((x) -> {
                LoanBook.Quote q = book.quote(branch, clientId, t, r, x, today);
                out.add(new PayoffOption(t, x.type(), r, x.status(), x.outstanding(), q.interestDue(), q.daysPastDue()));
            });
        }
        return out;
    }

    private int openVouchersPayable(int branch, int clientId, LocalDate today) {
        List<Integer> last = jdbc.queryForList("SELECT MREF_NO FROM slmastersum WHERE MBR_CODE = ? AND MSLC_CODE = 29 AND MSLT_CODE = 1 ORDER BY MREF_NO DESC LIMIT 1 FOR UPDATE", Integer.class, branch);
        int ref = last.isEmpty() ? 1 : last.get(0) + 1;
        jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (?, 29, 1, ?, ?)", branch, ref, clientId);
        jdbc.update("INSERT INTO osl (OSLBR_CODE, OSLSLC_CODE, OSLSLT_CODE, OSLREF_NO, ClientIDOSL, OSLTR_DATE, OSLSTATUS, OSLPAMT) VALUES (?, 29, 1, ?, ?, ?, 15, 0.00)", branch, ref, clientId, today.toString());
        return ref;
    }

    private int nextRef(int branch, int type) {
        return lastRef(branch, type, false) + 1;
    }

    private long nextAppId(int branch) {
        List<Long> r = jdbc.queryForList("SELECT ID FROM loanapp WHERE BR_CODE = ? ORDER BY ID DESC LIMIT 1 FOR UPDATE", Long.class, branch);
        return r.isEmpty() ? 1 : r.get(0) + 1;
    }

    private static BigDecimal money(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO.setScale(2);
        if (v.signum() < 0 || v.stripTrailingZeros().scale() > 2) throw bad("Pay-off amounts must not be negative and have at most 2 decimals.");
        return v.setScale(2);
    }

    private static int num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static Integer objInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : ((Number) v).intValue();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
