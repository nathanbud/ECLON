package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Writes a cash payment into, or a cash withdrawal out of, a member's subsidiary ledger account exactly as the old system did
 * (see documentation/share-payment-posting-map.md and savings-posting-map.md): a transsum header, two transdt entries (the
 * cash on the poster's own drawer and the member's account), one sldtl entry and two gltrans lines. It never touches the
 * account master (sc, sd), slsum, holds or the cash counts. The callers check the rules first; every method must run inside the
 * caller's transaction.
 */
@Component
class MemberLedgerPoster {

    /** The account master table of a class: share capital (31) in sc, savings (22) in sd. */
    record Master(String table, String prefix, int slc) {
        static final Master SHARE_CAPITAL = new Master("sc", "SC", 31);
        static final Master SAVINGS = new Master("sd", "SD", 22);
    }

    /** IN: cash comes in and the member's account is credited (Cash Receipt, module 3, type 10). OUT: cash goes out and it is debited (Deposit Withdrawal, module 2, type 23). */
    enum Direction {
        IN(3, 10), OUT(2, 23);
        final int module;
        final int code;
        Direction(int module, int code) { this.module = module; this.code = code; }
    }

    record Person(int id, int branch, int number, boolean head, String assignment, int dept, String name, BigDecimal disbursementLimit, BigDecimal maxWithdrawal) {}

    record Account(int status, BigDecimal minBalance, BigDecimal maxBalance, String type, String refText, BigDecimal balance, BigDecimal held) {}

    record Client(int status, String name) {}

    record Entry(Master master, Direction direction, int branch, int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate) {}

    record Posted(int branch, int year, long controlNo, String documentNo, LocalDate date) {}

    private static final int PRINCIPAL = 11;
    private static final int DUPLICATE_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final java.time.Clock clock;

    MemberLedgerPoster(JdbcTemplate jdbc, Db db, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
    }

    // ---- loading ------------------------------------------------------------------------------------------------

