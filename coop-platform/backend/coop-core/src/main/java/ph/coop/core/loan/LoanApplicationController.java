package ph.coop.core.loan;



import static ph.coop.core.web.ApiErrors.bad;import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
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

    private static final int LOAN_CLASS = 12, MONTHS = 4, YEARS = 7, STATUS_NEW = 1, LOAN_CURRENT = 11, VOUCHER_CODE = 21;
    private static final int BEHAVIOR_FEE = 1, BEHAVIOR_MEMBER = 3, BEHAVIOR_PAYOFF = 4, BEHAVIOR_NET = 6;

    record DeductionRow(long id, String description, int behavior, String formula, boolean needsMemberAccount, boolean hasMemberAccount) {}

    public record Template(int typeCode, String typeName, int terms, int termUnit, int paymentMode, BigDecimal interestRate, boolean hasVouchersPayable, int nextRefNo, List<DeductionRow> deductions, List<Option> purposes, List<Option> collateralTypes, List<PayoffOption> payoffs, Setup setup) {}

    /** The loan type's setup in words, as the old Loans Profile shows it, and whether this screen can build its schedule yet. */
    public record Setup(String termUnit, String principalPayment, String interestPayment, String basedOn, String diminish, BigDecimal penaltyRate, Integer yearDays, boolean collateralRequired, String unsupported) {}

    /** An open loan of the member that the new loan could pay off, with today's suggested interest. */
    /** nextDueDate: the next instalment not yet paid; pastDueSince: the oldest unpaid instalment when the loan is late (else null). */
    public record PayoffOption(int typeCode, String typeName, int refNo, int status, BigDecimal outstanding, BigDecimal interestDue, long daysPastDue,
                               LocalDate nextDueDate, LocalDate pastDueSince) {}

    /** What is taken from the new loan to pay off an earlier loan of the member: its principal, interest and penalty (the penalty is typed; the formula is not known). */
    public record Payoff(int typeCode, int refNo, BigDecimal principal, BigDecimal interest, BigDecimal penalty) {}

    record Option(int id, String name) {}

    public record DeductionAmount(long id, BigDecimal amount) {}

    public record Create(int typeCode, BigDecimal amount, Integer terms, Integer purpose, Integer loanClass, Integer borrowerClass, Integer collateralType, BigDecimal collateralAmount,
                  String collateralDescription, String remarks, List<Integer> coMakers, List<DeductionAmount> deductions, List<Payoff> payoffs, Integer termUnit,
                  List<CoMakerHolds.Request> coMakerHolds) {
        /** With the amounts each co-maker agreed to cover. */
        public Create(int typeCode, BigDecimal amount, Integer terms, Integer purpose, Integer loanClass, Integer borrowerClass, Integer collateralType, BigDecimal collateralAmount,
                      String collateralDescription, String remarks, List<Integer> coMakers, List<DeductionAmount> deductions, List<Payoff> payoffs, Integer termUnit) {
            this(typeCode, amount, terms, purpose, loanClass, borrowerClass, collateralType, collateralAmount, collateralDescription, remarks, coMakers, deductions, payoffs, termUnit, null);
        }
        /** Without a term unit, the loan type's own unit is used. */
        public Create(int typeCode, BigDecimal amount, Integer terms, Integer purpose, Integer loanClass, Integer borrowerClass, Integer collateralType, BigDecimal collateralAmount,
                      String collateralDescription, String remarks, List<Integer> coMakers, List<DeductionAmount> deductions, List<Payoff> payoffs) {
            this(typeCode, amount, terms, purpose, loanClass, borrowerClass, collateralType, collateralAmount, collateralDescription, remarks, coMakers, deductions, payoffs, null, null);
        }
        public Create(int typeCode, BigDecimal amount, Integer terms, Integer purpose, Integer loanClass, Integer borrowerClass, Integer collateralType, BigDecimal collateralAmount,
                      String collateralDescription, String remarks, List<Integer> coMakers, List<DeductionAmount> deductions) {
            this(typeCode, amount, terms, purpose, loanClass, borrowerClass, collateralType, collateralAmount, collateralDescription, remarks, coMakers, deductions, List.of(), null, null);
        }
    }

    /** waitingForDeductions: saved by someone who may do Profiling but not Deduction; someone else fills in the deductions before it can be verified. */
    public record Created(long applicationId, String documentNo, int refNo, LocalDate firstDue, LocalDate maturity, BigDecimal totalInterest, BigDecimal totalDeductions, BigDecimal netProceeds, int scheduleRows,
                          boolean waitingForDeductions) {}

    private record Type(String name, Map<String, Object> d) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final LoanBook book;
    private final LoanSteps steps;
    private final CoMakerHolds holds;
    private final AmortizationSchedulePrint amortizationPrint;

    LoanApplicationController(JdbcTemplate jdbc, PlatformTransactionManager txm, Clock clock, LoanBook book, CoMakerHolds holds, LoanSteps steps, AmortizationSchedulePrint amortizationPrint) {
        this.amortizationPrint = amortizationPrint;
        this.steps = steps;
        this.book = book;
        this.holds = holds;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    /** A loan type the member can apply for. A type with no interest rate or term in the setup cannot be scheduled, so it is listed but not available. */
    public record TypeOption(int code, String name, Integer terms, BigDecimal rate, long loans, boolean available) {}

    /** The enabled loan types from the setup (sltype, class 12), the most used first. */
    @GetMapping("/types")
    public List<TypeOption> types(@PathVariable int branchCode) {
        return jdbc.query("""
            SELECT s.SLTypeSLT_CODE, s.SLTypeM_DESC, s.SLTypeTERMS, s.SLTypeINT_RATE, s.SLTypeTERM_PERD, s.SLTypePPMT_MODE, s.SLTypeAMORTYPE,
                   (SELECT COUNT(*) FROM loan l WHERE l.LoanBR_CODE = s.SLTypeBR_CODE AND l.LoanSLC_CODE = s.SLTypeSLC_CODE AND l.LoanSLT_CODE = s.SLTypeSLT_CODE) n
            FROM sltype s WHERE s.SLTypeBR_CODE = ? AND s.SLTypeSLC_CODE = ? AND COALESCE(s.SLTypeIsDisabled, 0) = 0
            ORDER BY n DESC, s.SLTypeM_DESC
            """, (rs, i) -> {
                Integer terms = (Integer) rs.getObject(3);
                BigDecimal rate = rs.getBigDecimal(4);
                Map<String, Object> d = new java.util.HashMap<>();
                d.put("SLTypeTERMS", terms); d.put("SLTypeINT_RATE", rate); d.put("SLTypeTERM_PERD", rs.getObject(5)); d.put("SLTypePPMT_MODE", rs.getObject(6)); d.put("SLTypeAMORTYPE", rs.getObject(7));
                return new TypeOption(rs.getInt(1), rs.getString(2), terms, rate, rs.getLong(8), unsupported(d) == null);
            }, branchCode, LOAN_CLASS);
    }

    /** The accounts of a would-be co-maker that an amount can be held on, with what is free on each. */
    @GetMapping("/comaker-accounts")
    public List<CoMakerHolds.HoldAccount> comakerAccounts(@PathVariable int branchCode, @RequestParam int comaker) {
        return holds.accounts(branchCode, comaker);
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
            payoffOptions(branchCode, clientId), setup(t.d()));
    }

    private static final java.util.Set<Integer> SCHEDULED_MODES = java.util.Set.of(LoanSchedule.MONTHLY, LoanSchedule.QUARTERLY, LoanSchedule.LUMP_SUM);
    /** An annuity can also be paid half-yearly (seen on real loans). */
    private static final java.util.Set<Integer> ANNUITY_MODES = java.util.Set.of(LoanSchedule.MONTHLY, LoanSchedule.QUARTERLY, LoanSchedule.SEMIANNUAL, LoanSchedule.LUMP_SUM);

    /** Why a schedule cannot be built for this setup yet, or null. Mirrors what {@link #create} refuses. */
    static String unsupported(Map<String, Object> d) {
        Object terms = d.get("SLTypeTERMS"), rate = d.get("SLTypeINT_RATE"), unit = d.get("SLTypeTERM_PERD"), pm = d.get("SLTypePPMT_MODE"), am = d.get("SLTypeAMORTYPE");
        if (terms == null || ((Number) terms).intValue() <= 0) return "The setup has no term.";
        if (rate == null) return "The setup has no interest rate.";
        if (unit == null || (((Number) unit).intValue() != MONTHS && ((Number) unit).intValue() != YEARS)) return "Only loan types counted in months or years can be applied for here so far.";
        int kind = am == null ? 0 : ((Number) am).intValue();
        if (kind != LoanSchedule.DIMINISHING && kind != LoanSchedule.ANNUITY) return "Only diminishing-balance and annuity loan types can be applied for here so far.";
        boolean annuity = kind == LoanSchedule.ANNUITY;
        if (pm == null || !(annuity ? ANNUITY_MODES : SCHEDULED_MODES).contains(((Number) pm).intValue())) {
            return annuity ? "Only monthly, quarterly, half-yearly and lump-sum payment modes can be scheduled here so far." : "Only monthly, quarterly and lump-sum payment modes can be scheduled here so far.";
        }
        return null;
    }

    private String lookup(String sql, Object id) {
        if (id == null) return null;
        List<String> r = jdbc.query(sql, (rs, i) -> rs.getString(1), id);
        return r.isEmpty() ? "Code " + id : r.get(0);
    }

    private Setup setup(Map<String, Object> d) {
        Object diminish = d.get("SLTypeDIMB_FREQ");
        Object penalty = d.get("SLTypePEN_RATE"), days = d.get("SLTypeNDAYS_1YEAR_INTLOAN"), coll = d.get("SLTypeRequireCollateral");
        return new Setup(
            lookup("SELECT TermDesc FROM term WHERE TermID = ?", d.get("SLTypeTERM_PERD")),
            lookup("SELECT PaymentModeDesc FROM paymentmode WHERE PaymentModeID = ?", d.get("SLTypePPMT_MODE")),
            lookup("SELECT PaymentModeDesc FROM paymentmode WHERE PaymentModeID = ?", d.get("SLTypeIPMT_MODE")),
            lookup("SELECT AmorTypeDesc FROM amortype WHERE AmorTypeID = ?", d.get("SLTypeAMORTYPE")),
            diminish == null ? null : LoanProfileController.diminish(((Number) diminish).intValue()),
            penalty == null ? null : (BigDecimal) penalty, days == null ? null : ((Number) days).intValue(), coll != null && ((Number) coll).intValue() != 0, unsupported(d));
    }

    public record DeductionsIn(List<DeductionAmount> deductions, List<Payoff> payoffs) {}

    public record Deducted(long applicationId, BigDecimal totalDeductions, BigDecimal netProceeds) {}

    /**
     * The Deduction step of an application that was saved by someone who may do Profiling only: the deduction lines, by the same rules as when both are
     * done in one save. The person who did the Profiling may do this too when they hold both permissions. Audited as loan.application.deduction.
     */
    @PostMapping("/{appId}/deductions")
    public Deducted deductions(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable long appId, @RequestBody DeductionsIn in, Authentication auth) {
        Encoder enc = encoder(auth.getName());
        if (!enc.mayDeduct()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not do the Deduction step of loan applications.");
        if (enc.branch() != branchCode) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This member belongs to another branch.");
        return tx.execute((st) -> {
            jdbc.queryForList("SELECT ID FROM loanapp WHERE BR_CODE = ? AND ID = ? FOR UPDATE", Long.class, branchCode, appId);
            List<Map<String, Object>> app = jdbc.queryForList("""
                    SELECT a.SLT_CODE, a.REF_NO, a.STATUSID, l.LoanPAMT FROM loanapp a
                    JOIN loan l ON l.LoanBR_CODE = a.BR_CODE AND l.LoanSLC_CODE = a.SLC_CODE AND l.LoanSLT_CODE = a.SLT_CODE AND l.LoanREF_NO = a.REF_NO
                    WHERE a.BR_CODE = ? AND a.ID = ? AND a.CLIENTID = ?
                    """, branchCode, appId, clientId);
            if (app.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such loan application for this member.");
            Map<String, Object> a = app.get(0);
            if (num(a, "STATUSID") != STATUS_NEW) throw new ResponseStatusException(HttpStatus.CONFLICT, "This application is no longer new, so its deductions cannot be filled in.");
            if (!steps.waitingForDeductions(branchCode, appId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "The deductions of this application were already filled in.");
            int typeCode = num(a, "SLT_CODE"), ref = num(a, "REF_NO");
            BigDecimal amount = (BigDecimal) a.get("LoanPAMT");
            DeductionPlan plan = plan(branchCode, clientId, typeCode, ref, appId, deductionTemplate(branchCode, typeCode), in == null ? null : in.deductions(), in == null ? null : in.payoffs());
            if (plan.total().compareTo(amount) >= 0) throw bad("The deductions (" + plan.total().toPlainString() + ") must be less than the loan amount.");
            for (Object[] row : plan.rows()) {
                jdbc.update("""
                        INSERT INTO loandeduc (BR_CODE, LOANAPPID, SEQNO, DEDUCDESC, SLC_CODE, SLT_CODE, SLE_CODE, REF_NO, CLIENTID, ACCT_CODE, AMT, SLBEHAVIOR, ISTRANSITORYDEDUCITEM, IsHidePrinting,
                          DeductClass, IsIncludeEffectiveIntRate) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, row);
            }
            if (!steps.deducted(branchCode, appId, auth.getName())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Someone filled in the deductions in the meantime. Reload the application.");
            AuditLog.write(jdbc, auth.getName(), "loan.application.deduction", "loanapp", branchCode + "/" + appId, "waiting for deductions",
                plan.rows().size() + " lines, total " + plan.total().toPlainString() + ", net proceeds " + amount.subtract(plan.total()).toPlainString(), null);
            return new Deducted(appId, plan.total(), amount.subtract(plan.total()));
        });
    }

    @PostMapping
    public Created create(@PathVariable int branchCode, @PathVariable int clientId, @RequestBody Create in, Authentication auth) {
        Encoder enc = encoder(auth.getName());
        if (!enc.mayAdd()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not add loan applications.");
        if (enc.branch() != branchCode) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This member belongs to another branch.");
        // Profiling and Deduction are separate permissions; a person with both does both in this one save, as before.
        boolean deducts = enc.mayDeduct();
        boolean typedDeductions = (in.deductions() != null && in.deductions().stream().anyMatch((a) -> a.amount() != null && a.amount().signum() != 0))
            || (in.payoffs() != null && !in.payoffs().isEmpty());
        if (!deducts && typedDeductions) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may do Profiling but not Deduction. Save the application without deductions; someone who may do Deduction fills them in.");
        }
        if (!deducts && in.borrowerClass() != null && in.borrowerClass() == 3) {
            throw bad("A restructured loan pays off the loan it replaces as part of its deductions, so it must be encoded by someone who may do both Profiling and Deduction.");
        }
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().scale() > 2) throw bad("The loan amount must be more than zero, with at most 2 decimals.");
        Type t = type(branchCode, in.typeCode());
        // A loan type whose setup requires collateral needs its type, value and description (the form says so too, before the save).
        Object needsCollateral = t.d().get("SLTypeRequireCollateral");
        if (needsCollateral != null && ((Number) needsCollateral).intValue() != 0
            && (in.collateralType() == null || in.collateralAmount() == null || in.collateralAmount().signum() <= 0 || in.collateralDescription() == null || in.collateralDescription().isBlank())) {
            throw bad("This loan type requires collateral: give its type, its value (more than zero) and a description.");
        }
        List<Integer> clients = jdbc.queryForList("SELECT ClientID FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, branchCode, clientId);
        if (clients.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client.");
        Integer existingVp = memberRef(branchCode, clientId, 29, 1);

        Planned planned = plan(t, in, LocalDate.now(clock));
        int terms = planned.terms(), unit = planned.unit(), pmode = planned.paymentMode();
        BigDecimal rate = planned.rate();
        LocalDate today = planned.release();
        List<LoanSchedule.Row> schedule = planned.rows();

        return tx.execute((st) -> {
            // the old system opens the member's vouchers-payable account (osl, status 15, amount 0.00) with their first application
            int vp = existingVp != null ? existingVp : openVouchersPayable(branchCode, clientId, today);
            int ref = lastRef(branchCode, in.typeCode(), true) + 1;
            long appId = nextAppId(branchCode);
            String docNo = String.format("%08d", appId);

            List<Map<String, Object>> template = deductionTemplate(branchCode, in.typeCode());
            DeductionPlan plan = plan(branchCode, clientId, in.typeCode(), ref, appId, template, in.deductions(), in.payoffs());
            List<Object[]> deducRows = plan.rows();
            BigDecimal total = plan.total();
            Payoff firstPaid = plan.firstPaid();
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
            holds.validate(branchCode, clientId, in.coMakers(), in.coMakerHolds());
            if (in.coMakers() != null) {
                for (Integer cm : in.coMakers()) {
                    List<String> names = jdbc.query("SELECT CONCAT_WS(' ', CONCAT(LName, ','), FName, MName) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", (rs, i) -> rs.getString(1), branchCode, cm);
                    if (names.isEmpty()) throw bad("Co-maker " + cm + " is not a member.");
                    if (cm == clientId) throw bad("A borrower cannot be their own co-maker.");
                    jdbc.update("INSERT INTO comaker (CoMakerBR_CODE, CoMakerClientID, CoMakerSLC_CODE, CoMakerSLT_CODE, CoMakerREF_NO, ClientIDCoMaker, CoMakerClientName) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        // In the old data CoMakerClientID is the co-maker and ClientIDCoMaker is the borrower (the loan's own client): 16,471 of 16,594 rows
                        branchCode, cm, LOAN_CLASS, in.typeCode(), ref, clientId, names.get(0));
                }
            }
            holds.save(branchCode, appId, in.coMakerHolds());
            steps.profiled(branchCode, appId, auth.getName(), deducts);
            if (deducts) {
                AuditLog.write(jdbc, auth.getName(), "loan.application.deduction", "loanapp", branchCode + "/" + appId, null,
                    deducRows.size() + " lines, total " + total.toPlainString() + ", net proceeds " + in.amount().subtract(total).toPlainString() + " (with the profiling)", null);
            }
            AuditLog.write(jdbc, auth.getName(), "loan.application.create", "loanapp", branchCode + "/" + appId, null, "client " + clientId + " type " + in.typeCode() + " ref " + ref + " amount " + in.amount().toPlainString() + " terms " + terms + " deductions " + total.toPlainString(), null);
            return new Created(appId, docNo, ref, firstDue, maturity, LoanSchedule.totalInterest(schedule), total, in.amount().subtract(total), schedule.size(), steps.available() && !deducts);
        });
    }

    /** The deduction lines of an application, as the old system stores them: the loan type's lines with an amount typed, then the pay-offs of earlier loans. */
    /** The term as typed (with its unit), the rate, payment mode and the schedule an application builds; the save and the printed schedule use the same one. */
    record Planned(int terms, int unit, int termMonths, BigDecimal rate, int paymentMode, LocalDate release, List<LoanSchedule.Row> rows) {}

    private Planned plan(Type t, Create in, LocalDate today) {
        // The clerk may count the term in months or in years, whatever the type's default; the old system keeps the number as typed with its unit.
        int typeUnit = num(t.d(), "SLTypeTERM_PERD");
        int unit = in.termUnit() == null ? typeUnit : in.termUnit();
        if (unit != MONTHS && unit != YEARS) throw bad("The term can be counted in months or years.");
        if (in.terms() == null && unit != typeUnit) throw bad("Give the term when it is counted in a different unit from the loan type's.");
        int terms = in.terms() == null ? num(t.d(), "SLTypeTERMS") : in.terms();
        if (terms <= 0) throw bad("The term must be more than zero.");
        int termMonths = unit == YEARS ? terms * 12 : terms;
        if (termMonths > 600) throw bad("The term is too long.");
        // the loan type's term is the longest it allows (the form fills it in and stops there too)
        int typeTerms = num(t.d(), "SLTypeTERMS");
        int mostMonths = typeUnit == YEARS ? typeTerms * 12 : typeTerms;
        if (typeTerms > 0 && termMonths > mostMonths) {
            throw bad("This loan type allows a term of at most " + typeTerms + (typeUnit == YEARS ? " years" : " months")
                + (unit != typeUnit ? " (" + (unit == YEARS ? mostMonths / 12 + " years" : mostMonths + " months") + ")" : "") + ".");
        }
        BigDecimal rate = (BigDecimal) t.d().get("SLTypeINT_RATE");
        int pmode = num(t.d(), "SLTypePPMT_MODE");
        List<LoanSchedule.Row> schedule;
        try {
            int kind = num(t.d(), "SLTypeAMORTYPE");
            if (kind == LoanSchedule.ANNUITY) {
                Object nd = t.d().get("SLTypeND_FLAG");
                schedule = LoanSchedule.annuity(in.amount(), termMonths, pmode, rate, today, nd != null && ((Number) nd).intValue() == 1);
            } else if (kind == LoanSchedule.DIMINISHING) {
                schedule = LoanSchedule.diminishing(in.amount(), termMonths, pmode, rate, today);
            } else {
                throw new IllegalArgumentException("Only diminishing-balance and annuity loan types can be applied for here so far.");
            }
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        return new Planned(terms, unit, termMonths, rate, pmode, today, schedule);
    }

    /**
     * The amortization schedule of the application on the form, as a PDF laid out like the old system's printed Amortization Schedule (for the member to sign,
     * CONFORME), before or after it is saved. Nothing is saved; the print is written to the audit log and the export log, since it holds a member's loan.
     * {@code refNo}: the loan number once saved, printed only when that loan is this member's, of this type and amount; otherwise "To be assigned".
     */
    @PostMapping("/amortization/pdf")
    public org.springframework.http.ResponseEntity<byte[]> amortizationPdf(@PathVariable int branchCode, @PathVariable int clientId, @RequestBody Create in,
                                                                          @RequestParam(required = false) Integer refNo, Authentication auth) {
        Encoder enc = encoder(auth.getName());
        if (!enc.mayAdd()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not add loan applications, so you cannot print their schedules.");
        if (enc.branch() != branchCode) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This member belongs to another branch.");
        if (in.amount() == null || in.amount().signum() <= 0 || in.amount().scale() > 2) throw bad("The loan amount must be more than zero, with at most 2 decimals.");
        Type t = type(branchCode, in.typeCode());
        Planned planned = plan(t, in, LocalDate.now(clock));
        Integer saved = null;
        if (refNo != null) {
            Integer same = jdbc.queryForObject("SELECT COUNT(*) FROM loan WHERE LoanBR_CODE = ? AND ClientIDLoan = ? AND LoanSLC_CODE = ? AND LoanSLT_CODE = ? AND LoanREF_NO = ? AND LoanPAMT = ?",
                Integer.class, branchCode, clientId, LOAN_CLASS, in.typeCode(), refNo, in.amount());
            if (same != null && same > 0) saved = refNo;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
        AmortizationSchedulePrint.Sheet sheet = amortizationPrint.sheet(branchCode, clientId, in.typeCode(), t.d(), in.purpose(), in.amount(), planned, saved, enc.id());
        byte[] pdf = amortizationPrint.pdf(sheet, auth.getName(), now);
        String subject = "loan application " + sheet.refNo() + " of member " + branchCode + "-" + clientId;
        jdbc.update("INSERT INTO ecis_data_export (at, username, export_type, subject, row_count, purpose) VALUES (?, ?, 'REPORT', ?, ?, ?)",
            java.sql.Timestamp.valueOf(now), auth.getName(), subject, planned.rows().size(), "Amortization schedule (pdf)");
        AuditLog.write(jdbc, auth.getName(), "loan.application.amortization.print", "loan", branchCode + "-" + clientId + "-" + sheet.refNo(), null,
            "type " + in.typeCode() + " amount " + in.amount().toPlainString() + " term " + planned.terms() + (planned.unit() == YEARS ? " years" : " months"), "Amortization schedule printed");
        String name = "amortization-schedule-" + branchCode + "-" + clientId + "-" + planned.release() + ".pdf";
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "inline; filename=\"" + name + "\"").body(pdf);
    }

    private record DeductionPlan(List<Object[]> rows, BigDecimal total, Payoff firstPaid) {}

    private DeductionPlan plan(int branchCode, int clientId, int typeCode, int ref, long appId, List<Map<String, Object>> template, List<DeductionAmount> deductions, List<Payoff> payoffs) {
        Map<Long, BigDecimal> typed = new HashMap<>();
        if (deductions != null) for (DeductionAmount a : deductions) typed.put(a.id(), a.amount());
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
                slc = LOAN_CLASS; slt = typeCode; memberRef = ref;
            }
            seq++;
            total = total.add(amt);
            deducRows.add(new Object[] {branchCode, appId, seq, desc, slc, slt, sle, memberRef, clientId, gl, amt, behavior, objInt(r, "ISTRANSITORYDEDUCITEM"), objInt(r, "IsHidePrinting"),
                objInt(r, "DeductClass"), objInt(r, "IsIncludeEffectiveIntRate")});
        }
        if (!typed.isEmpty()) throw bad("Some deductions do not belong to this loan type.");
        Payoff firstPaid = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (payoffs != null) {
            for (Payoff po : payoffs) {
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
        return new DeductionPlan(deducRows, total, firstPaid);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** mayAdd is "1 · Profiling" (Add loan application), mayDeduct "2 · Deduction". */
    private record Encoder(int id, int branch, int dept, boolean mayAdd, boolean mayDeduct) {}

    private Encoder encoder(String username) {
        List<Encoder> rows = jdbc.query("SELECT AccessID, AccessBR_CODE, COALESCE(AccessDepartment, 1), COALESCE(AccessCanAddLoanApplication, 0), " + steps.deductionFlagSql("")
                + " FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
            (rs, i) -> new Encoder(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4) == 1, rs.getInt(5) == 1), username);
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
                out.add(new PayoffOption(t, x.type(), r, x.status(), x.outstanding(), q.interestDue(), q.daysPastDue(), q.nextDueDate(), q.pastDueSince()));
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

}
