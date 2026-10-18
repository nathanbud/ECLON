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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The old "Loans Profile" screen as data: the setup of one loan (type, classification, principal, term, interest, penalty, due-date options,
 * co-makers, collateral, pledges) and the client header. Read only. Labels come from the legacy lookup tables where they exist; where none does
 * (joint type, the diminishing frequency) the value is shown as inferred from the data. The schedule comes from the stored rows of
 * amortsched, with {@link LoanSchedule} used only to check that the stored schedule is the one the old rules would build.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/loans/{typeCode}/{refNo}/profile")
class LoanProfileController {

    record Header(String clientNo, String lastName, String firstName, String middleName, String suffix, LocalDate dateOpened, String accountType, String jointType, String clientType, String accountStatus) {}

    record Setup(String loanType, String classification, int refNo, BigDecimal principal, BigDecimal balance, LocalDate loanDate, int termGraceDays, int terms, String termUnit, String paymentMode, String status,
                 BigDecimal interestRate, String basedOn, String diminish, int principalGraceDue, int prepaidPayments, BigDecimal penaltyRate, BigDecimal penaltyAfterMaturity,
                 String penaltyComputed, int penaltyGraceDays, BigDecimal insurance, String dueDateOption, LocalDate firstInterestDue, LocalDate firstPrincipalDue, LocalDate maturity,
                 String collectionType, String loanClass, String purpose) {}

    record CoMaker(Integer clientId, String name, String address) {}

    record Collateral(String type, BigDecimal amount, String description) {}

    record Pledge(String profile, BigDecimal amount, Integer clientPledge) {}

    record ScheduleCheck(boolean checked, boolean matches, String note) {}

    record Profile(Header header, Setup setup, List<CoMaker> coMakers, Collateral collateral, List<Pledge> pledges, String remarks, ScheduleCheck scheduleCheck) {}

    private final JdbcTemplate jdbc;

