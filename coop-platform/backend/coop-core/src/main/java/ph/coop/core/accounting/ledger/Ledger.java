package ph.coop.core.accounting.ledger;

import java.math.BigDecimal;
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
import ph.coop.core.businessday.BusinessDay;
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
    private final BusinessDay businessDay;
    private volatile Boolean keysKept;

    public Ledger(JdbcTemplate jdbc, Db db, Clock clock, BusinessDay businessDay) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.businessDay = businessDay;
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

    // ---- cash at the window -----------------------------------------------------------------------------------------

    private static final int DUPLICATE_SECONDS = 60;

    /**
     * Writes one cash transaction at the window (moved here from the front office's MemberLedgerPoster, rows unchanged; see the golden snapshots):
     * the header, the cheque lines and the cash line on the teller's drawer (first for money in, last for money out), one line per member line,
     * the member's ledger rows (with cheques: the cash pays the lines first, in order, then the cheques, in order, one row per piece), a tender row
     * per cash and cheque taken, and the general-ledger lines. Refused when the same client had one in the last minute, unless confirmed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Posted writeCash(CashVoucher v) {
        if (v.requestKey() != null && keysKept()) {
            Posted earlier = earlier(v.requestKey());
            if (earlier != null) return earlier;
        }
        int branch = v.branch(), module = v.module(), code = v.code(), clientId = v.clientId();
        CashVoucher.Teller p = v.teller();
        boolean in = v.receipt();
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        // dated on the branch's open business day (refused while none is open); the time stamps stay the real moment
        LocalDate day = businessDay.postingDate(branch);
        int year = day.getYear();

        PostingPeriods.requireOpen(jdbc, branch, day);
        long ctl = db.nextControlNumber(branch, module, code, year);
        if (!v.confirmDuplicate()) {
            Integer same = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? AND TR_CTLNO > ?
                      AND TR_CLIENTID = ? AND DATETIMEADDED >= ?
                    """, Integer.class, branch, year, module, code, ctl - 500, clientId, Timestamp.valueOf(now.minusSeconds(DUPLICATE_SECONDS)));
            if (same != null && same > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + (in ? "receipt" : "withdrawal") + " for this client was posted in the last minute. If you really mean to post another, confirm it.");
            }
        }
        String docNo = LedgerRules.receiptDocumentNo(v.receiptNo(), ctl);
        String explanation = v.explanation() == null ? "" : v.explanation().trim();
        explanation = explanation.substring(0, Math.min(explanation.length(), 200));

        BigDecimal memberTotal = v.lines().stream().map((l) -> l.amount().value()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal received = memberTotal.negate();
        BigDecimal chequeTotal = v.cheques().stream().map((c) -> c.amount().value()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!v.cheques().isEmpty()) {
            if (!in) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cheques can be received here, not paid out.");
            if (!db.canInsertInto("transck")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cheques cannot be taken yet: the database user needs V024_cheque_receipts.sql.");
            if (p.event() != 52 && p.event() != 53) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a cashier or a teller takes cheques at the window.");
            if (chequeTotal.compareTo(received) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The cheques add up to " + chequeTotal.toPlainString() + ", more than the payment of " + received.toPlainString() + ".");
        }
        BigDecimal cashAmount = received.subtract(chequeTotal);
        // double entry: the member lines, the cheques and the cash together come to zero (the cash is what the lines leave over)
        if (memberTotal.add(chequeTotal).add(cashAmount).signum() != 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The voucher does not balance.");

        jdbc.update("""
                INSERT INTO transsum (TR_BRCODE, TR_YEAR, TR_MODULE, TR_CODE, TR_CTLNO, TR_DOCNO, TR_DATE, TR_TIME, TR_CLIENTID, BATCH_NO, EXPLANATION, IS_FO, DATETIMEADDED)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """, branch, year, module, code, ctl, docNo, day.toString(), now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), clientId,
            day.format(DateTimeFormatter.BASIC_ISO_DATE), explanation, Timestamp.valueOf(now));

        String cashSql = """
                INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                  DTSLE_CODE, DTCTS_NUM, DTCOCI_TYPE, DTAMT, DTENCDBY, DTPOSTBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 11, 1, 1, ?, ?, 1, ?, ?, ?, 1, 0, 0, 0)
                """;
        String memberSql = """
                INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, ClientIDDT, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                  DTSLE_CODE, DTCTS_NUM, DTSL_DATE, DTADJ_FLAG, DTCOCI_TYPE, DTAMT, DTENCDBY, DTPOSTBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 1, 0, 0, 0)
                """;
        String chequeSql = """
                INSERT INTO transdt (DTTR_BRCODE, DTTR_YEAR, DTTR_MODULE, DTTR_CODE, DTTR_CTLNO, DTSEQ_NO, DTDEPT, DTACCT_CODE, DTSLC_CODE, DTSLT_CODE, DTREF_NO,
                  DTSLE_CODE, DTCTS_NUM, DTCOCI_TYPE, DTAMT, DTENCDBY, DTPOSTBY, DTUPD_TAG, DTCHECKISRELEASE, DTISTRANSIT, DTISUNDENTIFIED)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 11, 3, 1, ?, ?, 2, ?, ?, ?, 1, 0, 0, 0)
                """;
        int memberTender = v.cheques().isEmpty() ? 1 : 2;
        boolean cashLine = cashAmount.signum() > 0 || v.cheques().isEmpty();
        int seq = 0;
        if (in) {
            for (CashVoucher.Cheque c : v.cheques()) jdbc.update(chequeSql, branch, year, module, code, ctl, seq++, p.dept(), p.chequeAccount(), p.event(), p.drawerNumber(), c.amount().value(), p.id(), p.id());
            if (cashLine) jdbc.update(cashSql, branch, year, module, code, ctl, seq++, p.dept(), p.cashAccount(), p.event(), p.drawerNumber(), cashAmount, p.id(), p.id());
        }
        for (CashVoucher.MemberLine l : v.lines()) {
            // a line with class 0 is a plain general ledger line (a fee into an income account): the same entry with no sub-ledger fields
            boolean plain = l.slc() == 0;
            jdbc.update(memberSql, branch, year, module, code, ctl, seq++, p.dept(), l.account(), clientId, plain ? null : l.slc(), plain ? null : l.typeCode(), plain ? null : l.refNo(),
                plain ? null : l.event(), plain ? null : l.cts(), day.toString(), memberTender, l.amount().value(), p.id(), p.id());
        }
        if (!in) jdbc.update(cashSql, branch, year, module, code, ctl, seq, p.dept(), p.cashAccount(), p.event(), p.drawerNumber(), cashAmount, p.id(), p.id());

        List<Object[]> glCredits = new ArrayList<>();
        if (v.cheques().isEmpty()) {
            for (CashVoucher.MemberLine l : v.lines()) {
                memberLedgerRow(branch, clientId, l, day, code, ctl, docNo, null, 1);
                glCredits.add(new Object[] {l.account(), l.amount().value()});
            }
        } else {
            BigDecimal cashLeft = cashAmount;
            int ci = 0;
            BigDecimal chequeLeft = v.cheques().get(0).amount().value();
            for (CashVoucher.MemberLine l : v.lines()) {
                BigDecimal need = l.amount().value().negate();
                BigDecimal take = cashLeft.min(need);
                if (take.signum() > 0) {
                    memberLedgerRow(branch, clientId, l.withAmount(Money.of(take.negate())), day, code, ctl, docNo, null, 1);
                    glCredits.add(new Object[] {l.account(), take.negate()});
                    cashLeft = cashLeft.subtract(take);
                    need = need.subtract(take);
                }
                while (need.signum() > 0) {
                    take = chequeLeft.min(need);
                    memberLedgerRow(branch, clientId, l.withAmount(Money.of(take.negate())), day, code, ctl, docNo, v.cheques().get(ci), 2);
                    glCredits.add(new Object[] {l.account(), take.negate()});
                    chequeLeft = chequeLeft.subtract(take);
                    need = need.subtract(take);
                    if (chequeLeft.signum() == 0 && ci + 1 < v.cheques().size()) chequeLeft = v.cheques().get(++ci).amount().value();
                }
            }
        }
        if (in && db.canInsertInto("transck")) {
            int k = 0;
            if (cashLine) tender(branch, year, module, code, ctl, k++, p, cashAmount, null);
            for (CashVoucher.Cheque c : v.cheques()) tender(branch, year, module, code, ctl, k++, p, c.amount().value(), c);
        }

        Timestamp stamp = Timestamp.valueOf(now);
        for (CashVoucher.Cheque c : v.cheques()) glLine(branch, p.chequeAccount(), day, code, p.dept(), c.amount().value(), stamp);
        if (cashLine) glLine(branch, p.cashAccount(), day, code, p.dept(), cashAmount, stamp);
        for (Object[] g : glCredits) glLine(branch, (String) g[0], day, code, p.dept(), (BigDecimal) g[1], stamp);

        if (v.requestKey() != null && keysKept()) remember(v.requestKey(), v.username(), branch, year, module, code, ctl, docNo, day, now);
        AuditLog.write(jdbc, v.username(), v.auditAction(), "transsum", branch + "/" + year + "/" + module + "/" + code + "/" + ctl, null, "client " + clientId + " " + v.auditDetail() + " doc " + docNo, null);
        return new Posted(branch, year, module, code, ctl, docNo, day, false);
    }

    /**
     * One member ledger row (sldtl), with its slsum row if this is the first entry of the event; the sequence runs on over all events of the
     * account. A cheque (null for cash) sets the days left to clear, the cheque number and the bank; {@code cociType} is the tender (1 cash,
     * 2 cheque, null for a General Journal row). A line of class 0 (a plain general-ledger line) has no member ledger row.
     */
    public void memberLedgerRow(int branch, int clientId, CashVoucher.MemberLine l, LocalDate day, int trCode, long ctl, String docNo, CashVoucher.Cheque cheque, Integer cociType) {
        if (l.slc() == 0) return;
        Integer known = jdbc.queryForObject("SELECT COUNT(*) FROM slsum WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = ?",
            Integer.class, branch, clientId, l.slc(), l.typeCode(), l.refNo(), l.event());
        if (known == null || known == 0) {
            jdbc.update("INSERT INTO slsum (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE) VALUES (?, ?, ?, ?, ?, ?, ?)",
                branch, clientId, l.slc(), l.typeCode(), l.refNo(), l.event(), day.toString());
        }
        List<Integer> top = jdbc.queryForList("""
                SELECT SEQNO FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ?
                ORDER BY SEQNO DESC LIMIT 1 FOR UPDATE
                """, Integer.class, branch, clientId, l.slc(), l.typeCode(), l.refNo());
        int next = (top.isEmpty() ? 0 : top.get(0)) + 1;
        jdbc.update("""
                INSERT INTO sldtl (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE, SEQNO, GL_DATE, TR_CODE, TR_CTLNO, TR_DOCNO, AMT, ADJ_FLAG, COCI_TYPE, CLRG_DAYS, CHECKNO, BANK_CODE)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                """, branch, clientId, l.slc(), l.typeCode(), l.refNo(), l.event(), day.toString(), next, day.toString(), trCode, ctl, docNo, l.amount().value(),
            cociType, cheque == null ? 0 : cheque.ledgerDays(), cheque == null ? null : cheque.number(), cheque == null ? null : cheque.bankCode());
    }

    /** One general-ledger line: a debit goes in the DR column, a credit (negative) in the CR column. */
    public void glLine(int branch, String account, LocalDate day, int trCode, int dept, BigDecimal amount, Timestamp stamp) {
        jdbc.update("""
                INSERT INTO gltrans (GLTransBR_CODE, GLTransACCT_CODE, GLTransTR_DATE, GLTransTR_CODE, GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT,
                  GLTransDR_BALB4, GLTransCR_BALB4, GLTransSUM_BALB4, GLTransSYNC, GLTransLASTUPDATED)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, ?)
                """, branch, account, day.toString(), trCode, dept, amount.signum() > 0 ? amount : BigDecimal.ZERO, amount.signum() < 0 ? amount : BigDecimal.ZERO, stamp);
    }

    /** One tender row (transck): the cash taken (cheque null) or one cheque. Not cleared yet; the poster is both encoder and poster, nobody has approved. */
    private void tender(int branch, int year, int module, int code, long ctl, int seq, CashVoucher.Teller p, BigDecimal amount, CashVoucher.Cheque c) {
        jdbc.update("""
                INSERT INTO transck (CKTR_BRCODE, CKTR_YEAR, CKTR_MODULE, CKTR_CODE, CKTR_CTLNO, CKSEQ_NO, CKDEPT, CKCOCI_TYPE, CKCHECKTYPE, CKCLRG_DAYS, CKCHECKNO, CKBANK_CODE,
                  CKCHECKDATE, CKAMT, CKUPD_TAG, CKENCDBY, CKPOSTBY, CKISCLEARED, CKPAYEE)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 0, '')
                """, branch, year, module, code, ctl, seq, p.dept(), c == null ? 1 : 2, c == null ? null : c.kind(), c == null ? null : c.clearingDays(), c == null ? "" : c.number(),
            c == null ? null : c.bankCode(), c == null ? null : c.date().toString(), amount, p.id(), p.id());
    }

    private Posted earlier(String key) {
        List<Posted> e = jdbc.query("SELECT branch_code, tr_year, tr_module, tr_code, tr_ctlno, doc_no, tr_date FROM ecis_posting_request WHERE request_key = ?",
            (rs, i) -> new Posted(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5), rs.getString(6), rs.getDate(7).toLocalDate(), true), key);
        return e.isEmpty() ? null : e.get(0);
    }

    private void remember(String key, String username, int branch, int year, int module, int code, long ctl, String docNo, LocalDate day, LocalDateTime now) {
        jdbc.update("INSERT INTO ecis_posting_request (request_key, username, branch_code, tr_year, tr_module, tr_code, tr_ctlno, doc_no, tr_date, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            key, username, branch, year, module, code, ctl, docNo, Date.valueOf(day), Timestamp.valueOf(now));
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
