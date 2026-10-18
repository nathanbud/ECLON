package ph.coop.core.share;

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

/**
 * Read-only share capital of a member (guide phase 7), over the legacy subsidiary ledger. Share capital is class 31 of the
 * legacy ledger: the accounts are rows of {@code sc} (one per type and reference number, with the type names in {@code sltype}),
 * and every movement is a row of {@code sldtl}. A credit (money in) is stored negative, so amounts are negated here and a
 * contribution shows as a positive figure. There is no stored balance: it is the sum of the account's ledger rows.
 * Nothing here writes; subscriptions and payments also move client balances and the legacy rules for that are not known yet.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/shares")
class ShareCapitalController {

    private static final int CLASS_SHARE_CAPITAL = 31;
    private static final int MAX_WINDOW = 1000;

    /** Status codes as they appear in sc.SCSTATUS; the legacy database has no table for them, so 15 and 17 are inferred from the data. */
    private static String statusName(int code) {
        return switch (code) {
            case 15 -> "Active";
            case 17 -> "Closed";
            default -> "Status " + code;
        };
    }

    record Account(int typeCode, String type, int refNo, String refNoText, LocalDate opened, int status, String statusName,
                   BigDecimal minimumBalance, BigDecimal balance, LocalDate lastActivity) {}

    record Summary(List<Account> accounts, BigDecimal totalBalance) {}

    record Entry(LocalDate date, LocalDate glDate, int transactionCode, String transaction, String documentNo, BigDecimal amount, BigDecimal balanceAfter) {}

    record History(Account account, List<Entry> entries, int page, int size, long totalEntries) {}

    private final JdbcTemplate jdbc;

    ShareCapitalController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String ACCOUNTS = """
            SELECT s.SCSLT_CODE, t.SLTypeM_DESC, s.SCREF_NO, s.SCREF_NOX, s.SCTR_DATE, s.SCSTATUS, s.SCMIN_BAL,
                   (SELECT COALESCE(-SUM(d.AMT), 0) FROM sldtl d
                     WHERE d.SL_BRCODE = s.SCBR_CODE AND d.SL_CLIENTID = s.ClientIDSC AND d.SLC_CODE = s.SCSLC_CODE
                       AND d.SLT_CODE = s.SCSLT_CODE AND d.REF_NO = s.SCREF_NO),
                   (SELECT MAX(d.TR_DATE) FROM sldtl d
                     WHERE d.SL_BRCODE = s.SCBR_CODE AND d.SL_CLIENTID = s.ClientIDSC AND d.SLC_CODE = s.SCSLC_CODE
                       AND d.SLT_CODE = s.SCSLT_CODE AND d.REF_NO = s.SCREF_NO)
            FROM sc s
            LEFT JOIN sltype t ON t.SLTypeBR_CODE = s.SCBR_CODE AND t.SLTypeSLC_CODE = s.SCSLC_CODE AND t.SLTypeSLT_CODE = s.SCSLT_CODE
            WHERE s.SCBR_CODE = ? AND s.ClientIDSC = ? AND s.SCSLC_CODE = ?
            """;

    private static Account map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date opened = rs.getDate(5);
        java.sql.Date last = rs.getDate(9);
        int status = rs.getInt(6);
        return new Account(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), opened == null ? null : opened.toLocalDate(), status, statusName(status),
                rs.getBigDecimal(7), rs.getBigDecimal(8), last == null ? null : last.toLocalDate());
    }

    @GetMapping
    Summary accounts(@PathVariable int branchCode, @PathVariable int clientId, @RequestParam(defaultValue = "false") boolean includeEmpty) {
        List<Account> accounts = jdbc.query(ACCOUNTS + " ORDER BY s.SCSLT_CODE, s.SCREF_NO", ShareCapitalController::map, branchCode, clientId, CLASS_SHARE_CAPITAL)
            .stream().filter((x) -> includeEmpty || x.lastActivity() != null).toList(); // the legacy system pre-creates empty accounts for every type
        BigDecimal total = accounts.stream().map(Account::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(accounts, total);
    }

    @GetMapping("/{typeCode}/{refNo}")
    History history(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo,
                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || size < 1 || (long) (page + 1) * size > MAX_WINDOW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for at most " + MAX_WINDOW + " entries from the newest.");
        }
        List<Account> found = jdbc.query(ACCOUNTS + " AND s.SCSLT_CODE = ? AND s.SCREF_NO = ?", ShareCapitalController::map,
                branchCode, clientId, CLASS_SHARE_CAPITAL, typeCode, refNo);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such share capital account.");
        Account account = found.get(0);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ?
                """, Long.class, branchCode, clientId, CLASS_SHARE_CAPITAL, typeCode, refNo);

        // Newest first. Each row's balance is the account balance minus everything newer than it.
        record Raw(LocalDate date, LocalDate gl, int code, String name, String doc, BigDecimal amount) {}
        List<Raw> window = jdbc.query("""
                SELECT d.TR_DATE, d.GL_DATE, d.TR_CODE, tt.TransTypeDesc, d.TR_DOCNO, -d.AMT
                FROM sldtl d LEFT JOIN transtype tt ON tt.TransTypeID = d.TR_CODE
                WHERE d.SL_BRCODE = ? AND d.SL_CLIENTID = ? AND d.SLC_CODE = ? AND d.SLT_CODE = ? AND d.REF_NO = ?
                ORDER BY d.TR_DATE DESC, d.SEQNO DESC LIMIT ?
                """, (rs, i) -> new Raw(rs.getDate(1).toLocalDate(), rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate(), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6)),
                branchCode, clientId, CLASS_SHARE_CAPITAL, typeCode, refNo, (page + 1) * size);
        List<Entry> entries = new ArrayList<>();
        BigDecimal running = account.balance();
        for (int i = 0; i < window.size(); i++) {
            Raw r = window.get(i);
            if (i >= page * size) entries.add(new Entry(r.date(), r.gl(), r.code(), r.name(), r.doc(), r.amount(), running));
            running = running.subtract(r.amount());
        }
        return new History(account, entries, page, size, total == null ? 0 : total);
    }
}
