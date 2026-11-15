package ph.coop.core.frontoffice;


import ph.coop.core.accounting.close.PostingPeriods;
import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
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
import ph.coop.core.frontoffice.ChequeRules.Cheque;
import ph.coop.core.accounting.ledger.CashVoucher;
import ph.coop.core.accounting.ledger.Ledger;
import ph.coop.core.accounting.ledger.Money;
import ph.coop.core.accounting.ledger.RequestKeys;

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

    /**
     * IN: cash comes in and the member's account is credited (Cash Receipt, module 3, type 10). OUT: cash goes out and it is debited (Deposit Withdrawal,
     * module 2, type 23). PAYABLE_OUT: the same shape, paying a vouchers-payable balance out (Cash Voucher payment, module 2, type 24).
     */
    enum Direction {
        IN(3, 10), OUT(2, 23), PAYABLE_OUT(2, 24);
        final int module;
        final int code;
        Direction(int module, int code) { this.module = module; this.code = code; }
    }

    record Person(int id, int branch, int number, boolean head, String assignment, int dept, String name, BigDecimal disbursementLimit, BigDecimal maxWithdrawal) {}

    record Account(int status, BigDecimal minBalance, BigDecimal maxBalance, String type, String refText, BigDecimal balance, BigDecimal held) {}

    record Client(int status, String name) {}

    /** cheques is the part of a receipt paid by cheque (the rest is cash); empty for cash only and for every withdrawal. */
    record Entry(Master master, Direction direction, int branch, int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate,
                 List<Cheque> cheques) {
        Entry(Master master, Direction direction, int branch, int clientId, int typeCode, int refNo, BigDecimal amount, String explanation, String receiptNo, boolean confirmDuplicate) {
            this(master, direction, branch, clientId, typeCode, refNo, amount, explanation, receiptNo, confirmDuplicate, List.of());
        }
    }

    record Posted(int branch, int year, long controlNo, String documentNo, LocalDate date) {}

    private static final int PRINCIPAL = 11;

    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;
    private final Ledger ledger;

    MemberLedgerPoster(JdbcTemplate jdbc, java.time.Clock clock, Ledger ledger) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ledger = ledger;
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
        // share capital carries its own limit on the account; a savings account takes the limit of its type (Settings > Account Types)
        String maxCol = m.slc() == 31 ? "s.SCMAX_BAL" : m.slc() == 22 ? "t.SLTypeMAXBAL" : "NULL";
        // savings and share capital both carry holds (a co-maker's agreed amount sits on share capital)
        String uncleared = m.slc() == 22 ? " + " + ChequeRules.unclearedSql("s") : "";
        String holds = "((SELECT COALESCE(SUM(h.HHELD_AMT), 0) FROM hold h WHERE h.HBR_CODE = s." + p + "BR_CODE AND h.ClientID" + "H = s.ClientID" + p + " AND h.HSLC_CODE = s." + p + "SLC_CODE AND h.HSLT_CODE = s." + p + "SLT_CODE AND h.HREF_NO = s." + p + "REF_NO)" + uncleared + ")";
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
    record MemberLine(int slc, int typeCode, int refNo, int event, int cts, String account, BigDecimal amount) {
        MemberLine withAmount(BigDecimal other) { return new MemberLine(slc, typeCode, refNo, event, cts, account, other); }
    }

    /** A cash payment into, or out of, one member account (share capital or savings): a single member line. */
    Posted post(Entry e, Person p, Account a, String username) {
        boolean in = e.direction() == Direction.IN;
        String memberAccount = glAccount(p.branch(), e.master().slc(), e.typeCode(), PRINCIPAL, a.status());
        String explanation = e.explanation() == null || e.explanation().isBlank() ? (e.master().slc() == 31 ? "SC" : in ? "SD DEP" : "SD WTH") : e.explanation();
        String what = e.master().slc() == 31 ? "share.payment.post" : in ? "savings.deposit.post" : "savings.withdrawal.post";
        MemberLine line = new MemberLine(e.master().slc(), e.typeCode(), e.refNo(), PRINCIPAL, a.status(), memberAccount, in ? e.amount().negate() : e.amount());
        return postVoucher(e.direction(), p, e.clientId(), explanation, e.receiptNo(), e.confirmDuplicate(), List.of(line), what, username,
            "type " + e.typeCode() + " ref " + e.refNo() + " amount " + e.amount().toPlainString() + (e.cheques().isEmpty() ? "" : " cheques " + describe(e.cheques())), e.cheques());
    }

    /**
     * Writes one cash transaction: the transsum header, the cash entry on the poster's own drawer, one entry per member line, the
     * member ledger rows and the general ledger lines. The cash entry is the opposite of the member lines together, so the voucher
     * balances. A receipt writes the cash entry first and the member entries after it; a withdrawal the other way round.
     */
    Posted postVoucher(Direction direction, Person p, int clientId, String explanationText, String receiptNo, boolean confirmDuplicate, List<MemberLine> lines,
                       String auditAction, String username, String auditDetail) {
        return postVoucher(direction, p, clientId, explanationText, receiptNo, confirmDuplicate, lines, auditAction, username, auditDetail, List.of());
    }

    /**
     * As above, for a receipt paid partly or wholly by cheque (documentation/cheque-receipt-posting-map.md): each cheque is a debit on the
     * cheque account of the poster's drawer (100112 cashier, 100113 teller; sub-ledger type 3, same event and drawer number as the cash),
     * written before the cash entry; the cash pays the member lines first, in order, and the cheques the rest, in order, one ledger row
     * and one general ledger credit per tender. Every receipt also gets its tender rows in transck (cash row first, then the cheques).
     */
    Posted postVoucher(Direction direction, Person p, int clientId, String explanationText, String receiptNo, boolean confirmDuplicate, List<MemberLine> lines,
                       String auditAction, String username, String auditDetail, List<Cheque> cheques) {
        int branch = p.branch();
        List<Integer> events = HandoverController.eventsOf(p.head(), p.assignment());
        if (p.number() <= 0 || events.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a cashier or teller with a drawer can take or pay out cash.");
        if (p.dept() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account has no department set, so this cannot be posted.");
        int event = events.get(0);
        try {
            SharePaymentRules.documentNo(receiptNo, 1); // the receipt number's form is a window rule
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        // The ledger writes the voucher (documentation/design/posting-engine.md); the screen's request key keeps a second press from posting again.
        CashVoucher.Teller teller = new CashVoucher.Teller(p.id(), p.dept(), p.number(), event, HandoverRules.account(event), event == 52 ? "100112" : "100113");
        Ledger.Posted v = ledger.writeCash(new CashVoucher(RequestKeys.current(auditAction, username), username, branch, direction.module, direction.code,
            direction == Direction.IN, clientId, explanationText, receiptNo, confirmDuplicate, teller, lines.stream().map(MemberLedgerPoster::toLedger).toList(),
            cheques.stream().map(MemberLedgerPoster::toLedger).toList(), auditAction, auditDetail));
        return new Posted(v.branch(), v.year(), v.controlNo(), v.documentNo(), v.date());
    }

    private static CashVoucher.MemberLine toLedger(MemberLine l) {
        return new CashVoucher.MemberLine(l.slc(), l.typeCode(), l.refNo(), l.event(), l.cts(), l.account(), Money.of(l.amount()));
    }

    private static CashVoucher.Cheque toLedger(Cheque c) {
        return new CashVoucher.Cheque(c.number(), c.bankCode(), c.date(), c.kind(), Money.of(c.amount()), c.clearingDays(), ChequeRules.ledgerDays(c.clearingDays()));
    }

    /** One sldtl row on a member account, with its slsum row if this is the first entry of the event; the sequence runs on over all events. */
    void writeLedgerRow(int branch, int clientId, MemberLine l, LocalDate day, int trCode, long ctl, String docNo) {
        writeLedgerRow(branch, clientId, l, day, trCode, ctl, docNo, null);
    }

    /** As above; a cheque (null for cash) sets the row's tender, the days left to clear, the cheque number and the bank. */
    void writeLedgerRow(int branch, int clientId, MemberLine l, LocalDate day, int trCode, long ctl, String docNo, Cheque cheque) {
        writeLedgerRow(branch, clientId, l, day, trCode, ctl, docNo, cheque, cheque == null ? 1 : 2);
    }

    /** As above with the tender type set by the caller; a General Journal row has none (null), as in the old data. Written by the ledger. */
    void writeLedgerRow(int branch, int clientId, MemberLine l, LocalDate day, int trCode, long ctl, String docNo, Cheque cheque, Integer cociType) {
        ledger.memberLedgerRow(branch, clientId, toLedger(l), day, trCode, ctl, docNo, cheque == null ? null : toLedger(cheque), cociType);
    }

    /**
     * Checks the cheques typed at the window against the bank list and the cheque kinds, and against cheques received before (the same
     * bank and number again needs a confirmation). Throws a 400 listing every problem.
     */
    List<Cheque> cheques(List<ChequeRules.Input> typed, boolean confirmDuplicate) {
        if (typed == null || typed.isEmpty()) return List.of();
        LocalDate today = LocalDate.now(clock);
        List<String> problems = new java.util.ArrayList<>(ChequeRules.repeats(typed));
        List<Cheque> out = new java.util.ArrayList<>();
        for (int i = 0; i < typed.size(); i++) {
            ChequeRules.Input c = typed.get(i);
            Integer days = c.kind() == null ? null : jdbc.queryForList("SELECT ChkTypeDays FROM chktype WHERE ChkTypeID = ?", Integer.class, c.kind()).stream().findFirst().orElse(null);
            boolean bank = c.bankCode() != null && jdbc.queryForObject("SELECT COUNT(*) FROM bankcode WHERE BCID = ?", Integer.class, c.bankCode()) > 0;
            List<String> mine = ChequeRules.check(c, i + 1, today, days, bank);
            problems.addAll(mine);
            if (mine.isEmpty()) {
                String number = ChequeRules.number(c.number());
                if (!confirmDuplicate) {
                    List<java.sql.Date> before = jdbc.queryForList("""
                            SELECT m.TR_DATE FROM transck k JOIN transsum m ON m.TR_BRCODE = k.CKTR_BRCODE AND m.TR_YEAR = k.CKTR_YEAR AND m.TR_MODULE = k.CKTR_MODULE
                              AND m.TR_CODE = k.CKTR_CODE AND m.TR_CTLNO = k.CKTR_CTLNO
                            WHERE k.CKCOCI_TYPE = 2 AND k.CKBANK_CODE = ? AND k.CKCHECKNO = ? ORDER BY m.TR_DATE DESC LIMIT 1
                            """, java.sql.Date.class, c.bankCode(), number);
                    if (!before.isEmpty()) problems.add("Cheque " + (i + 1) + ": a cheque with this bank and number was already received on " + before.get(0) + ". If you really mean to take it again, confirm it.");
                }
                out.add(new Cheque(number, c.bankCode(), c.date(), c.kind(), c.amount().setScale(2, java.math.RoundingMode.HALF_UP), days));
            }
        }
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        return out;
    }

    static String describe(List<Cheque> cheques) {
        return cheques.stream().map((c) -> c.number() + " bank " + c.bankCode() + " " + c.amount().toPlainString()).collect(java.util.stream.Collectors.joining("; "));
    }

    /** One gltrans line: a debit goes in the DR column, a credit (negative) in the CR column. Written by the ledger. */
    void writeGlLine(int branch, String account, LocalDate day, int trCode, int dept, BigDecimal amount, Timestamp stamp) {
        ledger.glLine(branch, account, day, trCode, dept, amount, stamp);
    }
}
