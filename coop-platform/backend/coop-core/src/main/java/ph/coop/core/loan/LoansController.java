package ph.coop.core.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.loan.LoanRules.Arrears;
import ph.coop.core.loan.LoanRules.Due;

/**
 * Read-only loans of a member (guide phase 10), over the legacy tables. A loan is a row of {@code loan}; its subsidiary ledger is
 * class 12 of {@code sldtl}, where event 11 is principal (the release is a debit, payments are credits, so the balance is the plain sum),
 * 23 interest paid, 31 service fee and so on; the repayment schedule is {@code amortsched}. Nothing is written: releasing a loan and
 * posting payments, with their interest, fee and penalty rules, are not built yet.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/loans")
class LoansController {

    private static final int CLASS_LOANS = 12;
    private static final int PRINCIPAL = 11;
    private static final int INTEREST_PAID = 23;
    private static final int MAX_ENTRIES = 1000;

    record Loan(int typeCode, String type, int refNo, int status, String statusName, BigDecimal amount, LocalDate date, int terms, String termUnit, String paymentMode, BigDecimal interestRate,
                String amortization, LocalDate firstDue, LocalDate maturity, String purpose, String collateral, BigDecimal collateralAmount, boolean restructured, int renewals,
                BigDecimal outstanding, BigDecimal principalPaid, BigDecimal interestPaid, BigDecimal pastDuePrincipal, long daysPastDue, LocalDate nextDueDate,
                BigDecimal nextDuePrincipal, BigDecimal nextDueInterest, LocalDate lastPayment) {}

    record Summary(List<Loan> loans, BigDecimal totalOutstanding, BigDecimal totalPastDue, LocalDate asOf) {}

    record CoMaker(Integer clientId, String name, String address) {}

    record ScheduleRow(LocalDate dueDate, BigDecimal principalDue, BigDecimal interestDue, BigDecimal principalBalance, String state, BigDecimal principalPaid) {}

    record Entry(LocalDate date, int event, String eventName, int transactionCode, String transaction, String documentNo, BigDecimal debit, BigDecimal credit, BigDecimal principalBalance) {}

    record Detail(Loan loan, String remarks, List<CoMaker> coMakers, List<ScheduleRow> schedule, List<Entry> entries, boolean entriesTruncated) {}

    private final JdbcTemplate jdbc;

    LoansController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String LOANS = """
            SELECT l.LoanSLT_CODE, t.SLTypeM_DESC, l.LoanREF_NO, l.LoanSTATUS, l.LoanPAMT, l.LoanTR_DATE, l.LoanTERMS, tm.TermDesc, pm.PaymentModeDesc, l.LoanINT_RATE,
                   am.AmorTypeDesc, l.LoanFPD_DATE, l.LoanMAT_DATE, lp.LoanPurposeDesc, ct.CollateralTypeDesc, l.LoanCOLLAMT, l.LoanRESTRUCT, l.LoanRENEWCOUNT,
                   (SELECT COALESCE(SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = l.LoanBR_CODE AND d.SL_CLIENTID = l.ClientIDLoan AND d.SLC_CODE = l.LoanSLC_CODE
                      AND d.SLT_CODE = l.LoanSLT_CODE AND d.REF_NO = l.LoanREF_NO AND d.SLE_CODE = 11),
                   (SELECT COALESCE(-SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = l.LoanBR_CODE AND d.SL_CLIENTID = l.ClientIDLoan AND d.SLC_CODE = l.LoanSLC_CODE
                      AND d.SLT_CODE = l.LoanSLT_CODE AND d.REF_NO = l.LoanREF_NO AND d.SLE_CODE = 23),
                   (SELECT MAX(d.TR_DATE) FROM sldtl d WHERE d.SL_BRCODE = l.LoanBR_CODE AND d.SL_CLIENTID = l.ClientIDLoan AND d.SLC_CODE = l.LoanSLC_CODE
                      AND d.SLT_CODE = l.LoanSLT_CODE AND d.REF_NO = l.LoanREF_NO AND d.SLE_CODE = 11 AND d.AMT < 0)
            FROM loan l
            LEFT JOIN sltype t ON t.SLTypeBR_CODE = l.LoanBR_CODE AND t.SLTypeSLC_CODE = l.LoanSLC_CODE AND t.SLTypeSLT_CODE = l.LoanSLT_CODE
            LEFT JOIN term tm ON tm.TermID = l.LoanTERM_PERD
            LEFT JOIN paymentmode pm ON pm.PaymentModeID = l.LoanPPMT_MODE
            LEFT JOIN amortype am ON am.AmorTypeID = l.LoanAMORTYPE
            LEFT JOIN loanpurpose lp ON lp.LoanPurposeID = l.LoanPURPOSE
            LEFT JOIN collateraltype ct ON ct.CollateralTypeID = l.LoanCOLLTYPE
            WHERE l.LoanBR_CODE = ? AND l.ClientIDLoan = ? AND l.LoanSLC_CODE = ?
            """;

    /** LoanSTATUS is the number the general ledger control table (glcontrol, class 12, event 11) keys its accounts by: 11 current, 12 past due, 13 in litigation, 14 restructured. */
    static String statusName(int code) {
        return switch (code) {
            case 11 -> "Current";
            case 12 -> "Past due";
            case 13 -> "In litigation";
            case 14 -> "Restructured";
            default -> "Status " + code;
        };
    }

    private List<Due> dues(int branch, int clientId, int typeCode, int refNo, List<BigDecimal> balances) {
        List<Due> dues = new ArrayList<>();
        jdbc.query("""
                SELECT DUEDATE, COALESCE(PRINDUE, 0), COALESCE(INTDUE, 0), COALESCE(PRINBAL, 0) FROM amortsched
                WHERE BR_CODE = ? AND CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? ORDER BY DUEDATE
                """, (rs) -> { dues.add(new Due(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getBigDecimal(3))); balances.add(rs.getBigDecimal(4)); },
            branch, clientId, CLASS_LOANS, typeCode, refNo);
        return dues;
    }

    private Loan loan(java.sql.ResultSet rs, int branch, int clientId, LocalDate asOf) throws java.sql.SQLException {
        int typeCode = rs.getInt(1), refNo = rs.getInt(3);
        BigDecimal amount = rs.getBigDecimal(5);
        BigDecimal outstanding = rs.getBigDecimal(19);
        BigDecimal paid = amount.subtract(outstanding).max(BigDecimal.ZERO);
        List<Due> dues = dues(branch, clientId, typeCode, refNo, new ArrayList<>());
        boolean open = outstanding.signum() > 0;
        Arrears a = open ? LoanRules.arrears(dues, paid, asOf) : new Arrears(BigDecimal.ZERO, 0, null);
        Due next = open ? LoanRules.nextDue(dues, paid, asOf) : null;
        java.sql.Date last = rs.getDate(21);
        java.sql.Date fpd = rs.getDate(12), mat = rs.getDate(13), date = rs.getDate(6);
        return new Loan(typeCode, rs.getString(2), refNo, rs.getInt(4), statusName(rs.getInt(4)), amount, date == null ? null : date.toLocalDate(), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getBigDecimal(10),
            rs.getString(11), fpd == null ? null : fpd.toLocalDate(), mat == null ? null : mat.toLocalDate(), rs.getString(14), rs.getString(15), rs.getBigDecimal(16),
            rs.getInt(17) == 1, rs.getInt(18), outstanding, paid, rs.getBigDecimal(20), a.pastDuePrincipal(), a.daysPastDue(),
            next == null ? null : next.date(), next == null ? null : next.principal(), next == null ? null : next.interest(), last == null ? null : last.toLocalDate());
    }

    @GetMapping
    Summary loans(@PathVariable int branchCode, @PathVariable int clientId, @RequestParam(required = false) LocalDate asOf, @RequestParam(defaultValue = "false") boolean includePaid) {
        LocalDate day = asOf == null ? LocalDate.now() : asOf;
        List<Loan> all = jdbc.query(LOANS + " ORDER BY l.LoanTR_DATE DESC, l.LoanREF_NO DESC", (rs, i) -> loan(rs, branchCode, clientId, day), branchCode, clientId, CLASS_LOANS);
        List<Loan> loans = all.stream().filter((l) -> includePaid || l.outstanding().signum() > 0).toList();
        BigDecimal outstanding = loans.stream().map(Loan::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pastDue = loans.stream().map(Loan::pastDuePrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(loans, outstanding, pastDue, day);
    }

    @GetMapping("/{typeCode}/{refNo}")
    Detail detail(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo, @RequestParam(required = false) LocalDate asOf) {
        LocalDate day = asOf == null ? LocalDate.now() : asOf;
        List<Loan> found = jdbc.query(LOANS + " AND l.LoanSLT_CODE = ? AND l.LoanREF_NO = ?", (rs, i) -> loan(rs, branchCode, clientId, day), branchCode, clientId, CLASS_LOANS, typeCode, refNo);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such loan.");
        Loan loan = found.get(0);
        List<String> remarkRows = jdbc.queryForList("SELECT LoanRemarks FROM loan WHERE LoanBR_CODE = ? AND ClientIDLoan = ? AND LoanSLC_CODE = ? AND LoanSLT_CODE = ? AND LoanREF_NO = ?",
            String.class, branchCode, clientId, CLASS_LOANS, typeCode, refNo);
        String remarks = remarkRows.isEmpty() ? null : remarkRows.get(0); // the remarks themselves are often empty (null)

        List<CoMaker> coMakers = jdbc.query("""
                SELECT ClientIDCoMaker, CoMakerClientName, CONCAT_WS(', ', NULLIF(CoMakerAddress1, ''), NULLIF(CoMakerAddress2, '')) FROM comaker
                WHERE CoMakerBR_CODE = ? AND CoMakerClientID = ? AND CoMakerSLC_CODE = ? AND CoMakerSLT_CODE = ? AND CoMakerREF_NO = ? ORDER BY ID
                """, (rs, i) -> new CoMaker((Integer) rs.getObject(1), rs.getString(2), rs.getString(3)), branchCode, clientId, CLASS_LOANS, typeCode, refNo);

        List<BigDecimal> balances = new ArrayList<>();
        List<Due> dues = dues(branchCode, clientId, typeCode, refNo, balances);
        List<ScheduleRow> schedule = new ArrayList<>();
        for (LoanRules.Row r : LoanRules.schedule(dues, balances, loan.principalPaid(), day)) {
            schedule.add(new ScheduleRow(r.date(), r.principalDue(), r.interestDue(), r.principalBalance(), r.state().name(), r.paidOfThis()));
        }

        // ledger entries oldest first, with the principal balance after each one
        record Raw(LocalDate date, int event, String eventName, int code, String name, String doc, BigDecimal amount) {}
        List<Raw> raw = jdbc.query("""
                SELECT d.TR_DATE, d.SLE_CODE, e.SLE_TypeDesc, d.TR_CODE, tt.TransTypeDesc, d.TR_DOCNO, d.AMT
                FROM sldtl d
                LEFT JOIN (SELECT SLE_TypeCode, MIN(SLE_TypeDesc) AS SLE_TypeDesc FROM sle_type GROUP BY SLE_TypeCode) e ON e.SLE_TypeCode = d.SLE_CODE
                LEFT JOIN transtype tt ON tt.TransTypeID = d.TR_CODE
                WHERE d.SL_BRCODE = ? AND d.SL_CLIENTID = ? AND d.SLC_CODE = ? AND d.SLT_CODE = ? AND d.REF_NO = ?
                ORDER BY d.TR_DATE, d.SEQNO LIMIT ?
                """, (rs, i) -> new Raw(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7)),
            branchCode, clientId, CLASS_LOANS, typeCode, refNo, MAX_ENTRIES + 1);
        boolean truncated = raw.size() > MAX_ENTRIES;
        List<Entry> entries = new ArrayList<>();
        BigDecimal principal = BigDecimal.ZERO;
        for (Raw r : raw.subList(0, Math.min(raw.size(), MAX_ENTRIES))) {
            if (r.event() == PRINCIPAL) principal = principal.add(r.amount());
            entries.add(new Entry(r.date(), r.event(), r.eventName(), r.code(), r.name(), r.doc(),
                r.amount().signum() > 0 ? r.amount() : BigDecimal.ZERO, r.amount().signum() < 0 ? r.amount().negate() : BigDecimal.ZERO, r.event() == PRINCIPAL ? principal : null));
        }
        return new Detail(loan, remarks, coMakers, schedule, entries, truncated);
    }
}
