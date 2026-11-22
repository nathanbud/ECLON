package ph.coop.core.accounting;

import java.sql.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The transaction types of the old system's back office windows, for the Trans. type list of Back Office: module 1 of {@code transtype} (General
 * Journal Transactions) or module 2 (Disbursement Transactions). Every type, how many entries the old data has of it and when the last was dated, and
 * whether this application makes it there ({@code entry}: GJ, DCM, CV or CKV) or elsewhere ({@code madeElsewhere}). Read only.
 */
@RestController
@RequestMapping("/api/backoffice/types")
@PreAuthorize("hasAnyRole('ACCOUNTANT','ADMIN')")
class BackOfficeTypeController {

    /** The types the Back Office window makes, by module and old type number: the journal writes type 1, the memo type 2, the vouchers 21 and 22. */
    static final Map<Integer, Map<Integer, String>> ENTRY = Map.of(1, Map.of(1, "GJ", 2, "DCM"), 2, Map.of(21, "CV", 22, "CKV"));
    /** Types this application makes, but on another screen. */
    static final Map<Integer, Map<Integer, String>> ELSEWHERE = Map.of(
        1, Map.of(
            81, "Made with Reverse… on a posted journal or memo.",
            99, "Made by the year-end close (Accounting > Year-end close).",
            4, "Time deposits are renewed on Front Office > TD Maturities."),
        2, Map.of(
            20, "Made by the Loan Module when a loan application is approved; released on Front Office > Disburse.",
            23, "Savings withdrawals are made on Front Office > Teller, which checks the balance, holds and limits.",
            24, "A misc disbursement pays cash out of a drawer, so a cashier makes it as a draft on Front Office > Disburse and posts it there."));

    record Type(int id, String name, String code, long entries, String lastUsed, String entry, String madeElsewhere) {}

    private final JdbcTemplate jdbc;

    BackOfficeTypeController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    List<Type> types(@RequestParam(defaultValue = "1") int module) {
        if (!ENTRY.containsKey(module)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The back office has modules 1 (journal) and 2 (disbursement).");
        Map<Integer, String> entry = ENTRY.get(module), elsewhere = ELSEWHERE.get(module);
        Map<Integer, Long> count = new HashMap<>();
        Map<Integer, String> last = new HashMap<>();
        jdbc.query("SELECT TR_CODE, COUNT(*), MAX(TR_DATE) FROM transsum WHERE TR_MODULE = ? GROUP BY TR_CODE", (rs) -> {
            count.put(rs.getInt(1), rs.getLong(2));
            Date d = rs.getDate(3);
            if (d != null) last.put(rs.getInt(1), d.toLocalDate().toString());
        }, module);
        // transtype's module column is text in the copied database and a number in the old one, so it is compared here as text
        return jdbc.query("SELECT TransTypeID, TransTypeDesc, TransTypeDesc2, TransTypeModule FROM transtype ORDER BY TransTypeID", (rs, i) -> {
            String m = rs.getString(4);
            if (m == null || !m.trim().equals(String.valueOf(module))) return null;
            int id = rs.getInt(1);
            String name = rs.getString(2) == null ? "Type " + id : rs.getString(2).trim();
            String code = rs.getString(3) == null ? "" : rs.getString(3).trim();
            return new Type(id, name, code, count.getOrDefault(id, 0L), last.get(id), entry.get(id), elsewhere.get(id));
        }).stream().filter(java.util.Objects::nonNull).toList();
    }
}
