package ph.coop.core.member;

import static ph.coop.core.web.ApiErrors.notFound;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The posted transaction behind a row of a member's ledger (a loan voucher, a cash receipt, a general journal and so on): its header, every general ledger and subsidiary ledger
 * line it wrote, and the cheques it carried. Read only, from {@code transsum}, {@code transdt} and {@code transck}. A transaction is shown only when it has a row in this member's
 * own ledger, so the ledger row is the way in and a control number cannot be used to browse other transactions.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/transactions/{year}/{code}/{ctlNo}")
class TransactionSourceController {

    record Header(int year, int module, int code, long ctlNo, String type, String typeShort, String controlNo, String docNo, String date, String time, String batchNo,
                  String department, String client, String clientNo, String andOr, String sourceReference, String explanation, String status) {}

    /** {@code sc}, {@code st}, {@code se} and {@code sn} are the subsidiary ledger class, type, event and number, empty for a line that is only on the general ledger. */
    record Line(int seq, boolean posted, Integer sc, Integer st, Integer se, Integer sn, String glCode, String title, BigDecimal debit, BigDecimal credit, String clientNo, String clientName) {}

    record Cheque(int seq, String type, String number, String bank, String date, BigDecimal amount, String payee, boolean cleared, String clearedOn) {}

    record Detail(Header header, List<Line> lines, List<Cheque> cheques, BigDecimal debit, BigDecimal credit, BigDecimal difference) {}

    private final JdbcTemplate jdbc;

    TransactionSourceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    Detail detail(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int year, @PathVariable int code, @PathVariable long ctlNo) {
        Integer own = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND TR_CODE = ? AND TR_CTLNO = ? AND EXTRACT(YEAR FROM TR_DATE) = ?",
            Integer.class, branchCode, clientId, code, ctlNo, year);
        if (own == null || own == 0) throw notFound("This member's ledger has no such transaction.");

        // The year of a ledger row is the year of its date; a transaction keyed in the next year's books is found one year either side, the exact year first.
        List<Header> found = jdbc.query("""
                SELECT t.TR_YEAR, t.TR_MODULE, t.TR_CODE, t.TR_CTLNO, tt.TransTypeDesc, tt.TransTypeDesc2, t.TR_DOCNO, t.TR_DATE, t.TR_TIME, t.BATCH_NO, t.CLIENTNAME, t.TR_CLIENTID,
                       t.ANDOR_NAME, t.TR_REFNO, t.EXPLANATION,
                       c.LName, c.FName, c.MName
                FROM transsum t
                LEFT JOIN transtype tt ON tt.TransTypeID = t.TR_CODE
                LEFT JOIN client c ON c.ClientIDBrCode = t.TR_BRCODE AND c.ClientID = t.TR_CLIENTID
                WHERE t.TR_BRCODE = ? AND t.TR_CODE = ? AND t.TR_CTLNO = ? AND t.TR_YEAR IN (?, ?, ?)
                ORDER BY CASE WHEN t.TR_YEAR = ? THEN 0 ELSE 1 END LIMIT 1
                """, (rs, i) -> {
                java.sql.Date date = rs.getDate(8);
                java.sql.Time time = rs.getTime(9);
                Integer owner = rs.getObject(12) == null ? null : rs.getInt(12);
                String name = name(rs.getString(16), rs.getString(17), rs.getString(18));
                return new Header(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(1) + " - " + pad(rs.getLong(4), 8), rs.getString(7),
                    date == null ? null : date.toString(), time == null ? null : time.toString(), rs.getString(10), null, name != null ? name : clean(rs.getString(11)),
                    owner == null ? null : clientNo(branchCode, owner), clean(rs.getString(13)), clean(rs.getString(14)), clean(rs.getString(15)), null);
            }, branchCode, code, ctlNo, year - 1, year, year + 1, year);
        if (found.isEmpty()) throw notFound("The transaction is not on file.");
        Header h = found.get(0);

