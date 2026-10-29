package ph.coop.core.frontoffice;


import ph.coop.core.audit.AuditLog;import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Clearing of cheques received at the window (Cash Receipts, type 10). The old system keeps the state in transck.CKISCLEARED (0 or 1;
 * CKCLR_DATE is never filled) and logged only a few manual clearings as "COCI Clearing". The real clearing days follow the cheque kind
 * (chktype) plus two, as in sldtl.CLRG_DAYS. Here the head cashier, an accountant or an administrator marks cheques cleared, with a
 * reason when the cheque is cleared before its date; the only legacy column changed is CKISCLEARED (V026) and every cheque gets an
 * audit row. Documented in documentation/cheque-receipt-posting-map.md.
 */
@RestController
@RequestMapping("/api/front-office/cheque-clearing")
class ChequeClearingController {

    record Row(int year, long ctlNo, int seq, String documentNo, String receiptDate, String client, String number, String bank, String chequeDate,
               String kind, BigDecimal amount, String clearsOn, boolean due, boolean cleared) {}

    record Item(int year, long ctlNo, int seq) {}

    record ClearRequest(List<Item> items, String reason) {}

    record Cleared(int cleared, int skipped) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final TransactionTemplate tx;

    ChequeClearingController(JdbcTemplate jdbc, Db db, Clock clock, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.tx = new TransactionTemplate(txm);
    }

    private static boolean role(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_" + role));
    }

    private boolean mayClear(Authentication auth) {
        if (role(auth, "ADMIN") || role(auth, "ACCOUNTANT")) return true;
        Integer head = jdbc.query("SELECT COALESCE(AccessIsHeadCashier, 0) FROM access WHERE AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0 LIMIT 1",
            (rs, i) -> rs.getInt(1), auth.getName()).stream().findFirst().orElse(0);
        return head == 1;
    }

    /** The day a cheque is expected to clear: the receipt date plus the kind's days plus 2 (the old ledger countdown); none for kinds with no wait. */
    static LocalDate clearsOn(LocalDate received, int kindDays) {
        return received.plusDays(ChequeRules.ledgerDays(kindDays));
    }

    /** Cheques taken on receipts this year and last that are not cleared yet (or, with all=true, the cleared ones too), oldest first. */
    @GetMapping
    List<Row> list(Authentication auth, @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean all) {
        if (!mayClear(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier, an accountant or an administrator sees cheque clearing.");
        LocalDate today = LocalDate.now(clock);
        return jdbc.query("""
                SELECT c.CKTR_YEAR, c.CKTR_CTLNO, c.CKSEQ_NO, t.TR_DOCNO, t.TR_DATE, t.CLIENTNAME, c.CKCHECKNO, COALESCE(NULLIF(b.BCDesc2, ''), b.BCDesc),
                  c.CKCHECKDATE, k.ChkTypeDesc, c.CKAMT, COALESCE(c.CKCLRG_DAYS, 0), c.CKISCLEARED
                FROM transck c
                JOIN transsum t ON t.TR_BRCODE = c.CKTR_BRCODE AND t.TR_YEAR = c.CKTR_YEAR AND t.TR_MODULE = c.CKTR_MODULE AND t.TR_CODE = c.CKTR_CODE AND t.TR_CTLNO = c.CKTR_CTLNO
                LEFT JOIN bankcode b ON b.BCBR_CODE = c.CKTR_BRCODE AND b.BCID = c.CKBANK_CODE
                LEFT JOIN chktype k ON k.ChkTypeID = c.CKCHECKTYPE
                WHERE c.CKTR_YEAR >= ? AND c.CKTR_CODE = 10 AND c.CKCOCI_TYPE = 2 AND (? = 1 OR COALESCE(c.CKISCLEARED, 0) = 0)
                ORDER BY t.TR_DATE, c.CKTR_CTLNO, c.CKSEQ_NO
                """, (rs, i) -> {
                LocalDate received = rs.getDate(5).toLocalDate();
                LocalDate on = clearsOn(received, rs.getInt(12));
                java.sql.Date cd = rs.getDate(9);
                return new Row(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getString(4), received.toString(), rs.getString(6), rs.getString(7), rs.getString(8),
                    cd == null ? null : cd.toLocalDate().toString(), rs.getString(10), rs.getBigDecimal(11), on.toString(), !today.isBefore(on), rs.getInt(13) == 1);
            }, today.getYear() - 1, all ? 1 : 0);
    }

    /** Marks the chosen cheques cleared. Cheques not yet due need a reason; cheques already cleared are skipped. */
    @PostMapping
    Cleared clear(@RequestBody ClearRequest req, Authentication auth) {
        if (!mayClear(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the head cashier, an accountant or an administrator clears cheques.");
        if (req.items() == null || req.items().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the cheques to clear.");
        if (!db.canUpdateColumn("transck", "CKISCLEARED"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Clearing is not set up yet. An administrator has to run database/migrations/V026_cheque_clearing.sql.");
        String reason = req.reason() == null ? "" : req.reason().trim();
        List<Row> open = list(auth, false);
        List<Row> chosen = new ArrayList<>();
        for (Item it : req.items())
            open.stream().filter((r) -> r.year() == it.year() && r.ctlNo() == it.ctlNo() && r.seq() == it.seq()).findFirst().ifPresent(chosen::add);
        if (chosen.isEmpty()) return new Cleared(0, req.items().size());
        boolean early = chosen.stream().anyMatch((r) -> !r.due());
        if (early && reason.length() < 5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Some of these cheques are not yet due. Give a reason (at least 5 characters) for clearing them early.");
        String why = reason.isEmpty() ? null : reason.substring(0, Math.min(reason.length(), 250));
        int done = tx.execute((st) -> {
            int n = 0;
            for (Row r : chosen) {
                int u = jdbc.update("UPDATE transck SET CKISCLEARED = 1 WHERE CKTR_YEAR = ? AND CKTR_CODE = 10 AND CKTR_CTLNO = ? AND CKSEQ_NO = ? AND CKCOCI_TYPE = 2 AND COALESCE(CKISCLEARED, 0) = 0",
                    r.year(), r.ctlNo(), r.seq());
                if (u == 0) continue;
                n++;
                AuditLog.write(jdbc, null, auth.getName(), "cheque.clear", "transck", r.year() + "/10/" + r.ctlNo() + "/" + r.seq(), "not cleared", "cleared: cheque " + r.number() + " " + r.amount().toPlainString() + " (" + r.documentNo() + ")" + (r.due() ? "" : " before " + r.clearsOn()), why);
            }
            return n;
        });
        return new Cleared(done, req.items().size() - done);
    }
}
