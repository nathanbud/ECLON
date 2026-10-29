package ph.coop.core.loan;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.loan.LoanWorksheet.Posting;
import ph.coop.core.loan.LoanWorksheet.ScheduleDate;

/**
 * Gathers one loan's header, ledger postings and schedule from the legacy tables (read only) and runs {@link LoanWorksheet} on them. The worksheet is what the old system's
 * "Loan Interest and Penalty Computation Worksheet" showed; the PDF and Excel files are written from the {@link Worksheet} this returns.
 */
@Component
public class LoanWorksheetService {

    private static final int CLASS_LOANS = 12;

    /** Everything printed above the table. Text is already formatted for the page where the old worksheet formatted it. */
    public record Header(String coopName, String officeName, String address, String tin, String accountNo, String borrower, String refNo, String loanType,
                         BigDecimal principal, String principalMode, BigDecimal interestRate, BigDecimal penaltyRate, LocalDate released, String term, String amortization,
                         String paymentMode, LocalDate maturity, String interestMode, String status, String accountOfficer, String collector) {}

    public record Worksheet(Header header, LocalDate asOf, LoanWorksheet.Result result) {}

    private final JdbcTemplate jdbc;
    private final String configuredName;

    LoanWorksheetService(JdbcTemplate jdbc, @org.springframework.beans.factory.annotation.Value("${coop.name:}") String configuredName) {
        this.jdbc = jdbc;
        this.configuredName = configuredName;
    }

    private static String trTag(int code) {
        return switch (code) { case 20 -> "LV"; case 21 -> "CV"; default -> "TR" + code; };
    }

    private static String statusText(int code) {
        return switch (code) { case 11 -> "Current"; case 12 -> "Past Due"; case 13 -> "In Litigation"; case 14 -> "Restructured"; default -> String.valueOf(code); };
    }

    private String desc(String sql, Object... args) {
        List<String> r = jdbc.query(sql, (rs, i) -> rs.getString(1), args);
        return r.isEmpty() || r.get(0) == null ? "" : r.get(0).trim();
    }

    private String coopName(String acronym) {
        for (String sql : new String[] {"SELECT coop_name FROM coopsystem_report_header LIMIT 1", "SELECT coop_name FROM ecis_branding LIMIT 1"}) {
            try {
                String n = desc(sql);
                if (!n.isBlank()) return n;
            } catch (org.springframework.dao.DataAccessException e) {
                // the table is not there on this database; try the next source
            }
        }
        return configuredName.isBlank() ? acronym : configuredName;
    }

    public Optional<Worksheet> build(int branch, int clientId, int typeCode, int refNo, LocalDate asOf) {
        List<Map<String, Object>> loans = jdbc.queryForList("SELECT * FROM loan WHERE LoanBR_CODE = ? AND ClientIDLoan = ? AND LoanSLC_CODE = ? AND LoanSLT_CODE = ? AND LoanREF_NO = ?",
            branch, clientId, CLASS_LOANS, typeCode, refNo);
        if (loans.isEmpty()) return Optional.empty();
        Map<String, Object> l = loans.get(0);
        LocalDate released = ((java.sql.Date) l.get("LoanTR_DATE")).toLocalDate();
        BigDecimal interest = (BigDecimal) l.get("LoanINT_RATE"), penalty = l.get("LoanPEN_RATE") == null ? null : (BigDecimal) l.get("LoanPEN_RATE");

        // Postings grouped by voucher: principal (event 11: a release is positive, a payment negative), interest paid (23) and penalty paid (41), credits negative in the ledger.
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        Map<String, Object[]> keys = new LinkedHashMap<>();
        jdbc.query("SELECT TR_DATE, TR_CODE, TR_CTLNO, TR_DOCNO, SLE_CODE, AMT FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? "
            + "AND SLE_CODE IN (11, 23, 41) AND TR_DATE <= ? ORDER BY TR_DATE, SEQNO", (rs) -> {
                String k = rs.getDate(1) + "|" + rs.getInt(2) + "|" + rs.getString(3) + "|" + rs.getString(4);
                keys.putIfAbsent(k, new Object[] {rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getString(3), rs.getString(4)});
                BigDecimal[] s = sums.computeIfAbsent(k, (x) -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                int event = rs.getInt(5);
                BigDecimal amt = rs.getBigDecimal(6);
                s[event == 11 ? 0 : event == 23 ? 1 : 2] = s[event == 11 ? 0 : event == 23 ? 1 : 2].add(amt);
            }, branch, clientId, CLASS_LOANS, typeCode, refNo, java.sql.Date.valueOf(asOf));
        List<Posting> postings = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : sums.entrySet()) {
            Object[] k = keys.get(e.getKey());
            BigDecimal[] s = e.getValue();
            BigDecimal principal = s[0];
            postings.add(new Posting((LocalDate) k[0], trTag((Integer) k[1]), pad((String) k[2]), (String) k[3],
                principal.signum() < 0 ? principal.negate() : BigDecimal.ZERO, principal.signum() > 0 ? principal : BigDecimal.ZERO, s[1].negate(), s[2].negate()));
        }
        List<ScheduleDate> schedule = new ArrayList<>();
        jdbc.query("SELECT DUEDATE, PRINBAL FROM amortsched WHERE BR_CODE = ? AND CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? ORDER BY DUEDATE",
            (rs) -> { schedule.add(new ScheduleDate(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2))); }, branch, clientId, CLASS_LOANS, typeCode, refNo);