        List<Boolean> approved = new ArrayList<>();
        List<Integer> depts = new ArrayList<>();
        List<Line> lines = jdbc.query("""
                SELECT d.DTSEQ_NO, d.DTPOSTBY, d.DTAPPRVBY, d.DTSLC_CODE, d.DTSLT_CODE, d.DTSLE_CODE, d.DTCTS_NUM, d.DTACCT_CODE, d.DTAMT, d.DTDEPT,
                       s.SLTypeM_DESC, co.COADesc, ev.SLE_TypeDesc, d.ClientIDDT, d.ClientIDDTNA, cl.LName, cl.FName, cl.MName
                FROM transdt d
                LEFT JOIN coa co ON co.COABR_CODE = d.DTTR_BRCODE AND co.COAID = d.DTACCT_CODE
                LEFT JOIN sltype s ON s.SLTypeBR_CODE = d.DTTR_BRCODE AND s.SLTypeSLC_CODE = d.DTSLC_CODE AND s.SLTypeSLT_CODE = d.DTSLT_CODE
                LEFT JOIN (SELECT SLE_TypeCode, MIN(SLE_TypeDesc) AS SLE_TypeDesc FROM sle_type GROUP BY SLE_TypeCode) ev ON ev.SLE_TypeCode = d.DTSLE_CODE
                LEFT JOIN client cl ON cl.ClientIDBrCode = d.DTTR_BRCODE AND cl.ClientID = d.ClientIDDT
                WHERE d.DTTR_BRCODE = ? AND d.DTTR_YEAR = ? AND d.DTTR_MODULE = ? AND d.DTTR_CODE = ? AND d.DTTR_CTLNO = ?
                ORDER BY d.DTSEQ_NO
                """, (rs, i) -> {
                BigDecimal amount = rs.getBigDecimal(9) == null ? BigDecimal.ZERO : rs.getBigDecimal(9);
                approved.add(rs.getInt(3) > 0);
                if (rs.getObject(10) != null) depts.add(rs.getInt(10));
                Integer sc = rs.getObject(4) == null ? null : rs.getInt(4);
                // A subsidiary ledger line is titled by its account type and the event it records ("Salary Loan : Interest Paid"); a general ledger line by its account.
                String title = sc != null ? (clean(rs.getString(11)) == null ? "" : clean(rs.getString(11))) + " : " + (clean(rs.getString(13)) == null ? "" : clean(rs.getString(13)))
                    : clean(rs.getString(12));
                Integer owner = rs.getObject(14) == null ? null : rs.getInt(14);
                String who = name(rs.getString(16), rs.getString(17), rs.getString(18));
                // Legacy convention: a positive amount is a debit, a negative one a credit.
                return new Line(rs.getInt(1), rs.getInt(2) > 0, sc, (Integer) rs.getObject(5), (Integer) rs.getObject(6), (Integer) rs.getObject(7), rs.getString(8), title,
                    amount.signum() > 0 ? amount : BigDecimal.ZERO, amount.signum() < 0 ? amount.negate() : BigDecimal.ZERO,
                    owner == null ? null : clientNo(branchCode, owner), who != null ? who : clean(rs.getString(15)));
            }, branchCode, h.year(), h.module(), h.code(), h.ctlNo());

        List<Cheque> cheques = jdbc.query("""
                SELECT k.CKSEQ_NO, ct.COCITYPEDesc, k.CKCHECKNO, COALESCE(NULLIF(b.BCDesc2, ''), b.BCDesc), k.CKCHECKDATE, k.CKAMT, k.CKPAYEE, k.CKISCLEARED, cc.cleared_on
                FROM transck k
                LEFT JOIN cocitype ct ON ct.COCITYPEID = k.CKCOCI_TYPE
                LEFT JOIN bankcode b ON b.BCBR_CODE = k.CKTR_BRCODE AND b.BCID = k.CKBANK_CODE
                LEFT JOIN ecis_cheque_clearing cc ON cc.tr_year = k.CKTR_YEAR AND cc.ctl_no = k.CKTR_CTLNO AND cc.seq_no = k.CKSEQ_NO
                WHERE k.CKTR_BRCODE = ? AND k.CKTR_YEAR = ? AND k.CKTR_MODULE = ? AND k.CKTR_CODE = ? AND k.CKTR_CTLNO = ?
                ORDER BY k.CKSEQ_NO
                """, (rs, i) -> {
                java.sql.Date on = rs.getDate(5), cleared = rs.getDate(9);
                return new Cheque(rs.getInt(1), clean(rs.getString(2)), rs.getString(3), clean(rs.getString(4)), on == null ? null : on.toString(), rs.getBigDecimal(6), clean(rs.getString(7)),
                    rs.getInt(8) == 1, cleared == null ? null : cleared.toString());
            }, branchCode, h.year(), h.module(), h.code(), h.ctlNo());

        BigDecimal debit = lines.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = lines.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        String department = depts.isEmpty() ? null : jdbc.queryForList("SELECT DeptDesc FROM dept WHERE DeptID = ?", String.class, depts.get(0)).stream().findFirst().orElse(null);
        String status = lines.isEmpty() ? "NO ENTRIES" : approved.stream().allMatch((a) -> a) ? "APPROVED" : "FOR APPROVAL";
        Header out = new Header(h.year(), h.module(), h.code(), h.ctlNo(), h.type(), h.typeShort(), h.controlNo(), h.docNo(), h.date(), h.time(), h.batchNo(), department, h.client(), h.clientNo(),
            h.andOr(), h.sourceReference(), h.explanation(), status);
        return new Detail(out, lines, cheques, debit, credit, debit.subtract(credit));
    }

    /** The member number as the old system prints it: branch, number and check digit ("0001-0028711-9"). */
    static String clientNo(int branch, int clientId) {
        return pad(branch, 4) + "-" + pad(clientId, 7) + "-" + LifecycleRules.checkDigit(clientId);
    }

    static String pad(long n, int width) {
        String s = Long.toString(n);
        return "0".repeat(Math.max(0, width - s.length())) + s;
    }

    /** "Osting, Samson M.": last name, first name and the middle initial. Null when there is no name. */
    static String name(String last, String first, String middle) {
        String l = clean(last), f = clean(first), m = clean(middle);
        if (l == null && f == null) return null;
        return (l == null ? "" : l) + (f == null ? "" : (l == null ? "" : ", ") + f) + (m == null ? "" : " " + m.charAt(0) + ".");
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
