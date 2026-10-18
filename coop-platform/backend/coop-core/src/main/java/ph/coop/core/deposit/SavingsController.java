package ph.coop.core.deposit;

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
 * Read-only savings deposits of a member (guide phase 8), over the legacy subsidiary ledger. Savings is class 22: the accounts
 * are rows of {@code sd} (type names in {@code sltype}), holds are rows of {@code hold}, and every movement is a row of
 * {@code sldtl} with event 11 (principal). As with share capital, a credit (money in) is stored negative, so amounts are
 * negated here and a deposit shows as a positive figure, and the balance is the sum of the account's ledger rows.
 * Deposits, withdrawals and interest are not posted here.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/savings")
class SavingsController {

    private static final int CLASS_SAVINGS = 22;
    private static final int PRINCIPAL = 11;
    private static final int MAX_WINDOW = 1000;

    /** sd.SDSTATUS as it appears in the data; there is no legacy lookup table, so 16 (Dormant) is inferred: mostly closed clients, no recent activity. */
    private static String statusName(int code) {
        return switch (code) {
            case 15 -> "Active";
            case 16 -> "Dormant";
            case 17 -> "Closed";
            default -> "Status " + code;
        };
    }

    record Account(int typeCode, String type, int refNo, String refNoText, LocalDate opened, int status, String statusName, BigDecimal interestRate,
                   BigDecimal minimumBalance, BigDecimal balance, BigDecimal held, BigDecimal available, LocalDate lastActivity) {}

    record Summary(List<Account> accounts, BigDecimal totalBalance, BigDecimal totalHeld) {}

    record Hold(String reason, String description, BigDecimal amount, LocalDate date, String reference) {}

    record Entry(LocalDate date, LocalDate glDate, int transactionCode, String transaction, String documentNo, BigDecimal amount, BigDecimal balanceAfter) {}

    record History(Account account, List<Hold> holds, List<Entry> entries, int page, int size, long totalEntries) {}

    private final JdbcTemplate jdbc;

    SavingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String ACCOUNTS = """
            SELECT s.SDSLT_CODE, t.SLTypeM_DESC, s.SDREF_NO, s.SDREF_NOX, s.SDTR_DATE, s.SDSTATUS, s.SDINT_RATE, s.SDMIN_BAL,
                   (SELECT COALESCE(-SUM(d.AMT), 0) FROM sldtl d
                     WHERE d.SL_BRCODE = s.SDBR_CODE AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = s.SDSLC_CODE
                       AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO AND d.SLE_CODE = 11),
                   (SELECT COALESCE(SUM(h.HHELD_AMT), 0) FROM hold h
                     WHERE h.HBR_CODE = s.SDBR_CODE AND h.ClientIDH = s.ClientIDSD AND h.HSLC_CODE = s.SDSLC_CODE
                       AND h.HSLT_CODE = s.SDSLT_CODE AND h.HREF_NO = s.SDREF_NO),
                   (SELECT MAX(d.TR_DATE) FROM sldtl d
                     WHERE d.SL_BRCODE = s.SDBR_CODE AND d.SL_CLIENTID = s.ClientIDSD AND d.SLC_CODE = s.SDSLC_CODE
                       AND d.SLT_CODE = s.SDSLT_CODE AND d.REF_NO = s.SDREF_NO AND d.SLE_CODE = 11)
            FROM sd s
            LEFT JOIN sltype t ON t.SLTypeBR_CODE = s.SDBR_CODE AND t.SLTypeSLC_CODE = s.SDSLC_CODE AND t.SLTypeSLT_CODE = s.SDSLT_CODE
            WHERE s.SDBR_CODE = ? AND s.ClientIDSD = ? AND s.SDSLC_CODE = ?
            """;