        List<Map<String, Object>> office = jdbc.queryForList("SELECT BranchesDesc, Address, TIN, ACRONYM FROM branches WHERE BranchesID = ?", branch);
        Map<String, Object> o = office.isEmpty() ? Map.of() : office.get(0);
        List<Map<String, Object>> client = jdbc.queryForList("SELECT LName, FName, MName, ClientIDChkDgt FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", branch, clientId);
        String borrower = "";
        String chk = "";
        if (!client.isEmpty()) {
            Map<String, Object> c = client.get(0);
            String fn = str(c.get("FName")), mn = str(c.get("MName"));
            borrower = str(c.get("LName")) + ", " + fn + (mn.isBlank() ? "" : " " + mn.charAt(0) + ".");
            chk = c.get("ClientIDChkDgt") == null ? "" : "-" + c.get("ClientIDChkDgt");
        }
        String type = desc("SELECT SLTypeM_DESC FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, CLASS_LOANS, typeCode);
        String purpose = l.get("LoanPURPOSE") == null ? "" : desc("SELECT LoanPurposeDesc FROM loanpurpose WHERE LoanPurposeID = ?", l.get("LoanPURPOSE"));
        String pmode = l.get("LoanPPMT_MODE") == null ? "" : desc("SELECT PaymentModeDesc FROM paymentmode WHERE PaymentModeID = ?", l.get("LoanPPMT_MODE"));
        String imode = l.get("LoanIPMT_MODE") == null ? "" : desc("SELECT PaymentModeDesc FROM paymentmode WHERE PaymentModeID = ?", l.get("LoanIPMT_MODE"));
        String amort = l.get("LoanAMORTYPE") == null ? "" : desc("SELECT AmorTypeDesc FROM amortype WHERE AmorTypeID = ?", l.get("LoanAMORTYPE"));
        String unit = l.get("LoanTERM_PERD") == null ? "" : desc("SELECT TermDesc FROM term WHERE TermID = ?", l.get("LoanTERM_PERD"));
        String term = l.get("LoanTERMS") == null ? "" : new BigDecimal(String.valueOf(l.get("LoanTERMS"))).setScale(2) + " " + unit;
        String acronym = str(o.get("ACRONYM"));
        Header h = new Header(coopName(acronym), str(o.get("BranchesDesc")), str(o.get("Address")), str(o.get("TIN")),
            String.format("%04d-%07d%s", branch, clientId, chk), borrower, String.format("%02d%02d-%08d", CLASS_LOANS, typeCode, refNo),
            type + (purpose.isBlank() ? "" : " - " + purpose), (BigDecimal) l.get("LoanPAMT"), pmode, interest, penalty, released, term, amort, pmode,
            l.get("LoanMAT_DATE") == null ? null : ((java.sql.Date) l.get("LoanMAT_DATE")).toLocalDate(), imode,
            l.get("LoanSTATUS") == null ? "" : statusText(((Number) l.get("LoanSTATUS")).intValue()), "", l.get("LoanCOLLECTOR") == null ? "na" : String.valueOf(l.get("LoanCOLLECTOR")));
        return Optional.of(new Worksheet(h, asOf, LoanWorksheet.compute(released, interest, penalty, postings, schedule, asOf)));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String pad(String s) {
        return s == null ? "" : s.length() >= 8 ? s : "0".repeat(8 - s.length()) + s;
    }

    /** The moment the worksheet is printed, for its "Date Created" line. */
    static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
