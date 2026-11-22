package ph.coop.core.member;

import ph.coop.core.connection.DatabaseChanges;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * The KYC review list: active members whose KYC needs attention (no record, incomplete, ID expired, review due), with a count of each. The status is worked out in SQL
 * with the same rules as {@link KycRules#status} (tested by {@code KycReviewTest}). Read only; for people who keep member records.
 */
@RestController
class KycReviewController {

    static final Set<String> STATUSES = Set.of("NONE", "INCOMPLETE", "EXPIRED", "REVIEW_DUE", "COMPLETE");
    static final int MAX_SIZE = 200;

    record Row(int branchCode, int clientId, String name, String status, String idType, String idExpiry, String risk, String nextReview, String updatedAt) {}

    record Review(Map<String, Integer> counts, String status, int page, int size, int total, List<Row> rows) {}

    private final JdbcTemplate jdbc;
    private final Db db;

    KycReviewController(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /** Active members with their KYC status. The two date parameters are today; the branch is optional. A blank text field counts as missing, as in {@link KycRules}. */
    private static final String STATUS_SQL = """
            SELECT c.ClientIDBrCode AS branch_code, c.ClientID AS client_id, c.AccountName AS aname, c.LName AS lname, c.FName AS fname, c.MName AS mname,
                   k.id_type, k.id_expiry, k.risk, k.next_review, k.updated_at,
                   CASE WHEN k.client_id IS NULL THEN 'NONE'
                        WHEN k.id_type IS NULL OR TRIM(k.id_type) = '' OR k.id_number IS NULL OR TRIM(k.id_number) = '' OR k.id_expiry IS NULL
                          OR k.source_of_funds IS NULL OR TRIM(k.source_of_funds) = '' OR k.risk IS NULL OR TRIM(k.risk) = '' OR k.next_review IS NULL THEN 'INCOMPLETE'
                        WHEN k.id_expiry < ? THEN 'EXPIRED'
                        WHEN k.next_review < ? THEN 'REVIEW_DUE'
                        ELSE 'COMPLETE' END AS status
              FROM client c LEFT JOIN ecis_member_kyc k ON k.branch_code = c.ClientIDBrCode AND k.client_id = c.ClientID
             WHERE c.AccountStatus = 1 AND (CAST(? AS CHAR) IS NULL OR c.ClientIDBrCode = ?)
            """;

    @GetMapping("/api/kyc/review")
    @PreAuthorize("hasAnyAuthority('PERM_MEMBER_EDIT', 'ROLE_MEMBERSHIP_OFFICER', 'ROLE_ADMIN')")
    Review review(@RequestParam(defaultValue = "EXPIRED") String status, @RequestParam(required = false) Integer branch,
                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return review(status, branch, page, size, LocalDate.now());
    }

    Review review(String status, Integer branch, int page, int size, LocalDate today) {
        if (!db.tableExists("ecis_member_kyc")) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("the KYC review list"));
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!STATUSES.contains(s)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown KYC status: " + status + ".");
        int n = Math.max(1, Math.min(size, MAX_SIZE));
        int p = Math.max(0, page);
        Date d = Date.valueOf(today);
        Object[] base = {d, d, branch, branch};

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : List.of("EXPIRED", "INCOMPLETE", "REVIEW_DUE", "NONE", "COMPLETE")) counts.put(k, 0);
        jdbc.query("SELECT x.status, COUNT(*) AS n FROM (" + STATUS_SQL + ") x GROUP BY x.status",
            (rs) -> { counts.put(rs.getString("status"), rs.getInt("n")); }, base);

        Object[] args = {d, d, branch, branch, s, n, p * n};
        List<Row> rows = new ArrayList<>(jdbc.query("SELECT * FROM (" + STATUS_SQL + ") x WHERE x.status = ? ORDER BY COALESCE(NULLIF(TRIM(x.aname), ''), x.lname), x.fname, x.branch_code, x.client_id LIMIT ? OFFSET ?",
            (rs, i) -> new Row(rs.getInt("branch_code"), rs.getInt("client_id"), name(rs.getString("aname"), rs.getString("lname"), rs.getString("fname"), rs.getString("mname")), rs.getString("status"),
                rs.getString("id_type"), text(rs.getDate("id_expiry")), rs.getString("risk"), text(rs.getDate("next_review")),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime().toString()), args));
        return new Review(counts, s, p, n, counts.getOrDefault(s, 0), rows);
    }

    private static String text(Date d) { return d == null ? null : d.toLocalDate().toString(); }

    /** The account name when there is one (an organisation), otherwise "Last, First Middle", as the profile shows it. */
    static String name(String account, String last, String first, String middle) {
        if (account != null && !account.isBlank()) return account.trim();
        String l = last == null ? "" : last.trim();
        String f = (first == null ? "" : first.trim()) + (middle == null || middle.isBlank() ? "" : " " + middle.trim());
        return l.isEmpty() ? f.trim() : f.isBlank() ? l : l + ", " + f.trim();
    }
}