    private static Account map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        java.sql.Date opened = rs.getDate(5);
        java.sql.Date last = rs.getDate(11);
        int status = rs.getInt(6);
        BigDecimal balance = rs.getBigDecimal(9);
        BigDecimal held = rs.getBigDecimal(10);
        return new Account(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), opened == null ? null : opened.toLocalDate(), status, statusName(status),
                rs.getBigDecimal(7), rs.getBigDecimal(8), balance, held, balance.subtract(held).max(BigDecimal.ZERO), last == null ? null : last.toLocalDate());
    }

    @GetMapping
    Summary accounts(@PathVariable int branchCode, @PathVariable int clientId, @RequestParam(defaultValue = "false") boolean includeEmpty) {
        List<Account> accounts = jdbc.query(ACCOUNTS + " ORDER BY s.SDSLT_CODE, s.SDREF_NO", SavingsController::map, branchCode, clientId, CLASS_SAVINGS)
            .stream().filter((x) -> includeEmpty || x.lastActivity() != null).toList();
        BigDecimal total = accounts.stream().map(Account::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal held = accounts.stream().map(Account::held).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(accounts, total, held);
    }

    @GetMapping("/{typeCode}/{refNo}")
    History history(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo,
                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || size < 1 || (long) (page + 1) * size > MAX_WINDOW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for at most " + MAX_WINDOW + " entries from the newest.");
        }
        List<Account> found = jdbc.query(ACCOUNTS + " AND s.SDSLT_CODE = ? AND s.SDREF_NO = ?", SavingsController::map,
                branchCode, clientId, CLASS_SAVINGS, typeCode, refNo);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such savings account.");
        Account account = found.get(0);

        List<Hold> holds = jdbc.query("""
                SELECT hc.Hold_CodeDesc, h.HHOLDDESCR, h.HHELD_AMT, h.HHOLDDATE, h.HOL_REFNO
                FROM hold h LEFT JOIN hold_code hc ON hc.Hold_CodeID = h.HHOLD_CODE
                WHERE h.HBR_CODE = ? AND h.ClientIDH = ? AND h.HSLC_CODE = ? AND h.HSLT_CODE = ? AND h.HREF_NO = ?
                ORDER BY h.HHOLDDATE DESC
                """, (rs, i) -> new Hold(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), rs.getString(5)),
                branchCode, clientId, CLASS_SAVINGS, typeCode, refNo);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND REF_NO = ? AND SLE_CODE = ?
                """, Long.class, branchCode, clientId, CLASS_SAVINGS, typeCode, refNo, PRINCIPAL);

        // Newest first. Each row's balance is the account balance minus everything newer than it.
        record Raw(LocalDate date, LocalDate gl, int code, String name, String doc, BigDecimal amount) {}
        List<Raw> window = jdbc.query("""
                SELECT d.TR_DATE, d.GL_DATE, d.TR_CODE, tt.TransTypeDesc, d.TR_DOCNO, -d.AMT
                FROM sldtl d LEFT JOIN transtype tt ON tt.TransTypeID = d.TR_CODE
                WHERE d.SL_BRCODE = ? AND d.SL_CLIENTID = ? AND d.SLC_CODE = ? AND d.SLT_CODE = ? AND d.REF_NO = ? AND d.SLE_CODE = ?
                ORDER BY d.TR_DATE DESC, d.SEQNO DESC LIMIT ?
                """, (rs, i) -> new Raw(rs.getDate(1).toLocalDate(), rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate(), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6)),
                branchCode, clientId, CLASS_SAVINGS, typeCode, refNo, PRINCIPAL, (page + 1) * size);
        List<Entry> entries = new ArrayList<>();
        BigDecimal running = account.balance();
        for (int i = 0; i < window.size(); i++) {
            Raw r = window.get(i);
            if (i >= page * size) entries.add(new Entry(r.date(), r.gl(), r.code(), r.name(), r.doc(), r.amount(), running));
            running = running.subtract(r.amount());
        }
        return new History(account, holds, entries, page, size, total == null ? 0 : total);
    }
}
