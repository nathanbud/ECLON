package ph.coop.core.deposit;

import java.math.BigDecimal;
import java.time.Clock;
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
import ph.coop.core.deposit.TimeDepositRules.Status;

/**
 * Read-only time deposits of a member (class 24 of the subsidiary ledger). A certificate is a row of {@code td} (type names in {@code sltype}: Short Term,
 * Lock In and so on); its money is ledger event 11 (credit negative) and the interest accrued at year end is event 71. Placing, rolling over,
 * pre-terminating and paying out are not posted here; see documentation/time-deposit-map.md.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/time-deposits")
class TimeDepositController {

    private static final int CLASS_TD = 24;
    private static final int PRINCIPAL = 11;
    private static final int ACCRUED = 71;
    private static final int MAX_WINDOW = 1000;

    /** estimatedInterest is the interest for the whole term on what the certificate holds (null once closed); see {@link TimeDepositRules#termInterest}. */
    record Deposit(int typeCode, String type, int refNo, String certificate, LocalDate placed, LocalDate maturity, int termDays, BigDecimal ratePercent, BigDecimal principal,
                   BigDecimal balance, BigDecimal accruedInterest, BigDecimal estimatedInterest, String status, String remarks) {}

    record Summary(List<Deposit> deposits, BigDecimal totalBalance, BigDecimal totalAccrued, LocalDate asOf) {}

    record Entry(LocalDate date, LocalDate glDate, int transactionCode, String transaction, String documentNo, int event, String eventName, BigDecimal amount, BigDecimal balanceAfter,
                  /** For money paid in by cheque: the cheque number, whether it has cleared, and the day it cleared when that was recorded. Empty for other entries. */ String chequeNo, Boolean chequeCleared, LocalDate clearedOn) {}

    record History(Deposit deposit, List<Entry> entries, int page, int size, long totalEntries) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    TimeDepositController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static final String DEPOSITS = """
            SELECT d.TDSLT_CODE, t.SLTypeM_DESC, d.TDREF_NO, d.TDCERT_NUM, d.TDTR_DATE, d.TDMAT_DATE, d.TDTERMS, d.TDINT_RATE, d.TDPAMT, d.TDSTATUS, d.TDREMARKS,
                   (SELECT COALESCE(-SUM(l.AMT), 0) FROM sldtl l WHERE l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE
                      AND l.SLT_CODE = d.TDSLT_CODE AND l.REF_NO = d.TDREF_NO AND l.SLE_CODE = 11),
                   (SELECT COALESCE(-SUM(l.AMT), 0) FROM sldtl l WHERE l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE
                      AND l.SLT_CODE = d.TDSLT_CODE AND l.REF_NO = d.TDREF_NO AND l.SLE_CODE = 71),
                   (SELECT COUNT(*) FROM sldtl l WHERE l.SL_BRCODE = d.TDBR_CODE AND l.SL_CLIENTID = d.ClientIDTD AND l.SLC_CODE = d.TDSLC_CODE
                      AND l.SLT_CODE = d.TDSLT_CODE AND l.REF_NO = d.TDREF_NO)
            FROM td d
            LEFT JOIN sltype t ON t.SLTypeBR_CODE = d.TDBR_CODE AND t.SLTypeSLC_CODE = d.TDSLC_CODE AND t.SLTypeSLT_CODE = d.TDSLT_CODE
            WHERE d.TDBR_CODE = ? AND d.ClientIDTD = ? AND d.TDSLC_CODE = ?
            """;

    private Deposit map(java.sql.ResultSet rs, LocalDate asOf) throws java.sql.SQLException {
        BigDecimal balance = rs.getBigDecimal(12);
        BigDecimal accrued = rs.getBigDecimal(13);
        boolean hasLedger = rs.getLong(14) > 0;
        java.sql.Date maturity = rs.getDate(6);
        LocalDate mat = maturity == null ? null : maturity.toLocalDate();
        Status status = TimeDepositRules.status(rs.getInt(10), hasLedger, balance, mat, asOf);
        BigDecimal rate = rs.getBigDecimal(8);
        BigDecimal basis = hasLedger ? balance : rs.getBigDecimal(9);
        BigDecimal estimate = status == Status.CLOSED || basis == null ? null : TimeDepositRules.termInterest(basis, rate, rs.getInt(7));
        java.sql.Date placed = rs.getDate(5);
        return new Deposit(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), placed == null ? null : placed.toLocalDate(), mat, rs.getInt(7), rate, rs.getBigDecimal(9),
            balance, accrued, estimate, status.name(), rs.getString(11));
    }

    @GetMapping
    Summary deposits(@PathVariable int branchCode, @PathVariable int clientId, @RequestParam(defaultValue = "false") boolean includeClosed) {
        LocalDate asOf = LocalDate.now(clock);
        List<Deposit> all = jdbc.query(DEPOSITS + " ORDER BY d.TDMAT_DATE DESC, d.TDREF_NO DESC", (rs, i) -> map(rs, asOf), branchCode, clientId, CLASS_TD);
        List<Deposit> shown = all.stream().filter((d) -> includeClosed || !d.status().equals(Status.CLOSED.name())).toList();
        BigDecimal total = shown.stream().filter((d) -> !d.status().equals(Status.NO_LEDGER.name())).map(Deposit::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal accrued = shown.stream().map(Deposit::accruedInterest).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(shown, total, accrued, asOf);
    }

    @GetMapping("/{typeCode}/{refNo}")
    History history(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo,
                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || size < 1 || (long) (page + 1) * size > MAX_WINDOW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for at most " + MAX_WINDOW + " entries from the newest.");
        }
        LocalDate asOf = LocalDate.now(clock);
        List<Deposit> found = jdbc.query(DEPOSITS + " AND d.TDSLT_CODE = ? AND d.TDREF_NO = ?", (rs, i) -> map(rs, asOf), branchCode, clientId, CLASS_TD, typeCode, refNo);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such time deposit.");
        Deposit deposit = found.get(0);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ?",
            Long.class, branchCode, clientId, CLASS_TD, typeCode, refNo);

        // Newest first. The balance shown is of the principal only: what the certificate held after that row.
        record Raw(LocalDate date, LocalDate gl, int code, String name, String doc, int event, BigDecimal amount, String chequeNo, Boolean cleared, LocalDate clearedOn) {}
        List<Raw> window = jdbc.query("""
                SELECT d.TR_DATE, d.GL_DATE, d.TR_CODE, COALESCE(NULLIF(tt.TransTypeDesc2, ''), tt.TransTypeDesc), d.TR_DOCNO, d.SLE_CODE, -d.AMT,
                       CASE WHEN d.SLE_CODE = 11 AND d.COCI_TYPE = 2 AND d.AMT < 0 THEN d.CHECKNO END,
                       CASE WHEN d.SLE_CODE = 11 AND d.COCI_TYPE = 2 AND d.AMT < 0 THEN (SELECT COALESCE(k.CKISCLEARED, 0) FROM transck k WHERE k.CKTR_BRCODE = d.SL_BRCODE AND k.CKTR_YEAR = EXTRACT(YEAR FROM d.TR_DATE) AND k.CKTR_CODE = d.TR_CODE AND k.CKTR_CTLNO = d.TR_CTLNO AND k.CKCHECKNO = d.CHECKNO AND k.CKCOCI_TYPE = 2 LIMIT 1) END,
                       CASE WHEN d.SLE_CODE = 11 AND d.COCI_TYPE = 2 AND d.AMT < 0 THEN (SELECT cc.cleared_on FROM transck k JOIN ecis_cheque_clearing cc ON cc.tr_year = k.CKTR_YEAR AND cc.ctl_no = k.CKTR_CTLNO AND cc.seq_no = k.CKSEQ_NO WHERE k.CKTR_BRCODE = d.SL_BRCODE AND k.CKTR_YEAR = EXTRACT(YEAR FROM d.TR_DATE) AND k.CKTR_CODE = d.TR_CODE AND k.CKTR_CTLNO = d.TR_CTLNO AND k.CKCHECKNO = d.CHECKNO AND k.CKCOCI_TYPE = 2 LIMIT 1) END
                FROM sldtl d LEFT JOIN transtype tt ON tt.TransTypeID = d.TR_CODE
                WHERE d.SL_BRCODE = ? AND d.SL_CLIENTID = ? AND d.SLC_CODE = ? AND d.SLT_CODE = ? AND d.REF_NO = ?
                ORDER BY d.TR_DATE DESC, d.SEQNO DESC LIMIT ?
                """, (rs, i) -> new Raw(rs.getDate(1).toLocalDate(), rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate(), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getBigDecimal(7),
                rs.getString(8), rs.getObject(9) == null ? null : rs.getInt(9) == 1, rs.getDate(10) == null ? null : rs.getDate(10).toLocalDate()),
                branchCode, clientId, CLASS_TD, typeCode, refNo, (page + 1) * size);
        List<Entry> entries = new ArrayList<>();
        BigDecimal running = deposit.balance();
        for (int i = 0; i < window.size(); i++) {
            Raw r = window.get(i);
            if (i >= page * size) entries.add(new Entry(r.date(), r.gl(), r.code(), r.name(), r.doc(), r.event(), TimeDepositRules.eventName(r.event()), r.amount(), running, r.chequeNo(), r.cleared(), r.clearedOn()));
            if (r.event() == PRINCIPAL) running = running.subtract(r.amount());
        }
        return new History(deposit, entries, page, size, total == null ? 0 : total);
    }
}