    Person person(String username) {
        List<Person> rows = jdbc.query("""
                SELECT AccessID, AccessBR_CODE, COALESCE(AccessTellerNumber, 0), COALESCE(AccessIsHeadCashier, 0), COALESCE(AccessCurrAssign, ''),
                  COALESCE(AccessDepartment, 0), CONCAT_WS(' ', AccessFName, AccessLName), COALESCE(AccessDisbursementLimit, 0), COALESCE(AccessMaxWithdrawal, 0)
                FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1
                """, (rs, i) -> new Person(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4) == 1, rs.getString(5), rs.getInt(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9)),
            username);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not a cashier or teller.");
        return rows.get(0);
    }

    Account account(Master m, int branch, int clientId, int typeCode, int refNo) {
        String p = m.prefix();
        String maxCol = m.slc() == 31 ? "s.SCMAX_BAL" : "NULL";
        String holds = m.slc() == 22
            ? "(SELECT COALESCE(SUM(h.HHELD_AMT), 0) FROM hold h WHERE h.HBR_CODE = s." + p + "BR_CODE AND h.ClientID" + "H = s.ClientID" + p + " AND h.HSLC_CODE = s." + p + "SLC_CODE AND h.HSLT_CODE = s." + p + "SLT_CODE AND h.HREF_NO = s." + p + "REF_NO)"
            : "0";
        List<Account> found = jdbc.query("SELECT s." + p + "STATUS, s." + p + "MIN_BAL, " + maxCol + ", t.SLTypeM_DESC, s." + p + "REF_NOX, "
            + "(SELECT COALESCE(-SUM(d.AMT), 0) FROM sldtl d WHERE d.SL_BRCODE = s." + p + "BR_CODE AND d.SL_CLIENTID = s.ClientID" + p + " AND d.SLC_CODE = s." + p + "SLC_CODE"
            + " AND d.SLT_CODE = s." + p + "SLT_CODE AND d.REF_NO = s." + p + "REF_NO AND d.SLE_CODE = 11), " + holds
            + " FROM " + m.table() + " s LEFT JOIN sltype t ON t.SLTypeBR_CODE = s." + p + "BR_CODE AND t.SLTypeSLC_CODE = s." + p + "SLC_CODE AND t.SLTypeSLT_CODE = s." + p + "SLT_CODE"
            + " WHERE s." + p + "BR_CODE = ? AND s.ClientID" + p + " = ? AND s." + p + "SLC_CODE = ? AND s." + p + "SLT_CODE = ? AND s." + p + "REF_NO = ?",
            (rs, i) -> new Account(rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7)),
            branch, clientId, m.slc(), typeCode, refNo);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That client has no such " + (m.slc() == 31 ? "share capital" : "savings") + " account.");
        return found.get(0);
    }

    Client client(int branch, int clientId) {
        List<Client> rows = jdbc.query("SELECT AccountStatus, COALESCE(NULLIF(AccountName, ''), CONCAT_WS(', ', LName, FName)) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?",
            (rs, i) -> new Client(rs.getInt(1), rs.getString(2)), branch, clientId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client.");
        return rows.get(0);
    }

    // ---- posting ------------------------------------------------------------------------------------------------

    /** The general ledger account the old system keeps for a class, type, event and status (glcontrol). */
    String glAccount(int branch, int slc, int typeCode, int event, int cts) {
        List<String> gl = jdbc.queryForList("""
                SELECT GLControlACCT_CODE FROM glcontrol WHERE GLControlBR_CODE = ? AND GLControlSLC_CODE = ? AND GLControlSLT_CODE = ? AND GLControlSLE_CODE = ? AND GLControlCTS_NUM = ?
                """, String.class, branch, slc, typeCode, event, cts);
        if (gl.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "The ledger has no account set up for this type of account.");
        return gl.get(0);
    }

    /** One line on a member's account: debit positive, credit negative, as the ledger stores it. */
    record MemberLine(int slc, int typeCode, int refNo, int event, int cts, String account, BigDecimal amount) {}

    /** A cash payment into, or out of, one member account (share capital or savings): a single member line. */
    Posted post(Entry e, Person p, Account a, String username) {
        boolean in = e.direction() == Direction.IN;
        String memberAccount = glAccount(p.branch(), e.master().slc(), e.typeCode(), PRINCIPAL, a.status());
        String explanation = e.explanation() == null || e.explanation().isBlank() ? (e.master().slc() == 31 ? "SC" : in ? "SD DEP" : "SD WTH") : e.explanation();
        String what = e.master().slc() == 31 ? "share.payment.post" : in ? "savings.deposit.post" : "savings.withdrawal.post";
        MemberLine line = new MemberLine(e.master().slc(), e.typeCode(), e.refNo(), PRINCIPAL, a.status(), memberAccount, in ? e.amount().negate() : e.amount());
        return postVoucher(e.direction(), p, e.clientId(), explanation, e.receiptNo(), e.confirmDuplicate(), List.of(line), what, username,
            "type " + e.typeCode() + " ref " + e.refNo() + " amount " + e.amount().toPlainString());
    }

    /**
     * Writes one cash transaction: the transsum header, the cash entry on the poster's own drawer, one entry per member line, the
     * member ledger rows and the general ledger lines. The cash entry is the opposite of the member lines together, so the voucher
     * balances. A receipt writes the cash entry first and the member entries after it; a withdrawal the other way round.
     */
    Posted postVoucher(Direction direction, Person p, int clientId, String explanationText, String receiptNo, boolean confirmDuplicate, List<MemberLine> lines,
                       String auditAction, String username, String auditDetail) {
        int branch = p.branch();
        List<Integer> events = HandoverController.eventsOf(p.head(), p.assignment());
        if (p.number() <= 0 || events.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a cashier or teller with a drawer can take or pay out cash.");
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so this cannot be posted.");
        int event = events.get(0);
        String cashAccount = HandoverRules.account(event);

        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDate day = now.toLocalDate();
        int year = day.getYear();
        int module = direction.module, code = direction.code;
        boolean in = direction == Direction.IN;

        long ctl = db.nextControlNumber(branch, module, code, year);
        if (!confirmDuplicate) {
            Integer same = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = ? AND TR_CODE = ? AND TR_CTLNO > ?
                      AND TR_CLIENTID = ? AND DATETIMEADDED >= ?
                    """, Integer.class, branch, year, module, code, ctl - 500, clientId, Timestamp.valueOf(now.minusSeconds(DUPLICATE_SECONDS)));
            if (same != null && same > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + (in ? "receipt" : "withdrawal") + " for this client was posted in the last minute. If you really mean to post another, confirm it.");
            }
        }
        String docNo;
        try {
            docNo = SharePaymentRules.documentNo(receiptNo, ctl);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        String explanation = explanationText == null ? "" : explanationText.trim();
        explanation = explanation.substring(0, Math.min(explanation.length(), 200));

        BigDecimal memberTotal = lines.stream().map(MemberLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashAmount = memberTotal.negate();

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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?, 1, 0, 0, 0)
                """;
        int seq = 0;
        if (in) jdbc.update(cashSql, branch, year, module, code, ctl, seq++, p.dept(), cashAccount, event, p.number(), cashAmount, p.id(), p.id());
        for (MemberLine l : lines) {
            jdbc.update(memberSql, branch, year, module, code, ctl, seq++, p.dept(), l.account(), clientId, l.slc(), l.typeCode(), l.refNo(), l.event(), l.cts(), day.toString(), l.amount(), p.id(), p.id());
        }
        if (!in) jdbc.update(cashSql, branch, year, module, code, ctl, seq, p.dept(), cashAccount, event, p.number(), cashAmount, p.id(), p.id());

        // the member's ledger rows; the sequence runs on over all dates and all events of the account
        for (MemberLine l : lines) writeLedgerRow(branch, clientId, l, day, code, ctl, docNo);

        Timestamp stamp = Timestamp.valueOf(now);
        // debit positive in DR, credit negative in CR
        writeGlLine(branch, cashAccount, day, code, p.dept(), cashAmount, stamp);
        for (MemberLine l : lines) writeGlLine(branch, l.account(), day, code, p.dept(), l.amount(), stamp);

        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, 'transsum', ?, ?)",
            stamp, username, auditAction, branch + "/" + year + "/" + module + "/" + code + "/" + ctl, "client " + clientId + " " + auditDetail + " doc " + docNo);
        return new Posted(branch, year, ctl, docNo, day);
    }

    /** One sldtl row on a member account, with its slsum row if this is the first entry of the event; the sequence runs on over all events. */
    void writeLedgerRow(int branch, int clientId, MemberLine l, LocalDate day, int trCode, long ctl, String docNo) {
        // the ledger needs a slsum row for each account and event, dated by its first entry; the first use of an event adds it
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
                INSERT INTO sldtl (SL_BRCODE, SL_CLIENTID, SLC_CODE, SLT_CODE, REF_NO, SLE_CODE, TR_DATE, SEQNO, GL_DATE, TR_CODE, TR_CTLNO, TR_DOCNO, AMT, ADJ_FLAG, COCI_TYPE, CLRG_DAYS)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0)
                """, branch, clientId, l.slc(), l.typeCode(), l.refNo(), l.event(), day.toString(), next, day.toString(), trCode, ctl, docNo, l.amount());
    }

    /** One gltrans line: a debit goes in the DR column, a credit (negative) in the CR column. */
    void writeGlLine(int branch, String account, LocalDate day, int trCode, int dept, BigDecimal amount, Timestamp stamp) {
        jdbc.update("""
                INSERT INTO gltrans (GLTransBR_CODE, GLTransACCT_CODE, GLTransTR_DATE, GLTransTR_CODE, GLTransDEPT, GLTransDR_AMT, GLTransCR_AMT,
                  GLTransDR_BALB4, GLTransCR_BALB4, GLTransSUM_BALB4, GLTransSYNC, GLTransLASTUPDATED)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, ?)
                """, branch, account, day.toString(), trCode, dept, amount.signum() > 0 ? amount : BigDecimal.ZERO, amount.signum() < 0 ? amount : BigDecimal.ZERO, stamp);
    }
}
