package ph.coop.core.accounting.ledger;

import java.sql.Date;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.accounting.close.PostingPeriods;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.OnConnectionSwitch;
import ph.coop.core.db.Db;

/**
 * The one way to write the old system's ledger (documentation/design/posting-engine.md). A module hands over a {@link Voucher}; this checks it,
 * numbers it, writes its header ({@code transsum}), lines ({@code transdt}) and general-ledger rows ({@code gltrans}) in the old system's format for
 * its {@link Voucher.Style}, remembers its request key and audits it, all inside the caller's transaction, so a refusal or a failure writes nothing.
 *
 * <p>The same request key twice gives back the first voucher and writes nothing (a double click, a retry after a lost connection).
 */
@Service
public class Ledger implements OnConnectionSwitch {

    /** The voucher as written: where to find it, and whether it was written now or by an earlier request with the same key. */
    public record Posted(int branch, int year, int module, int code, long controlNo, String documentNo, LocalDate date, boolean repeated) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private volatile Boolean keysKept;

    public Ledger(JdbcTemplate jdbc, Db db, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
    }

    @Override
    public void connectionSwitched() {
        keysKept = null;
    }

    /** Whether this database has the request-key table (PostgreSQL V026, MySQL V063). Without it, vouchers are written but not protected against repeats. */
    private boolean keysKept() {
        Boolean k = keysKept;
        if (k == null) {
            k = db.tableExists("ecis_posting_request");
            keysKept = k;
        }
        return k;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Posted write(Voucher v) {
        if (keysKept()) {
            List<Posted> earlier = jdbc.query("SELECT branch_code, tr_year, tr_module, tr_code, tr_ctlno, doc_no, tr_date FROM ecis_posting_request WHERE request_key = ?",
                (rs, i) -> new Posted(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5), rs.getString(6), rs.getDate(7).toLocalDate(), true), v.requestKey());
            if (!earlier.isEmpty()) return earlier.get(0);
        }
        check(v);

        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        int year = v.date().getYear();
        long ctl = db.nextControlNumber(v.branch(), v.module(), v.code(), year);
        String docNo = LedgerRules.documentNo(v.style(), ctl);
        Date day = Date.valueOf(v.date());
        jdbc.update("""
                INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, v.branch(), year, v.module(), v.code(), ctl, docNo, day, now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                v.date().format(DateTimeFormatter.BASIC_ISO_DATE), LedgerRules.explanation(v.explanation()), Timestamp.valueOf(now));

        List<Object[]> lines = new ArrayList<>();
        List<Object[]> gl = new ArrayList<>();
        int seq = 0;
        for (Voucher.Line l : v.lines()) {
            Voucher.People p = v.people();
            lines.add(switch (v.style()) {
                case GENERAL_JOURNAL -> new Object[]{v.branch(), year, v.module(), v.code(), ctl, seq++, l.dept(), l.account(), day, l.amount().value(), p.encodedBy(), p.approvedBy(), p.postedBy()};
                case CLOSING_ENTRY -> new Object[]{v.branch(), year, v.module(), v.code(), ctl, seq++, l.dept(), l.account(), l.amount().value(), p.encodedBy(), p.approvedBy(), p.postedBy()};
            });
            gl.add(new Object[]{v.branch(), l.account(), day, v.code(), l.dept(), l.amount().debit(), l.amount().credit(), Timestamp.valueOf(now)});
        }
        for (Voucher.GlOnly g : v.glOnly()) gl.add(new Object[]{v.branch(), g.account(), day, v.code(), g.dept(), g.amount().debit(), g.amount().credit(), Timestamp.valueOf(now)});
        jdbc.batchUpdate(switch (v.style()) {
            case GENERAL_JOURNAL -> """
                    INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSL_DATE, DTAMT, DTAMTDUE,
                      DTENCDBY, DTAPPRVBY, DTPOSTBY, DTREFERENCE, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, '', 0, 0, 0)
                    """;
            case CLOSING_ENTRY -> """
                    INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSL_DATE, DTADJ_FLAG, DTAMT,
                      DTENCDBY, DTAPPRVBY, DTPOSTBY, DTUPD_TAG, DTREFERENCE, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?, ?, ?, ?, 1, '', 0, 0, 0)
                    """;
        }, lines);
        jdbc.batchUpdate("""
                INSERT INTO gltrans (GLTransBR_CODE, GLTransACCT_CODE, GLTransTR_DATE, GLTransTR_CODE, GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT,
                  GLTransDR_BALB4, GLTransCR_BALB4, GLTransSUM_BALB4, GLTransSYNC, GLTransLASTUPDATED)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, ?)
                """, gl);

        if (keysKept()) {
            jdbc.update("INSERT INTO ecis_posting_request (request_key, username, branch_code, tr_year, tr_module, tr_code, tr_ctlno, doc_no, tr_date, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                v.requestKey(), v.username(), v.branch(), year, v.module(), v.code(), ctl, docNo, day, Timestamp.valueOf(now));
        }
        AuditLog.write(jdbc, v.username(), "ledger.write", "transsum", v.branch() + "/" + year + "/" + v.module() + "/" + v.code() + "/" + ctl, null,
            v.style() + ", " + v.lines().size() + " lines, " + LedgerRules.total(v.lines()).toPlainString() + " debits, key " + v.requestKey(), null);
        return new Posted(v.branch(), year, v.module(), v.code(), ctl, docNo, v.date(), false);
    }

    /** Refuses a voucher that does not balance, has an unknown, summary or switched-off account, or falls in a closed year or period. */
    private void check(Voucher v) {
        String problem = LedgerRules.problem(v.lines());
        if (problem != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        boolean closing = v.style() == Voucher.Style.CLOSING_ENTRY;
        for (String account : v.lines().stream().map(Voucher.Line::account).distinct().toList()) {
            List<Map<String, Object>> a = jdbc.queryForList("SELECT COAacct_tag, COALESCE(COAisdisabled, 0) AS off FROM coa WHERE COABR_CODE = ? AND COAID = ?", v.branch(), account);
            if (a.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown account " + account + ".");
            if ("S".equals(a.get(0).get("COAacct_tag"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account " + account + " is a summary account and takes no postings.");
            // closing a year brings every income and expense account to zero, even one switched off during the year
            if (!closing && ((Number) a.get(0).get("off")).intValue() != 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account " + account + " is switched off.");
        }
        // the year-end close is dated 31 December of the year it closes; its own checks decide whether that year may be closed
        if (!closing) PostingPeriods.requireOpen(jdbc, v.branch(), v.date());
    }
}