    LoanProfileController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    Profile profile(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo) {
        List<Header> h = jdbc.query("""
                SELECT k.ClientID, k.LName, k.FName, k.MName, k.SName, k.DateOpened, at.AcctTypeDesc, k.JointType, ct.ClientTypeDesc, st.AcctStatDesc
                FROM client k LEFT JOIN accttype at ON at.AccTypeID = k.AccountType LEFT JOIN clienttype ct ON ct.ClientTypeID = k.ClientType LEFT JOIN acctstat st ON st.AcctStatID = k.AccountStatus
                WHERE k.ClientIDBrCode = ? AND k.ClientID = ?
                """, (rs, i) -> new Header(String.format("%02d-%07d", branchCode, rs.getInt(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime().toLocalDate(), rs.getString(7), jointType(rs.getInt(8)), rs.getString(9), rs.getString(10)), branchCode, clientId);
        if (h.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client.");

        List<Setup> s = jdbc.query("""
                SELECT t.SLTypeM_DESC, bc.BorrowerClassDesc, l.LoanREF_NO, l.LoanPAMT, l.LoanTR_DATE, l.LoanPRIN_GP, l.LoanTERMS, tm.TermDesc, pm.PaymentModeDesc, l.LoanSTATUS,
                       l.LoanINT_RATE, am.AmorTypeDesc, l.LoanDIMB_FREQ, l.LoanINT_GP, l.LoanPREPAIDPYMTS, l.LoanPEN_RATE, l.LoanPEN_RATE_AM, pen.PaymentModeDesc, l.LoanPENGP,
                       l.LoanINSAMT, dd.DueDateTypeDesc, l.LoanFID_DATE, l.LoanFPD_DATE, l.LoanMAT_DATE, co.CollectTypeDesc, lc.LoanClassDesc, lp.LoanPurposeDesc,
                       (SELECT COALESCE(SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = l.LoanBR_CODE AND d.SL_CLIENTID = l.ClientIDLoan AND d.SLC_CODE = l.LoanSLC_CODE
                          AND d.SLT_CODE = l.LoanSLT_CODE AND d.REF_NO = l.LoanREF_NO AND d.SLE_CODE = 11)
                FROM loan l
                LEFT JOIN sltype t ON t.SLTypeBR_CODE = l.LoanBR_CODE AND t.SLTypeSLC_CODE = l.LoanSLC_CODE AND t.SLTypeSLT_CODE = l.LoanSLT_CODE
                LEFT JOIN borrowerclass bc ON bc.BorrowerClassID = l.LoanMCLASS
                LEFT JOIN term tm ON tm.TermID = l.LoanTERM_PERD
                LEFT JOIN paymentmode pm ON pm.PaymentModeID = l.LoanPPMT_MODE
                LEFT JOIN paymentmode pen ON pen.PaymentModeID = l.LoanPEN_MODE
                LEFT JOIN amortype am ON am.AmorTypeID = l.LoanAMORTYPE
                LEFT JOIN duedatetype dd ON dd.DueDateTypeID = l.LoanDD_FLAG
                LEFT JOIN collecttype co ON co.CollectTypeID = l.LoanCOLLECTTYPE
                LEFT JOIN loanclass lc ON lc.LoanClassID = l.LoanLOANCLASS
                LEFT JOIN loanpurpose lp ON lp.LoanPurposeID = l.LoanPURPOSE
                WHERE l.LoanBR_CODE = ? AND l.ClientIDLoan = ? AND l.LoanSLC_CODE = 12 AND l.LoanSLT_CODE = ? AND l.LoanREF_NO = ?
                """, (rs, i) -> new Setup(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4), rs.getBigDecimal(28), date(rs, 5), rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9),
                    LoansController.statusName(rs.getInt(10)), rs.getBigDecimal(11), rs.getString(12), diminish(rs.getInt(13)), rs.getInt(14), rs.getInt(15), rs.getBigDecimal(16), rs.getBigDecimal(17),
                    rs.getString(18), rs.getInt(19), rs.getBigDecimal(20), rs.getString(21), date(rs, 22), date(rs, 23), date(rs, 24), rs.getString(25), rs.getString(26), rs.getString(27)),
            branchCode, clientId, typeCode, refNo);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such loan.");
        Setup setup = s.get(0);

        List<CoMaker> coMakers = jdbc.query("""
                SELECT ClientIDCoMaker, CoMakerClientName, CONCAT_WS(', ', NULLIF(CoMakerAddress1, ''), NULLIF(CoMakerAddress2, '')) FROM comaker
                WHERE CoMakerBR_CODE = ? AND CoMakerClientID = ? AND CoMakerSLC_CODE = 12 AND CoMakerSLT_CODE = ? AND CoMakerREF_NO = ? ORDER BY ID
                """, (rs, i) -> new CoMaker((Integer) rs.getObject(1), rs.getString(2), rs.getString(3)), branchCode, clientId, typeCode, refNo);

        List<Collateral> coll = jdbc.query("""
                SELECT ct.CollateralTypeDesc, l.LoanCOLLAMT, l.LoanCOLLDESC FROM loan l LEFT JOIN collateraltype ct ON ct.CollateralTypeID = l.LoanCOLLTYPE
                WHERE l.LoanBR_CODE = ? AND l.ClientIDLoan = ? AND l.LoanSLC_CODE = 12 AND l.LoanSLT_CODE = ? AND l.LoanREF_NO = ?
                """, (rs, i) -> new Collateral(rs.getString(1), rs.getBigDecimal(2), rs.getString(3)), branchCode, clientId, typeCode, refNo);

        List<Pledge> pledges = jdbc.query("""
                SELECT pp.* FROM (SELECT p.LOANPLEDGEPROFILEID, p.AMOUNT, p.CLIENTPLEDGEID FROM loanpledge p
                  WHERE p.BR_CODE = ? AND p.CLIENTID = ? AND p.SLC_CODE = 12 AND p.SLT_CODE = ? AND p.REF_NO = ?) pp
                """, (rs, i) -> new Pledge(String.valueOf(rs.getInt(1)), rs.getBigDecimal(2), (Integer) rs.getObject(3)), branchCode, clientId, typeCode, refNo);

        List<String> remarks = jdbc.queryForList("SELECT LoanRemarks FROM loan WHERE LoanBR_CODE = ? AND ClientIDLoan = ? AND LoanSLC_CODE = 12 AND LoanSLT_CODE = ? AND LoanREF_NO = ?",
            String.class, branchCode, clientId, typeCode, refNo);

        return new Profile(h.get(0), setup, coMakers, coll.isEmpty() ? null : coll.get(0), pledges, remarks.isEmpty() ? null : remarks.get(0), check(branchCode, clientId, typeCode, refNo));
    }

    private static LocalDate date(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    /** No lookup table exists; 3 is "None" on the sample screen and by far the most common value, the others are shown as codes. */
    static String jointType(int code) {
        return code == 3 ? "NONE" : "Joint type " + code;
    }

    /** 1 is the only value the data shows and the old screen labels it "E-DueDate"; no lookup table exists. */
    static String diminish(int code) {
        return code == 1 ? "E-DueDate" : "Code " + code;
    }

    /** Builds the schedule the old rules give for this loan and compares it with the stored one; only the mapped cases (monthly, quarterly, lump sum, diminishing balance) are checked. */
    private ScheduleCheck check(int branch, int clientId, int typeCode, int refNo) {
        List<Object[]> l = jdbc.query("""
                SELECT LoanPAMT, LoanTERMS, LoanTERM_PERD, LoanPPMT_MODE, LoanINT_RATE, LoanTR_DATE, LoanAMORTYPE, LoanIPMT_MODE FROM loan
                WHERE LoanBR_CODE = ? AND ClientIDLoan = ? AND LoanSLC_CODE = 12 AND LoanSLT_CODE = ? AND LoanREF_NO = ?
                """, (rs, i) -> new Object[] {rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getBigDecimal(5), rs.getDate(6), rs.getInt(7), rs.getInt(8)}, branch, clientId, typeCode, refNo);
        if (l.isEmpty()) return new ScheduleCheck(false, false, "No loan.");
        Object[] x = l.get(0);
        int unit = (Integer) x[2], mode = (Integer) x[3], amor = (Integer) x[6], imode = (Integer) x[7];
        if (unit != 4 || amor != LoanSchedule.DIMINISHING || imode != mode || (mode != LoanSchedule.MONTHLY && mode != LoanSchedule.QUARTERLY && mode != LoanSchedule.LUMP_SUM)) {
            return new ScheduleCheck(false, false, "This kind of schedule is not checked yet.");
        }
        List<LoanSchedule.Row> built;
        try {
            built = LoanSchedule.diminishing((BigDecimal) x[0], (Integer) x[1], mode, (BigDecimal) x[4], ((java.sql.Date) x[5]).toLocalDate());
        } catch (IllegalArgumentException e) {
            return new ScheduleCheck(false, false, e.getMessage());
        }
        List<Object[]> stored = jdbc.query("SELECT DUEDATE, PRINDUE, INTDUE, PRINBAL FROM amortsched WHERE BR_CODE = ? AND CLIENTID = ? AND SLC_CODE = 12 AND SLT_CODE = ? AND REF_NO = ? ORDER BY DUEDATE",
            (rs, i) -> new Object[] {rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)}, branch, clientId, typeCode, refNo);
        boolean same = stored.size() == built.size();
        List<String> diffs = new ArrayList<>();
        for (int i = 0; same && i < built.size(); i++) {
            LoanSchedule.Row b = built.get(i);
            Object[] s = stored.get(i);
            if (!b.dueDate().equals(s[0]) || b.principalDue().compareTo((BigDecimal) s[1]) != 0 || b.interestDue().compareTo((BigDecimal) s[2]) != 0 || b.principalBalance().compareTo((BigDecimal) s[3]) != 0) {
                same = false;
                diffs.add(b.dueDate().toString());
            }
        }
        return new ScheduleCheck(true, same, same ? "The stored schedule is the one the old rules give." : "The stored schedule differs from the old rules" + (diffs.isEmpty() ? " (number of rows)." : " from " + diffs.get(0) + "."));
    }
}
